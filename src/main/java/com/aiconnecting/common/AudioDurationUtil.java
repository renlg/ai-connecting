package com.aiconnecting.common;

import java.nio.charset.StandardCharsets;

/**
 * 从音频文件字节流解析真实时长（秒），用于按秒计费，不信任客户端申报的时长。
 * 支持 WAV / FLAC / OGG(Vorbis|Opus) / MP4|M4A / MP3 / ADTS-AAC / WebM。
 * 无法识别或解析失败时返回 -1，由调用方决定拒绝请求或降级处理。
 */
public final class AudioDurationUtil {

    private AudioDurationUtil() {
    }

    /**
     * @return 时长（秒），无法识别时返回 -1
     */
    public static double measure(byte[] d) {
        if (d == null || d.length < 16) {
            return -1;
        }
        try {
            if (match(d, 0, "RIFF") && match(d, 8, "WAVE")) {
                return wavSeconds(d);
            }
            if (match(d, 0, "fLaC")) {
                return flacSeconds(d);
            }
            if (match(d, 0, "OggS")) {
                return oggSeconds(d);
            }
            if (match(d, 4, "ftyp")) {
                return mp4Seconds(d);
            }
            if ((d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                    && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3) {
                return webmSeconds(d);
            }
            int off = 0;
            if (match(d, 0, "ID3")) {
                off = 10 + syncSafe(d, 6);
            }
            if (off + 7 <= d.length && (d[off] & 0xFF) == 0xFF && (d[off + 1] & 0xF6) == 0xF0) {
                return adtsSeconds(d, off);
            }
            return mp3Seconds(d, off);
        } catch (Exception e) {
            return -1;
        }
    }

    /** OpenAI speech pcm 输出为 24kHz 16bit 单声道小端裸样本 */
    public static double pcmSeconds(byte[] d) {
        return d == null || d.length == 0 ? -1 : d.length / 48000.0;
    }

    // ==================== WAV ====================

    private static double wavSeconds(byte[] d) {
        int pos = 12;
        long byteRate = 0;
        long dataSize = -1;
        while (pos + 8 <= d.length) {
            String id = new String(d, pos, 4, StandardCharsets.ISO_8859_1);
            long size = u32le(d, pos + 4);
            if ("fmt ".equals(id) && pos + 20 <= d.length) {
                byteRate = u32le(d, pos + 16);
            } else if ("data".equals(id)) {
                dataSize = Math.min(size, (long) d.length - pos - 8);
            }
            pos += 8 + (int) Math.min(size + (size & 1), Integer.MAX_VALUE);
            if (pos < 0) {
                break;
            }
        }
        return byteRate > 0 && dataSize > 0 ? dataSize / (double) byteRate : -1;
    }

    // ==================== FLAC ====================

    private static double flacSeconds(byte[] d) {
        // STREAMINFO 是第一个元数据块，负载从第 8 字节起；第 18 字节起 8 字节为
        // 采样率(20bit) + 声道(3bit) + 位深(5bit) + 总样本数(36bit)
        if (d.length < 26) {
            return -1;
        }
        long v = u64be(d, 18);
        long sampleRate = v >>> 44;
        long totalSamples = v & 0xFFFFFFFFFL;
        return sampleRate > 0 && totalSamples > 0 ? totalSamples / (double) sampleRate : -1;
    }

    // ==================== OGG (Vorbis / Opus) ====================

    private static double oggSeconds(byte[] d) {
        long rate = -1;
        int head = Math.min(d.length, 512);
        if (indexOf(d, "OpusHead", 0, head) >= 0) {
            rate = 48000; // Opus granule position 恒按 48kHz 计
        } else {
            int idx = indexOf(d, "vorbis", 0, head);
            // Vorbis 标识头: \x01 "vorbis" version(4) channels(1) rate(4 LE)
            if (idx >= 1 && d[idx - 1] == 1 && idx + 15 <= d.length) {
                rate = u32le(d, idx + 11);
            }
        }
        if (rate <= 0) {
            return -1;
        }
        int last = lastIndexOf(d, "OggS");
        if (last < 0 || last + 14 > d.length) {
            return -1;
        }
        long granule = u64le(d, last + 6);
        return granule > 0 ? granule / (double) rate : -1;
    }

    // ==================== MP4 / M4A ====================

    private static double mp4Seconds(byte[] d) {
        return mp4Find(d, 0, d.length);
    }

    private static double mp4Find(byte[] d, int start, int end) {
        int pos = start;
        while (pos + 8 <= end) {
            long size = u32be(d, pos);
            String type = new String(d, pos + 4, 4, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1 && pos + 16 <= end) {
                size = u64be(d, pos + 8);
                header = 16;
            } else if (size == 0) {
                size = end - pos;
            }
            if (size < header) {
                return -1;
            }
            int boxEnd = (int) Math.min(end, pos + size);
            if ("moov".equals(type)) {
                double r = mp4Find(d, pos + header, boxEnd);
                if (r > 0) {
                    return r;
                }
            } else if ("mvhd".equals(type) && pos + header + 32 <= end) {
                int p = pos + header;
                int version = d[p] & 0xFF;
                long timescale;
                long duration;
                if (version == 1) {
                    timescale = u32be(d, p + 20);
                    duration = u64be(d, p + 24);
                } else {
                    timescale = u32be(d, p + 12);
                    duration = u32be(d, p + 16);
                }
                return timescale > 0 && duration > 0 ? duration / (double) timescale : -1;
            }
            pos = boxEnd;
        }
        return -1;
    }

    // ==================== WebM (EBML) ====================

    private static double webmSeconds(byte[] d) {
        long[] scale = {1_000_000L}; // TimecodeScale 默认 1ms（纳秒单位）
        double[] durTicks = {-1};
        ebmlWalk(d, 0, d.length, scale, durTicks, 0);
        return durTicks[0] > 0 ? durTicks[0] * scale[0] / 1e9 : -1;
    }

    private static void ebmlWalk(byte[] d, int start, int end, long[] scale, double[] dur, int depth) {
        if (depth > 3) {
            return;
        }
        int pos = start;
        while (pos < end) {
            long[] idr = ebmlRead(d, pos, end, false);
            if (idr == null) {
                return;
            }
            long id = idr[0];
            pos = (int) idr[1];
            long[] szr = ebmlRead(d, pos, end, true);
            if (szr == null) {
                return;
            }
            long size = szr[0];
            pos = (int) szr[1];
            int elEnd = size < 0 ? end : (int) Math.min(end, pos + size);
            if (id == 0x18538067L || id == 0x1549A966L) { // Segment / Info
                ebmlWalk(d, pos, elEnd, scale, dur, depth + 1);
            } else if (id == 0x2AD7B1L && size > 0 && size <= 8) { // TimecodeScale
                scale[0] = readUintBE(d, pos, (int) size);
            } else if (id == 0x4489L && (size == 4 || size == 8)) { // Duration (float)
                dur[0] = size == 4
                        ? Float.intBitsToFloat((int) readUintBE(d, pos, 4))
                        : Double.longBitsToDouble(readUintBE(d, pos, 8));
            }
            if (elEnd < pos) {
                return;
            }
            pos = Math.max(pos, elEnd);
        }
    }

    private static long[] ebmlRead(byte[] d, int pos, int end, boolean stripMarker) {
        if (pos >= end) {
            return null;
        }
        int first = d[pos] & 0xFF;
        if (first == 0) {
            return null;
        }
        int len = Integer.numberOfLeadingZeros(first) - 23;
        if (len < 1 || len > 8 || pos + len > end) {
            return null;
        }
        long value = stripMarker ? (first & (0xFF >>> len)) : first;
        boolean allOnes = stripMarker && (first & (0xFF >>> len)) == (0xFF >>> len);
        for (int i = 1; i < len; i++) {
            int b = d[pos + i] & 0xFF;
            value = (value << 8) | b;
            if (b != 0xFF) {
                allOnes = false;
            }
        }
        if (allOnes) {
            value = -1; // 未知长度
        }
        return new long[]{value, pos + len};
    }

    // ==================== MP3 (Layer III) ====================

    private static final int[][] L3_BITRATES = {
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320}, // MPEG1
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160}      // MPEG2 / 2.5
    };
    private static final int[][] MP3_SAMPLE_RATES = {
            {44100, 48000, 32000}, {22050, 24000, 16000}, {11025, 12000, 8000}
    };

    private static double mp3Seconds(byte[] d, int off) {
        double seconds = 0;
        int frames = 0;
        int pos = Math.max(off, 0);
        while (pos + 4 <= d.length) {
            if ((d[pos] & 0xFF) != 0xFF || (d[pos + 1] & 0xE0) != 0xE0) {
                pos++;
                continue;
            }
            int b1 = d[pos + 1] & 0xFF;
            int b2 = d[pos + 2] & 0xFF;
            int versionBits = (b1 >> 3) & 3; // 0=MPEG2.5, 2=MPEG2, 3=MPEG1
            int layerBits = (b1 >> 1) & 3;   // 1=Layer III
            int bitrateIdx = (b2 >> 4) & 0xF;
            int rateIdx = (b2 >> 2) & 3;
            int padding = (b2 >> 1) & 1;
            if (versionBits == 1 || layerBits != 1 || bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) {
                pos++;
                continue;
            }
            boolean mpeg1 = versionBits == 3;
            int sampleRate = MP3_SAMPLE_RATES[mpeg1 ? 0 : (versionBits == 2 ? 1 : 2)][rateIdx];
            int bitrate = L3_BITRATES[mpeg1 ? 0 : 1][bitrateIdx] * 1000;
            int samplesPerFrame = mpeg1 ? 1152 : 576;
            int frameLen = samplesPerFrame / 8 * bitrate / sampleRate + padding;
            if (frameLen <= 4) {
                pos++;
                continue;
            }
            seconds += samplesPerFrame / (double) sampleRate;
            frames++;
            pos += frameLen;
        }
        return frames >= 3 ? seconds : -1;
    }

    // ==================== ADTS AAC ====================

    private static final int[] AAC_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    private static double adtsSeconds(byte[] d, int off) {
        double seconds = 0;
        int frames = 0;
        int pos = Math.max(off, 0);
        while (pos + 7 <= d.length) {
            if ((d[pos] & 0xFF) != 0xFF || (d[pos + 1] & 0xF6) != 0xF0) {
                pos++;
                continue;
            }
            int rateIdx = (d[pos + 2] >> 2) & 0xF;
            int frameLen = ((d[pos + 3] & 3) << 11) | ((d[pos + 4] & 0xFF) << 3) | ((d[pos + 5] & 0xFF) >> 5);
            if (rateIdx >= AAC_RATES.length || frameLen < 7) {
                pos++;
                continue;
            }
            int rawBlocks = (d[pos + 6] & 3) + 1;
            seconds += rawBlocks * 1024.0 / AAC_RATES[rateIdx];
            frames++;
            pos += frameLen;
        }
        return frames >= 1 ? seconds : -1;
    }

    // ==================== 工具方法 ====================

    private static boolean match(byte[] d, int pos, String s) {
        if (pos + s.length() > d.length) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (d[pos + i] != (byte) s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] d, String s, int from, int to) {
        for (int i = from; i <= to - s.length(); i++) {
            if (match(d, i, s)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] d, String s) {
        for (int i = d.length - s.length(); i >= 0; i--) {
            if (match(d, i, s)) {
                return i;
            }
        }
        return -1;
    }

    private static int syncSafe(byte[] d, int pos) {
        return ((d[pos] & 0x7F) << 21) | ((d[pos + 1] & 0x7F) << 14)
                | ((d[pos + 2] & 0x7F) << 7) | (d[pos + 3] & 0x7F);
    }

    private static long u32le(byte[] d, int pos) {
        return (d[pos] & 0xFFL) | (d[pos + 1] & 0xFFL) << 8 | (d[pos + 2] & 0xFFL) << 16 | (d[pos + 3] & 0xFFL) << 24;
    }

    private static long u32be(byte[] d, int pos) {
        return (d[pos] & 0xFFL) << 24 | (d[pos + 1] & 0xFFL) << 16 | (d[pos + 2] & 0xFFL) << 8 | (d[pos + 3] & 0xFFL);
    }

    private static long u64be(byte[] d, int pos) {
        return u32be(d, pos) << 32 | u32be(d, pos + 4);
    }

    private static long u64le(byte[] d, int pos) {
        return u32le(d, pos) | u32le(d, pos + 4) << 32;
    }

    private static long readUintBE(byte[] d, int pos, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (d[pos + i] & 0xFF);
        }
        return v;
    }
}
