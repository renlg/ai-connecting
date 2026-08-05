package com.aiconnecting.common;

import java.nio.charset.StandardCharsets;

/**
 * 从音频文件字节流实测真实时长（秒），用于按秒计费，不信任客户端申报的时长，
 * 也不信任容器头部单独声明的时长字段（可被伪造以少计费）：
 * <ul>
 *   <li>WAV：校验 fmt 块内部一致性（byteRate = sampleRate × blockAlign），按实际存在的 data 字节计算</li>
 *   <li>FLAC：逐帧完整解码子帧位流（含 Rice 残差编码）以精确定位帧边界，校验帧头 CRC-8
 *       与帧尾 CRC-16，帧必须首尾相接铺满全部数据，不使用 STREAMINFO 总样本数</li>
 *   <li>OGG(Opus)：顺序遍历页并校验每页 CRC-32，重建 Opus 分组（packet）边界，
 *       按分组 TOC 字节推导的真实帧时长累计，不信任可被任意篡改的 granule 位置；
 *       校验首页 BOS、末页 EOS、页序号连续、分组跨页续传标志；Ogg/Vorbis 因缺乏可靠的
 *       免解码时长推导方式，一律判定为不可测</li>
 *   <li>MP4|M4A：顶层 box 必须铺满文件，按音频 trak 的 stts 样本表计时，
 *       校验 stts/stsz 样本数一致，并通过 stsc + stco/co64 换算每个 chunk 的真实字节区间，
 *       要求全部落在文件中真实存在的 mdat box 字节范围内（而不仅仅是总字节数不超标），
 *       AAC 再与帧数交叉校验</li>
 *   <li>WebM：严格遍历 EBML 树，任何结构断裂直接判为不可测（不采信断裂前已收集的部分时间戳），
 *       要求存在音频 Track，按 Cluster 内块时间戳实测，不信任 Duration 元数据元素</li>
 *   <li>MP3 / ADTS-AAC：帧序列必须从数据起始处开始、逐帧相接、连续铺满至文件末尾，
 *       不允许逐字节跳过垃圾数据寻找可解析帧</li>
 * </ul>
 * 裸 PCM 无自描述采样率/声道/位深，无法从字节可靠测量，不作为上传格式支持
 * （{@link #pcmSeconds} 仅用于 speech 输出路径，格式由 OpenAI 规范固定）。
 * 无法识别或校验失败时返回 -1，由调用方拒绝请求（400），绝不按伪造数据计费。
 */
public final class AudioDurationUtil {

    private static final double MAX_AUDIO_DURATION_SECONDS = 24 * 3600;

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
            double seconds = measureSupportedFormat(d);
            return isSaneDuration(seconds) ? seconds : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static double measureSupportedFormat(byte[] d) {
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
    }

    /** 仅供 speech 输出路径使用：OpenAI speech pcm 输出由规范固定为 24kHz 16bit 单声道小端裸样本 */
    public static double pcmSeconds(byte[] d) {
        double seconds = d == null || d.length == 0 ? -1 : d.length / 48000.0;
        return isSaneDuration(seconds) ? seconds : -1;
    }

    private static boolean isSaneDuration(double seconds) {
        return Double.isFinite(seconds) && seconds > 0 && seconds <= MAX_AUDIO_DURATION_SECONDS;
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

    private static final int[] FLAC_CRC16_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 8;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x8000) != 0 ? ((r << 1) ^ 0x8005) & 0xFFFF : (r << 1) & 0xFFFF;
            }
            FLAC_CRC16_TABLE[i] = r;
        }
    }

    private static final int[] FLAC_BPS_TABLE = {0, 8, 12, 0, 16, 20, 24, 32};

    private static double flacSeconds(byte[] d) {
        // 遍历元数据块定位音频帧起始；STREAMINFO 仅用于取采样率/位深/声道兜底，总样本数不采信
        int pos = 4;
        long streamRate = -1;
        int streamChannels = -1;
        int streamBps = -1;
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
                long streamInfo = u64be(d, pos + 4 + 10);
                streamRate = streamInfo >>> 44;
                streamChannels = (int) ((streamInfo >>> 41) & 0x7) + 1;
                streamBps = (int) ((streamInfo >>> 36) & 0x1F) + 1;
            }
            pos += 4 + len;
            if (pos < 0 || pos > d.length) {
                return -1;
            }
        }
        // 逐帧完整解码子帧位流以精确确定帧边界（帧长不自描述），并校验帧尾 CRC-16；
        // 帧必须首尾相接、无缝铺满从元数据结束到文件末尾的全部字节，任何结构断裂或
        // 伪造的帧体（如帧头合法但子帧数据不是有效编码）都直接判为不可测，不允许部分计费
        double seconds = 0;
        int frames = 0;
        int p = pos;
        FlacFrame previous = null;
        while (p < d.length) {
            FlacFrame frame = parseFlacFrameFull(d, p, streamRate, streamChannels, streamBps);
            if (frame == null) {
                return -1;
            }
            if (previous != null && !isNextFlacFrame(previous, frame)) {
                return -1;
            }
            seconds += frame.seconds();
            frames++;
            previous = frame;
            p += frame.length();
        }
        return frames >= 1 && seconds > 0 && p == d.length ? seconds : -1;
    }

    private static boolean isNextFlacFrame(FlacFrame previous, FlacFrame current) {
        if (previous.variableBlock() != current.variableBlock()) {
            return false;
        }
        long expected = previous.number() + (previous.variableBlock() ? previous.blockSize() : 1);
        return current.number() == expected;
    }

    /**
     * 解析一个 FLAC 帧：校验帧头 CRC-8 后，逐位完整解码全部子帧（含 wasted-bits、
     * CONSTANT/VERBATIM/FIXED/LPC 及 Rice 残差编码）以精确得到帧体长度，
     * 再校验帧尾 CRC-16；任一环节失败返回 null（不接受"头合法、体不解码"的伪造帧）
     */
    private static FlacFrame parseFlacFrameFull(byte[] d, int p, long streamRate, int streamChannels, int streamBps) {
        if (p + 6 > d.length) {
            return null;
        }
        if ((d[p] & 0xFF) != 0xFF || (d[p + 1] & 0xFC) != 0xF8) {
            return null;
        }
        int bsCode = (d[p + 2] >> 4) & 0xF;
        int srCode = d[p + 2] & 0xF;
        int chCode = (d[p + 3] >> 4) & 0xF;
        int bpsCode = (d[p + 3] >> 1) & 0x7;
        if ((d[p + 3] & 1) != 0 || bsCode == 0 || srCode == 15 || chCode > 10 || bpsCode == 3 || bpsCode == 7) {
            return null;
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
        else return null;
        long frameNumber = first & (0x3F >> extra);
        idx++;
        for (int i = 0; i < extra; i++) {
            if (idx >= d.length || (d[idx] & 0xC0) != 0x80) {
                return null;
            }
            frameNumber = (frameNumber << 6) | (d[idx] & 0x3F);
            idx++;
        }
        long blockSize;
        if (bsCode == 1) {
            blockSize = 192;
        } else if (bsCode <= 5) {
            blockSize = 576L << (bsCode - 2);
        } else if (bsCode == 6) {
            if (idx + 1 > d.length) return null;
            blockSize = (d[idx] & 0xFF) + 1L;
            idx += 1;
        } else if (bsCode == 7) {
            if (idx + 2 > d.length) return null;
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
                if (idx + 1 > d.length) return null;
                rate = (d[idx] & 0xFF) * 1000L;
                idx += 1;
            }
            case 13 -> {
                if (idx + 2 > d.length) return null;
                rate = ((d[idx] & 0xFF) << 8) | (d[idx + 1] & 0xFF);
                idx += 2;
            }
            case 14 -> {
                if (idx + 2 > d.length) return null;
                rate = (((d[idx] & 0xFF) << 8) | (d[idx + 1] & 0xFF)) * 10L;
                idx += 2;
            }
            default -> {
                return null;
            }
        }
        if (idx >= d.length) {
            return null;
        }
        int frameChannels = chCode <= 7 ? chCode + 1 : 2;
        int bps = bpsCode == 0 ? streamBps : FLAC_BPS_TABLE[bpsCode];
        if (rate <= 0 || bps <= 0 || (streamRate > 0 && rate != streamRate)
                || (streamChannels > 0 && frameChannels != streamChannels)) {
            return null;
        }
        int crc = 0;
        for (int i = p; i < idx; i++) {
            crc = FLAC_CRC8_TABLE[(crc ^ (d[i] & 0xFF)) & 0xFF];
        }
        if ((d[idx] & 0xFF) != crc) {
            return null;
        }
        int bodyStart = idx + 1;
        int subframeCount = chCode <= 7 ? frameChannels : 2;
        BitReader br = new BitReader(d, bodyStart, d.length);
        for (int ch = 0; ch < subframeCount; ch++) {
            int subBps = bps;
            // 立体声解相关模式下，side 声道多存 1 个 bit
            if ((chCode == 8 && ch == 1) || (chCode == 9 && ch == 0) || (chCode == 10 && ch == 1)) {
                subBps = bps + 1;
            }
            if (!decodeFlacSubframe(br, subBps, blockSize)) {
                return null;
            }
        }
        br.alignToByte();
        if (!br.hasBits(16)) {
            return null;
        }
        int footerStart = br.byteOffset();
        int crc16 = 0;
        for (int i = p; i < footerStart; i++) {
            crc16 = ((crc16 << 8) ^ FLAC_CRC16_TABLE[((crc16 >>> 8) ^ (d[i] & 0xFF)) & 0xFF]) & 0xFFFF;
        }
        int gotCrc16 = ((d[footerStart] & 0xFF) << 8) | (d[footerStart + 1] & 0xFF);
        if (gotCrc16 != crc16) {
            return null;
        }
        int frameLen = footerStart + 2 - p;
        return new FlacFrame(blockSize / (double) rate, blockSize, frameNumber, (d[p + 1] & 1) != 0, frameLen);
    }

    /** 解码单个子帧的完整位流（不还原样本值，仅用于精确定位子帧结束位置），结构非法返回 false */
    private static boolean decodeFlacSubframe(BitReader br, int bps, long blockSize) {
        if (!br.hasBits(8) || bps <= 0 || bps > 32) {
            return false;
        }
        if (br.readBits(1) != 0) {
            return false; // 填充位必须为 0
        }
        int type = (int) br.readBits(6);
        int wasted = 0;
        if (br.readBits(1) == 1) {
            int u = br.readUnary();
            if (u < 0) {
                return false;
            }
            wasted = u + 1;
        }
        int effBps = bps - wasted;
        if (effBps <= 0 || effBps > 32) {
            return false;
        }
        if (type == 0) { // CONSTANT
            return br.skipBits(effBps);
        }
        if (type == 1) { // VERBATIM
            return br.skipBits((long) effBps * blockSize);
        }
        if (type >= 8 && type <= 12) { // FIXED，阶数 0-4
            int order = type - 8;
            if (order > blockSize) {
                return false;
            }
            return br.skipBits((long) order * effBps) && decodeFlacResidual(br, order, blockSize);
        }
        if ((type & 0x20) != 0) { // LPC，阶数 1-32
            int order = (type & 0x1F) + 1;
            if (order > blockSize || !br.skipBits((long) order * effBps) || !br.hasBits(4 + 5)) {
                return false;
            }
            int precisionField = (int) br.readBits(4);
            if (precisionField == 0xF) {
                return false; // 保留值
            }
            int precision = precisionField + 1;
            br.readBits(5); // qlp shift（有符号，具体数值不影响帧边界定位）
            return br.skipBits((long) order * precision) && decodeFlacResidual(br, order, blockSize);
        }
        return false; // 保留类型
    }

    /** 解码 FIXED/LPC 子帧的残差编码部分（Rice 编码），仅消费比特数以定位子帧结束位置 */
    private static boolean decodeFlacResidual(BitReader br, int predictorOrder, long blockSize) {
        if (!br.hasBits(2 + 4)) {
            return false;
        }
        int method = (int) br.readBits(2);
        if (method > 1) {
            return false; // 保留编码方式
        }
        int paramBits = method == 0 ? 4 : 5;
        int partitionOrder = (int) br.readBits(4);
        long partitions = 1L << partitionOrder;
        if (partitions == 0 || blockSize % partitions != 0) {
            return false;
        }
        long samplesPerPartition = blockSize / partitions;
        if (samplesPerPartition <= predictorOrder) {
            return false;
        }
        int escapeVal = method == 0 ? 0xF : 0x1F;
        for (long part = 0; part < partitions; part++) {
            long partitionSamples = part == 0 ? samplesPerPartition - predictorOrder : samplesPerPartition;
            if (!br.hasBits(paramBits)) {
                return false;
            }
            int riceParam = (int) br.readBits(paramBits);
            if (riceParam == escapeVal) {
                if (!br.hasBits(5)) {
                    return false;
                }
                int rawBits = (int) br.readBits(5);
                if (!br.skipBits((long) rawBits * partitionSamples)) {
                    return false;
                }
            } else {
                for (long s = 0; s < partitionSamples; s++) {
                    if (br.readUnary() < 0 || !br.skipBits(riceParam)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** 从字节流中读取比特位的辅助类（MSB 优先），越界读取一律返回失败而非抛异常 */
    private static final class BitReader {
        private final byte[] d;
        private long bitPos;
        private final long endBit;

        BitReader(byte[] d, int startByte, int endByteExclusive) {
            this.d = d;
            this.bitPos = startByte * 8L;
            this.endBit = endByteExclusive * 8L;
        }

        boolean hasBits(long n) {
            return n >= 0 && bitPos + n <= endBit;
        }

        long readBits(int n) {
            long v = 0;
            for (int i = 0; i < n; i++) {
                int byteIdx = (int) (bitPos >>> 3);
                int bitIdx = 7 - (int) (bitPos & 7);
                v = (v << 1) | ((d[byteIdx] >>> bitIdx) & 1);
                bitPos++;
            }
            return v;
        }

        boolean skipBits(long n) {
            if (!hasBits(n)) {
                return false;
            }
            bitPos += n;
            return true;
        }

        /** 读取一元编码（0 的个数，以 1 结尾），越界返回 -1 */
        int readUnary() {
            int count = 0;
            while (true) {
                if (!hasBits(1)) {
                    return -1;
                }
                if (readBits(1) == 1) {
                    return count;
                }
                count++;
            }
        }

        void alignToByte() {
            bitPos = (bitPos + 7) & ~7L;
        }

        int byteOffset() {
            return (int) (bitPos >>> 3);
        }
    }

    private record FlacFrame(double seconds, long blockSize, long number, boolean variableBlock, int length) {}

    // ==================== OGG (Opus) ====================

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
     * 顺序遍历所有页并逐页校验 CRC-32（页必须首尾相接铺满整个文件），重建 Opus 分组
     * （packet，跨页续传由 lacing 表与 continuation 标志共同校验）边界；不信任可被
     * 任意篡改且能重新配平 CRC 的 granule 位置，改为按每个分组 TOC 字节推导的真实
     * Opus 帧时长累计；同时要求首页 BOS、末页 EOS、页序号严格连续。
     * Ogg/Vorbis 缺乏免解码即可信的时长推导方式，一律判定为不可测（-1）。
     */
    private static double oggSeconds(byte[] d) {
        long audioSerial = 0;
        boolean serialFound = false;
        boolean sawEos = false;
        long expectedPageSeq = -1;
        int pos = 0;
        java.io.ByteArrayOutputStream packetBuf = new java.io.ByteArrayOutputStream();
        boolean packetPending = false;
        double totalMs = 0;
        long packetCount = 0;
        while (pos < d.length) {
            if (pos + 27 > d.length || !match(d, pos, "OggS") || d[pos + 4] != 0) {
                return -1;
            }
            int headerType = d[pos + 5] & 0xFF;
            long pageSeq = u32le(d, pos + 18);
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
            long serial = u32le(d, pos + 14);
            int body = pos + headerLen;
            if (!serialFound && bodyLen >= 8 && match(d, body, "OpusHead")) {
                audioSerial = serial;
                serialFound = true;
            }
            if (serialFound && serial == audioSerial) {
                if (expectedPageSeq == -1) {
                    if ((headerType & 0x02) == 0) {
                        return -1; // 音频流首页必须标记 BOS
                    }
                } else if (pageSeq != expectedPageSeq) {
                    return -1; // 页序号必须严格连续，防止乱序/重放/丢页
                }
                expectedPageSeq = pageSeq + 1;
                if (sawEos) {
                    return -1; // EOS 之后不应再出现该流的页
                }
                boolean continued = (headerType & 0x01) != 0;
                if (continued != packetPending) {
                    return -1; // continuation 标志必须与实际未终止的分组状态一致
                }
                if ((headerType & 0x04) != 0) {
                    sawEos = true;
                }
                int segPos = pos + 27;
                int off = body;
                for (int i = 0; i < segCount; i++) {
                    int segLen = d[segPos + i] & 0xFF;
                    packetBuf.write(d, off, segLen);
                    off += segLen;
                    if (segLen < 255) {
                        byte[] packet = packetBuf.toByteArray();
                        packetBuf.reset();
                        packetPending = false;
                        // 前两个分组固定为 OpusHead / OpusTags 元数据分组，之后才是音频分组
                        if (packetCount >= 2 && packet.length > 0) {
                            double ms = opusPacketDurationMs(packet);
                            if (ms < 0) {
                                return -1;
                            }
                            totalMs += ms;
                        }
                        packetCount++;
                    } else {
                        packetPending = true;
                    }
                }
            }
            pos += pageLen;
        }
        if (packetPending) {
            return -1; // 文件结束但存在未终止的分组，结构不完整
        }
        return serialFound && sawEos && totalMs > 0 ? totalMs / 1000.0 : -1;
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

    /** Opus 单个分组（packet）的音频时长（毫秒），仅依据 TOC 字节推导，不解码音频样本；结构非法返回 -1 */
    private static double opusPacketDurationMs(byte[] packet) {
        int toc = packet[0] & 0xFF;
        int config = (toc >> 3) & 0x1F;
        int frameCountCode = toc & 0x3;
        double frameMs = opusConfigFrameMs(config);
        int frames;
        if (frameCountCode == 0) {
            frames = 1;
        } else if (frameCountCode == 1 || frameCountCode == 2) {
            frames = 2;
        } else {
            if (packet.length < 2) {
                return -1;
            }
            frames = packet[1] & 0x3F;
            if (frames == 0) {
                return -1;
            }
        }
        double totalMs = frameMs * frames;
        if (totalMs <= 0 || totalMs > 120.0 + 1e-6) {
            return -1; // 单个 Opus 分组最长承载 120ms 音频
        }
        return totalMs;
    }

    /** Opus TOC config（0-31）到单帧时长（毫秒）的映射，纯由编码规范决定，与实际样本内容无关 */
    private static double opusConfigFrameMs(int config) {
        if (config <= 11) { // SILK-only（NB/MB/WB），每 4 个 config 循环 10/20/40/60ms
            int[] silk = {10, 20, 40, 60};
            return silk[config % 4];
        } else if (config <= 15) { // Hybrid（SWB/FB），每 2 个 config 循环 10/20ms
            int[] hybrid = {10, 20};
            return hybrid[(config - 12) % 2];
        } else { // CELT-only（NB/WB/SWB/FB），每 4 个 config 循环 2.5/5/10/20ms
            double[] celt = {2.5, 5, 10, 20};
            return celt[(config - 16) % 4];
        }
    }

    // ==================== MP4 / M4A ====================

    /**
     * 不使用 mvhd 影片头时长（可伪造）：按音频 trak 的 stts 样本表实测，
     * 顶层 box 必须铺满文件，样本表内部一致，且通过 stsc + stco/co64 换算出的
     * 每个 chunk 字节区间必须完整落在文件中真实存在的 mdat box 范围内
     */
    private static double mp4Seconds(byte[] d) {
        java.util.List<long[]> mdatRanges = new java.util.ArrayList<>();
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
                mdatRanges.add(new long[]{pos + header, pos + size});
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
                double r = trakAudioSeconds(d, box[0], box[1], mdatRanges);
                if (r > 0) {
                    return r;
                }
            }
            p = box[1];
        }
        return -1;
    }

    private static double trakAudioSeconds(byte[] d, int start, int end, java.util.List<long[]> mdatRanges) {
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
        if (uniformSize == 0 && stsz[0] + 12 + sampleCount * 4 > stsz[1]) {
            return -1;
        }
        // 样本表必须自洽
        if (sampleCount <= 0 || sttsSamples != sampleCount) {
            return -1;
        }
        // 通过 stsc + stco/co64 换算每个 chunk 的真实字节区间，要求完整落在文件中
        // 真实存在的 mdat box 范围内，证明样本数据确有其字节支撑，而不仅仅是元数据自洽
        int[] stsc = findBox(d, stbl[0], stbl[1], "stsc");
        int[] stco = findBox(d, stbl[0], stbl[1], "stco");
        int[] co64 = stco == null ? findBox(d, stbl[0], stbl[1], "co64") : null;
        if (stsc == null || (stco == null && co64 == null)) {
            return -1;
        }
        if (!verifyMp4SampleBytesInMdat(d, stsc, stco, co64, uniformSize, stsz[0], sampleCount, mdatRanges)) {
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

    /**
     * 展开 stsc（sample-to-chunk）与 stco/co64（chunk 偏移表），逐 chunk 累加其覆盖的
     * 样本字节数（来自 stsz），要求 [chunkOffset, chunkOffset+chunkBytes) 完整落在
     * 某个真实 mdat box 的字节范围内；并要求恰好消费全部 sampleCount 个样本
     */
    private static boolean verifyMp4SampleBytesInMdat(byte[] d, int[] stsc, int[] stco, int[] co64,
                                                       long uniformSize, int stszStart, long sampleCount,
                                                       java.util.List<long[]> mdatRanges) {
        if (stsc[0] + 8 > stsc[1]) {
            return false;
        }
        long stscEntries = u32be(d, stsc[0] + 4);
        if (stscEntries <= 0 || stsc[0] + 8 + stscEntries * 12 > stsc[1]) {
            return false;
        }
        int[] offsetBox = stco != null ? stco : co64;
        int entrySize = stco != null ? 4 : 8;
        if (offsetBox[0] + 8 > offsetBox[1]) {
            return false;
        }
        long chunkCount = u32be(d, offsetBox[0] + 4);
        if (chunkCount <= 0 || offsetBox[0] + 8 + chunkCount * entrySize > offsetBox[1]) {
            return false;
        }
        boolean uniform = uniformSize != 0;
        long sampleIdx = 0;
        for (long e = 0; e < stscEntries; e++) {
            int entryPos = (int) (stsc[0] + 8 + e * 12);
            long firstChunk = u32be(d, entryPos);
            long samplesPerChunk = u32be(d, entryPos + 4);
            if (firstChunk <= 0 || samplesPerChunk <= 0) {
                return false;
            }
            long nextFirstChunk = e + 1 < stscEntries ? u32be(d, entryPos + 12) : chunkCount + 1;
            if (nextFirstChunk <= firstChunk) {
                return false;
            }
            for (long chunk = firstChunk; chunk < nextFirstChunk; chunk++) {
                if (chunk > chunkCount) {
                    return false;
                }
                long chunkOffsetPos = offsetBox[0] + 8 + (chunk - 1) * entrySize;
                long chunkOffset = entrySize == 4 ? u32be(d, (int) chunkOffsetPos) : u64be(d, (int) chunkOffsetPos);
                long chunkBytes = 0;
                for (long s = 0; s < samplesPerChunk; s++) {
                    if (sampleIdx >= sampleCount) {
                        return false;
                    }
                    chunkBytes += uniform ? uniformSize : u32be(d, (int) (stszStart + 12 + sampleIdx * 4));
                    sampleIdx++;
                }
                if (chunkOffset < 0 || chunkBytes < 0 || chunkOffset + chunkBytes > d.length) {
                    return false;
                }
                boolean withinMdat = false;
                for (long[] range : mdatRanges) {
                    if (chunkOffset >= range[0] && chunkOffset + chunkBytes <= range[1]) {
                        withinMdat = true;
                        break;
                    }
                }
                if (!withinMdat) {
                    return false;
                }
            }
        }
        return sampleIdx == sampleCount;
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
        int currentTrackType = -1;
        boolean hasAudioTrack = false;
    }

    /**
     * 不信任 Duration 元数据元素：严格遍历 EBML 树按块时间戳实测最大时间点；
     * 任何结构断裂（未知长度元素、越界、非法 box）都判为不可测，不采信断裂前
     * 已收集的部分时间戳；同时要求存在音频 Track
     */
    private static double webmSeconds(byte[] d) {
        WebmState st = new WebmState();
        if (!webmWalk(d, 0, d.length, st, 0)) {
            return -1;
        }
        return st.hasAudioTrack && st.maxTicks > 0 ? st.maxTicks * st.timecodeScale / 1e9 : -1;
    }

    private static boolean webmWalk(byte[] d, int start, int end, WebmState st, int depth) {
        if (depth > 6) {
            return false;
        }
        int pos = start;
        while (pos < end) {
            long[] idr = ebmlRead(d, pos, end, false);
            if (idr == null) {
                return false;
            }
            long id = idr[0];
            pos = (int) idr[1];
            long[] szr = ebmlRead(d, pos, end, true);
            if (szr == null) {
                return false;
            }
            long size = szr[0];
            pos = (int) szr[1];
            if (size < 0) {
                return false; // 不支持未知长度元素，避免静默吞掉后续结构
            }
            long elEndL = pos + size;
            if (elEndL > end) {
                return false;
            }
            int elEnd = (int) elEndL;
            if (id == 0x18538067L || id == 0x1549A966L || id == 0x1654AE6BL) { // Segment / Info / Tracks
                if (!webmWalk(d, pos, elEnd, st, depth + 1)) {
                    return false;
                }
            } else if (id == 0xAEL) { // TrackEntry
                st.currentTrackType = -1;
                if (!webmWalk(d, pos, elEnd, st, depth + 1)) {
                    return false;
                }
                if (st.currentTrackType == 2) {
                    st.hasAudioTrack = true;
                }
            } else if (id == 0x83L && size > 0 && size <= 8) { // TrackType
                st.currentTrackType = (int) readUintBE(d, pos, (int) size);
            } else if (id == 0x2AD7B1L && size > 0 && size <= 8) { // TimecodeScale
                long v = readUintBE(d, pos, (int) size);
                if (v > 0) {
                    st.timecodeScale = v;
                }
            } else if (id == 0x1F43B675L) { // Cluster
                st.clusterTime = -1;
                if (!webmWalk(d, pos, elEnd, st, depth + 1)) {
                    return false;
                }
            } else if (id == 0xE7L && size > 0 && size <= 8) { // Cluster Timecode
                st.clusterTime = readUintBE(d, pos, (int) size);
            } else if (id == 0xA0L) { // BlockGroup
                if (!webmWalk(d, pos, elEnd, st, depth + 1)) {
                    return false;
                }
            } else if ((id == 0xA3L || id == 0xA1L) && st.clusterTime >= 0) { // SimpleBlock / Block
                long[] track = ebmlRead(d, pos, elEnd, true);
                if (track == null) {
                    return false;
                }
                int p2 = (int) track[1];
                if (p2 + 2 > elEnd) {
                    return false;
                }
                int rel = (short) (((d[p2] & 0xFF) << 8) | (d[p2 + 1] & 0xFF));
                st.maxTicks = Math.max(st.maxTicks, st.clusterTime + rel);
            }
            pos = elEnd;
        }
        return pos == end;
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

    /**
     * MP3 帧序列必须从 off（跳过 ID3 后）开始逐帧相接、连续铺满至文件末尾；
     * 不允许逐字节跳过垃圾数据去寻找可解析的帧起点，也不允许中途中断后仍接受已累计的部分时长
     */
    private static double mp3Seconds(byte[] d, int off) {
        int pos = Math.max(off, 0);
        if (pos + 4 > d.length) {
            return -1;
        }
        Mp3Frame first = parseMp3Frame(d, pos);
        if (first == null) {
            return -1;
        }
        double seconds = 0;
        int frames = 0;
        int framePos = pos;
        while (framePos < d.length) {
            Mp3Frame frame = parseMp3Frame(d, framePos);
            if (frame == null || frame.versionBits() != first.versionBits()
                    || frame.sampleRate() != first.sampleRate() || frame.channels() != first.channels()) {
                return -1;
            }
            seconds += frame.samplesPerFrame() / (double) frame.sampleRate();
            frames++;
            framePos += frame.length();
        }
        return frames >= 3 && framePos == d.length ? seconds : -1;
    }

    private static Mp3Frame parseMp3Frame(byte[] d, int pos) {
        if (pos + 4 > d.length || (d[pos] & 0xFF) != 0xFF || (d[pos + 1] & 0xE0) != 0xE0) {
            return null;
        }
        int b1 = d[pos + 1] & 0xFF;
        int b2 = d[pos + 2] & 0xFF;
        int versionBits = (b1 >> 3) & 3; // 0=MPEG2.5, 2=MPEG2, 3=MPEG1
        int layerBits = (b1 >> 1) & 3;   // 1=Layer III
        int bitrateIdx = (b2 >> 4) & 0xF;
        int rateIdx = (b2 >> 2) & 3;
        int padding = (b2 >> 1) & 1;
        if (versionBits == 1 || layerBits != 1 || bitrateIdx == 0 || bitrateIdx == 15 || rateIdx == 3) {
            return null;
        }
        boolean mpeg1 = versionBits == 3;
        int sampleRate = MP3_SAMPLE_RATES[mpeg1 ? 0 : (versionBits == 2 ? 1 : 2)][rateIdx];
        int bitrate = L3_BITRATES[mpeg1 ? 0 : 1][bitrateIdx] * 1000;
        int samplesPerFrame = mpeg1 ? 1152 : 576;
        int frameLen = samplesPerFrame / 8 * bitrate / sampleRate + padding;
        if (frameLen <= 4 || frameLen > d.length - pos) {
            return null;
        }
        int channels = ((d[pos + 3] >> 6) & 3) == 3 ? 1 : 2;
        return new Mp3Frame(frameLen, sampleRate, samplesPerFrame, channels, versionBits);
    }

    private record Mp3Frame(int length, int sampleRate, int samplesPerFrame, int channels, int versionBits) {}

    // ==================== ADTS AAC ====================

    private static final int[] AAC_RATES = {
            96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350
    };

    /**
     * ADTS 帧序列必须从 off 开始逐帧相接、连续铺满至文件末尾，规则与 MP3 一致，
     * 不允许逐字节跳过垃圾数据寻找可解析帧
     */
    private static double adtsSeconds(byte[] d, int off) {
        int pos = Math.max(off, 0);
        if (pos + 7 > d.length) {
            return -1;
        }
        AdtsFrame first = parseAdtsFrame(d, pos);
        if (first == null) {
            return -1;
        }
        double seconds = 0;
        int frames = 0;
        int framePos = pos;
        while (framePos < d.length) {
            AdtsFrame frame = parseAdtsFrame(d, framePos);
            if (frame == null || frame.sampleRate() != first.sampleRate()
                    || frame.channels() != first.channels()) {
                return -1;
            }
            seconds += frame.rawBlocks() * 1024.0 / frame.sampleRate();
            frames++;
            framePos += frame.length();
        }
        return frames >= 2 && framePos == d.length ? seconds : -1;
    }

    private static AdtsFrame parseAdtsFrame(byte[] d, int pos) {
        if (pos + 7 > d.length || (d[pos] & 0xFF) != 0xFF || (d[pos + 1] & 0xF6) != 0xF0) {
            return null;
        }
        int rateIdx = (d[pos + 2] >> 2) & 0xF;
        int frameLen = ((d[pos + 3] & 3) << 11) | ((d[pos + 4] & 0xFF) << 3) | ((d[pos + 5] & 0xFF) >> 5);
        int headerLen = (d[pos + 1] & 1) == 0 ? 9 : 7;
        if (rateIdx >= AAC_RATES.length || frameLen < headerLen || frameLen > d.length - pos) {
            return null;
        }
        int rawBlocks = (d[pos + 6] & 3) + 1;
        int channels = ((d[pos + 2] & 1) << 2) | ((d[pos + 3] >> 6) & 3);
        if (channels == 0) {
            return null;
        }
        return new AdtsFrame(frameLen, AAC_RATES[rateIdx], rawBlocks, channels);
    }

    private record AdtsFrame(int length, int sampleRate, int rawBlocks, int channels) {}

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

    private static long readUintBE(byte[] d, int pos, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (d[pos + i] & 0xFF);
        }
        return v;
    }
}
