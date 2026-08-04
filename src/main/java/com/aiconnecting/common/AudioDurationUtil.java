package com.aiconnecting.common;

import java.nio.charset.StandardCharsets;

/**
 * 从音频文件字节流实测真实时长（秒），用于按秒计费，不信任客户端申报的时长，
 * 也不信任容器头部单独声明的时长字段（可被伪造以少计费）：
 * <ul>
 *   <li>WAV：校验 fmt 块内部一致性（byteRate = sampleRate × blockAlign），按实际存在的 data 字节计算</li>
 *   <li>FLAC：逐帧扫描帧头并校验 CRC-8，累计各帧块大小，不使用 STREAMINFO 总样本数</li>
 *   <li>OGG(Vorbis|Opus)：顺序遍历页并校验每页 CRC-32，页必须铺满整个文件，
 *       granule 单调递增，取音频流最大 granule</li>
 *   <li>MP4|M4A：顶层 box 必须铺满文件，按音频 trak 的 stts 样本表计时，
 *       校验 stts/stsz 样本数一致、声明的媒体字节不超过 mdat 实际字节，AAC 再与帧数交叉校验</li>
 *   <li>WebM：遍历 Cluster 按块时间戳实测，不信任 Duration 元数据元素</li>
 *   <li>MP3 / ADTS-AAC：逐帧解析累计</li>
 * </ul>
 * 裸 PCM 无自描述采样率/声道/位深，无法从字节可靠测量，不作为上传格式支持
 * （{@link #pcmSeconds} 仅用于 speech 输出路径，格式由 OpenAI 规范固定）。
 * 无法识别或校验失败时返回 -1，由调用方拒绝请求（400），绝不按伪造数据计费。
 */
public final class AudioDurationUtil {

    private AudioDurationUtil() {
    }

    /**
     * @return 时长（秒），无法识别或结构校验失败时返回 -1
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

    /** 仅供 speech 输出路径使用：OpenAI speech pcm 输出由规范固定为 24kHz 16bit 单声道小端裸样本 */
    public static double pcmSeconds(byte[] d) {
        return d == null || d.length == 0 ? -1 : d.length / 48000.0;
    }

    // ==================== WAV ====================

    private static double wavSeconds(byte[] d) {
        int pos = 12;
        int audioFormat = 0;
        int channels = 0;
        int bitsPerSample = 0;
        int blockAlign = 0;
        long sampleRate = 0;
        long byteRate = 0;
        long dataBytes = -1;
        while (pos + 8 <= d.length) {
            long size = u32le(d, pos + 4);
            if ("fmt ".equals(chunkId(d, pos)) && size >= 16 && pos + 24 <= d.length) {
                audioFormat = (int) u16le(d, pos + 8);
                channels = (int) u16le(d, pos + 10);
                sampleRate = u32le(d, pos + 12);
                byteRate = u32le(d, pos + 16);
                blockAlign = (int) u16le(d, pos + 20);
                bitsPerSample = (int) u16le(d, pos + 22);
            } else if ("data".equals(chunkId(d, pos))) {
                // 只按文件中实际存在的字节计费，声明超出文件长度的部分不计
                dataBytes = Math.min(size, (long) d.length - pos - 8);
            }
            long next = pos + 8L + size + (size & 1);
            if (next <= pos || next > Integer.MAX_VALUE) {
                break;
            }
            pos = (int) next;
        }
        // 仅支持 PCM / IEEE float / extensible，且 fmt 内部字段必须自洽，防止伪造 byteRate 少计费
        boolean pcmLike = audioFormat == 1 || audioFormat == 3 || audioFormat == 0xFFFE;
        if (!pcmLike || channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0 || dataBytes <= 0) {
            return -1;
        }
        long expectedBlockAlign = (long) channels * bitsPerSample / 8;
        if (expectedBlockAlign <= 0 || blockAlign != expectedBlockAlign
                || byteRate != sampleRate * expectedBlockAlign) {
            return -1;
        }
        return dataBytes / (double) byteRate;
    }

    private static String chunkId(byte[] d, int pos) {
        return new String(d, pos, 4, StandardCharsets.ISO_8859_1);
    }

    // ==================== FLAC ====================

    private static final int[] FLAC_CRC8_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80) != 0 ? ((r << 1) ^ 0x07) & 0xFF : (r << 1) & 0xFF;
            }
            FLAC_CRC8_TABLE[i] = r;
        }
    }

    private static double flacSeconds(byte[] d) {
        // 遍历元数据块定位音频帧起始；STREAMINFO 仅用于取采样率兜底，总样本数不采信
        int pos = 4;
        long streamRate = -1;
        boolean last = false;
        while (!last) {
            if (pos + 4 > d.length) {
                return -1;
            }
            int header = d[pos] & 0xFF;
            last = (header & 0x80) != 0;
            int type = header & 0x7F;
            int len = ((d[pos + 1] & 0xFF) << 16) | ((d[pos + 2] & 0xFF) << 8) | (d[pos + 3] & 0xFF);
            if (type == 0 && len >= 18 && pos + 4 + 18 <= d.length) {
                streamRate = u64be(d, pos + 4 + 10) >>> 44;
            }
            pos += 4 + len;
            if (pos < 0 || pos > d.length) {
                return -1;
            }
        }
        // 逐帧扫描：帧头需通过 CRC-8 校验，按各帧实际块大小累计时长
        double seconds = 0;
        int frames = 0;
        int p = pos;
        while (p + 6 <= d.length) {
            double s = flacFrameSeconds(d, p, streamRate);
            if (s < 0) {
                p++;
                continue;
            }
            seconds += s;
            frames++;
            p += 6; // FLAC 帧长不自描述，跳过帧头后继续搜索下一个同步字
        }
        return frames >= 1 && seconds > 0 ? seconds : -1;
    }

    /** 解析并校验一个 FLAC 帧头（含 CRC-8），返回该帧时长（秒），无效返回 -1 */
    private static double flacFrameSeconds(byte[] d, int p, long streamRate) {
        if ((d[p] & 0xFF) != 0xFF || (d[p + 1] & 0xFC) != 0xF8) {
            return -1;
        }
        int bsCode = (d[p + 2] >> 4) & 0xF;
        int srCode = d[p + 2] & 0xF;
        int chCode = (d[p + 3] >> 4) & 0xF;
        if ((d[p + 3] & 1) != 0 || bsCode == 0 || srCode == 15 || chCode > 10) {
            return -1;
        }
        int idx = p + 4;
        int first = d[idx] & 0xFF;
        int extra;
        if (first < 0x80) extra = 0;
        else if ((first & 0xE0) == 0xC0) extra = 1;
        else if ((first & 0xF0) == 0xE0) extra = 2;
        else if ((first & 0xF8) == 0xF0) extra = 3;
        else if ((first & 0xFC) == 0xF8) extra = 4;
        else if ((first & 0xFE) == 0xFC) extra = 5;
        else if (first == 0xFE) extra = 6;
        else return -1;
        idx++;
        for (int i = 0; i < extra; i++) {
            if (idx >= d.length || (d[idx] & 0xC0) != 0x80) {
                return -1;
            }
            idx++;
        }
        long blockSize;
        if (bsCode == 1) {
            blockSize = 192;
        } else if (bsCode <= 5) {
            blockSize = 576L << (bsCode - 2);
        } else if (bsCode == 6) {
            if (idx + 1 > d.length) return -1;
            blockSize = (d[idx] & 0xFF) + 1L;
            idx += 1;
        } else if (bsCode == 7) {
            if (idx + 2 > d.length) return -1;
            blockSize = (((d[idx] & 0xFF) << 8) | (d[idx + 1] & 0xFF)) + 1L;
            idx += 2;
        } else {
            blockSize = 256L << (bsCode - 8);
        }
        long rate;
        switch (srCode) {
            case 0 -> rate = streamRate;
            case 1 -> rate = 88200;
            case 2 -> rate = 176400;
            case 3 -> rate = 192000;
            case 4 -> rate = 8000;
            case 5 -> rate = 16000;
            case 6 -> rate = 22050;
            case 7 -> rate = 24000;
            case 8 -> rate = 32000;
            case 9 -> rate = 44100;
            case 10 -> rate = 48000;
            case 11 -> rate = 96000;
            case 12 -> {
                if (idx + 1 > d.length) return -1;
                rate = (d[idx] & 0xFF) * 1000L;
                idx += 1;
            }
            case 13 -> {
                if (idx + 2 > d.length) return -1;
                rate = ((d[idx] & 0xFF) << 8) | (d[idx + 1] & 0xFF);
                idx += 2;
            }
            case 14 -> {
                if (idx + 2 > d.length) return -1;
                rate = (((d[idx] & 0xFF) << 8) | (d[idx + 1] & 0xFF)) * 10L;
                idx += 2;
            }
            default -> {
                return -1;
            }
        }
        if (idx >= d.length) {
            return -1;
        }
        int crc = 0;
        for (int i = p; i < idx; i++) {
            crc = FLAC_CRC8_TABLE[(crc ^ (d[i] & 0xFF)) & 0xFF];
        }
        if ((d[idx] & 0xFF) != crc) {
            return -1;
        }
        return rate > 0 && blockSize > 0 ? blockSize / (double) rate : -1;
    }

    // ==================== OGG (Vorbis / Opus) ====================

    private static final int[] OGG_CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            OGG_CRC_TABLE[i] = r;
        }
    }

    /**
     * 顺序遍历所有页并逐页校验 CRC-32，页必须首尾相接铺满整个文件；
     * 音频流 granule 必须单调不减，任何结构或校验失败直接判为不可测（-1），
     * 防止追加伪造的小 granule 尾页把长音频按 1 秒计费
     */
    private static double oggSeconds(byte[] d) {
        long rate = -1;
        long audioSerial = 0;
        boolean serialFound = false;
        long maxGranule = -1;
        long prevGranule = -1;
        int pos = 0;
        while (pos < d.length) {
            if (pos + 27 > d.length || !match(d, pos, "OggS") || d[pos + 4] != 0) {
                return -1;
            }
            int segCount = d[pos + 26] & 0xFF;
            int headerLen = 27 + segCount;
            if (pos + headerLen > d.length) {
                return -1;
            }
            int bodyLen = 0;
            for (int i = 0; i < segCount; i++) {
                bodyLen += d[pos + 27 + i] & 0xFF;
            }
            int pageLen = headerLen + bodyLen;
            if (pos + pageLen > d.length) {
                return -1;
            }
            if (oggPageCrc(d, pos, pageLen) != (int) u32le(d, pos + 22)) {
                return -1;
            }
            long granule = u64le(d, pos + 6);
            long serial = u32le(d, pos + 14);
            int body = pos + headerLen;
            if (!serialFound && bodyLen >= 8) {
                if (match(d, body, "OpusHead")) {
                    rate = 48000; // Opus granule position 恒按 48kHz 计
                    audioSerial = serial;
                    serialFound = true;
                } else if (bodyLen >= 16 && d[body] == 1 && match(d, body + 1, "vorbis")) {
                    rate = u32le(d, body + 12);
                    audioSerial = serial;
                    serialFound = true;
                }
            }
            if (serialFound && serial == audioSerial && granule != -1) {
                if (granule < prevGranule) {
                    return -1;
                }
                prevGranule = granule;
                maxGranule = Math.max(maxGranule, granule);
            }
            pos += pageLen;
        }
        return serialFound && rate > 0 && maxGranule > 0 ? maxGranule / (double) rate : -1;
    }

    /** Ogg 页 CRC-32（多项式 0x04C11DB7，初值 0，无反射），CRC 字段本身按 0 参与计算 */
    private static int oggPageCrc(byte[] d, int off, int len) {
        int crc = 0;
        for (int i = 0; i < len; i++) {
            int b = i >= 22 && i < 26 ? 0 : d[off + i] & 0xFF;
            crc = (crc << 8) ^ OGG_CRC_TABLE[((crc >>> 24) ^ b) & 0xFF];
        }
        return crc;
    }

    // ==================== MP4 / M4A ====================

    /**
     * 不使用 mvhd 影片头时长（可伪造）：按音频 trak 的 stts 样本表实测，
     * 顶层 box 必须铺满文件，样本表内部一致且声明的媒体字节不超过 mdat 实际字节
     */
    private static double mp4Seconds(byte[] d) {
        long mdatBytes = 0;
        int moovStart = -1;
        int moovEnd = -1;
        int pos = 0;
        while (pos + 8 <= d.length) {
            long size = u32be(d, pos);
            int header = 8;
            if (size == 1) {
                if (pos + 16 > d.length) {
                    return -1;
                }
                size = u64be(d, pos + 8);
                header = 16;
            } else if (size == 0) {
                size = d.length - pos;
            }
            if (size < header || size > d.length - pos) {
                return -1;
            }
            String type = chunkId(d, pos + 4);
            if ("moov".equals(type)) {
                moovStart = pos + header;
                moovEnd = (int) (pos + size);
            } else if ("mdat".equals(type)) {
                mdatBytes += size - header;
            }
            pos += (int) size;
        }
        if (pos != d.length || moovStart < 0) {
            return -1;
        }
        int p = moovStart;
        while (p + 8 <= moovEnd) {
            int[] box = readBox(d, p, moovEnd);
            if (box == null) {
                return -1;
            }
            if ("trak".equals(chunkId(d, p + 4))) {
                double r = trakAudioSeconds(d, box[0], box[1], mdatBytes);
                if (r > 0) {
                    return r;
                }
            }
            p = box[1];
        }
        return -1;
    }

    private static double trakAudioSeconds(byte[] d, int start, int end, long mdatBytes) {
        int[] mdia = findBox(d, start, end, "mdia");
        if (mdia == null) {
            return -1;
        }
        int[] hdlr = findBox(d, mdia[0], mdia[1], "hdlr");
        if (hdlr == null || hdlr[0] + 12 > hdlr[1] || !match(d, hdlr[0] + 8, "soun")) {
            return -1;
        }
        int[] mdhd = findBox(d, mdia[0], mdia[1], "mdhd");
        if (mdhd == null || mdhd[0] + 24 > mdhd[1]) {
            return -1;
        }
        long timescale = (d[mdhd[0]] & 0xFF) == 1 ? u32be(d, mdhd[0] + 20) : u32be(d, mdhd[0] + 12);
        if (timescale <= 0) {
            return -1;
        }
        int[] minf = findBox(d, mdia[0], mdia[1], "minf");
        int[] stbl = minf != null ? findBox(d, minf[0], minf[1], "stbl") : null;
        int[] stts = stbl != null ? findBox(d, stbl[0], stbl[1], "stts") : null;
        int[] stsz = stbl != null ? findBox(d, stbl[0], stbl[1], "stsz") : null;
        if (stts == null || stsz == null || stts[0] + 8 > stts[1] || stsz[0] + 12 > stsz[1]) {
            return -1;
        }
        long entryCount = u32be(d, stts[0] + 4);
        if (stts[0] + 8 + entryCount * 8 > stts[1]) {
            return -1;
        }
        long sttsSamples = 0;
        long sttsTicks = 0;
        for (long i = 0; i < entryCount; i++) {
            int e = (int) (stts[0] + 8 + i * 8);
            long cnt = u32be(d, e);
            long delta = u32be(d, e + 4);
            sttsSamples += cnt;
            sttsTicks += cnt * delta;
            if (sttsSamples < 0 || sttsTicks < 0 || sttsTicks > (1L << 52)) {
                return -1;
            }
        }
        long uniformSize = u32be(d, stsz[0] + 4);
        long sampleCount = u32be(d, stsz[0] + 8);
        long totalBytes;
        if (uniformSize != 0) {
            totalBytes = uniformSize * sampleCount;
        } else {
            if (stsz[0] + 12 + sampleCount * 4 > stsz[1]) {
                return -1;
            }
            totalBytes = 0;
            for (long i = 0; i < sampleCount; i++) {
                totalBytes += u32be(d, (int) (stsz[0] + 12 + i * 4));
                if (totalBytes < 0) {
                    return -1;
                }
            }
        }
        // 样本表必须自洽，且声明的媒体数据不能超过文件中实际存在的 mdat 字节
        if (sampleCount <= 0 || sttsSamples != sampleCount || totalBytes <= 0 || totalBytes > mdatBytes) {
            return -1;
        }
        double sttsSeconds = sttsTicks / (double) timescale;
        // AAC：用可解码帧数（每帧 1024 样本）与 stts 声明时长交叉校验，明显不符判为伪造
        int[] stsd = findBox(d, stbl[0], stbl[1], "stsd");
        if (stsd != null) {
            int[] mp4a = findBox(d, stsd[0] + 8, stsd[1], "mp4a");
            if (mp4a != null && mp4a[0] + 28 <= mp4a[1]) {
                long codecRate = u32be(d, mp4a[0] + 24) >>> 16;
                if (codecRate > 0) {
                    double frameSeconds = sampleCount * 1024.0 / codecRate;
                    if (Math.abs(frameSeconds - sttsSeconds) > Math.max(1.0, sttsSeconds * 0.25)) {
                        return -1;
                    }
                    return Math.max(sttsSeconds, frameSeconds);
                }
            }
        }
        return sttsSeconds > 0 ? sttsSeconds : -1;
    }

    /** 读取 pos 处的 box，返回 {负载起点, box 终点}，结构非法返回 null */
    private static int[] readBox(byte[] d, int pos, int end) {
        if (pos + 8 > end) {
            return null;
        }
        long size = u32be(d, pos);
        int header = 8;
        if (size == 1) {
            if (pos + 16 > end) {
                return null;
            }
            size = u64be(d, pos + 8);
            header = 16;
        } else if (size == 0) {
            size = end - pos;
        }
        if (size < header || size > end - pos) {
            return null;
        }
        return new int[]{pos + header, (int) (pos + size)};
    }

    /** 在 [start, end) 的子 box 序列中查找第一个指定类型，返回 {负载起点, box 终点} */
    private static int[] findBox(byte[] d, int start, int end, String type) {
        int pos = start;
        while (pos + 8 <= end) {
            int[] box = readBox(d, pos, end);
            if (box == null) {
                return null;
            }
            if (type.equals(chunkId(d, pos + 4))) {
                return box;
            }
            pos = box[1];
        }
        return null;
    }

    // ==================== WebM (EBML) ====================

    private static final class WebmState {
        long timecodeScale = 1_000_000L; // 默认 1ms（纳秒单位）
        long clusterTime = -1;
        long maxTicks = -1;
    }

    /** 不信任 Duration 元数据元素：遍历 Cluster，按块时间戳实测最大时间点 */
    private static double webmSeconds(byte[] d) {
        WebmState st = new WebmState();
        webmWalk(d, 0, d.length, st, 0);
        return st.maxTicks > 0 ? st.maxTicks * st.timecodeScale / 1e9 : -1;
    }

    private static void webmWalk(byte[] d, int start, int end, WebmState st, int depth) {
        if (depth > 5) {
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
                webmWalk(d, pos, elEnd, st, depth + 1);
            } else if (id == 0x2AD7B1L && size > 0 && size <= 8) { // TimecodeScale
                long v = readUintBE(d, pos, (int) size);
                if (v > 0) {
                    st.timecodeScale = v;
                }
            } else if (id == 0x1F43B675L) { // Cluster
                st.clusterTime = -1;
                webmWalk(d, pos, elEnd, st, depth + 1);
            } else if (id == 0xE7L && size > 0 && size <= 8) { // Cluster Timecode
                st.clusterTime = readUintBE(d, pos, (int) size);
            } else if (id == 0xA0L) { // BlockGroup
                webmWalk(d, pos, elEnd, st, depth + 1);
            } else if ((id == 0xA3L || id == 0xA1L) && st.clusterTime >= 0) { // SimpleBlock / Block
                long[] track = ebmlRead(d, pos, elEnd, true);
                if (track != null && (int) track[1] + 2 <= elEnd) {
                    int p2 = (int) track[1];
                    int rel = (short) (((d[p2] & 0xFF) << 8) | (d[p2 + 1] & 0xFF));
                    st.maxTicks = Math.max(st.maxTicks, st.clusterTime + rel);
                }
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

    private static int syncSafe(byte[] d, int pos) {
        return ((d[pos] & 0x7F) << 21) | ((d[pos + 1] & 0x7F) << 14)
                | ((d[pos + 2] & 0x7F) << 7) | (d[pos + 3] & 0x7F);
    }

    private static long u16le(byte[] d, int pos) {
        return (d[pos] & 0xFFL) | (d[pos + 1] & 0xFFL) << 8;
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
