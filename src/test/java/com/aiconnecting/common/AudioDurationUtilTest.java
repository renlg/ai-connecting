package com.aiconnecting.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证时长测量基于实际数据实测，且拒绝伪造的头部时长元数据
 */
class AudioDurationUtilTest {

    // ==================== WAV ====================

    private byte[] buildWav(int sampleRate, int channels, int bitsPerSample, int dataBytes, Integer forgedByteRate) {
        int blockAlign = channels * bitsPerSample / 8;
        int byteRate = forgedByteRate != null ? forgedByteRate : sampleRate * blockAlign;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "RIFF");
        writeU32le(out, 36 + dataBytes);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeU32le(out, 16);
        writeU16le(out, 1);
        writeU16le(out, channels);
        writeU32le(out, sampleRate);
        writeU32le(out, byteRate);
        writeU16le(out, blockAlign);
        writeU16le(out, bitsPerSample);
        writeAscii(out, "data");
        writeU32le(out, dataBytes);
        out.write(new byte[dataBytes], 0, dataBytes);
        return out.toByteArray();
    }

    @Test
    void wavMeasuresFromActualDataBytes() {
        // 3 秒 16kHz 16bit 单声道 = 96000 字节
        double s = AudioDurationUtil.measure(buildWav(16000, 1, 16, 96000, null));
        assertEquals(3.0, s, 0.01);
    }

    @Test
    void wavRejectsInconsistentByteRate() {
        // 伪造 byteRate 与 sampleRate×blockAlign 不一致（试图把长音频按短时长计费）
        double s = AudioDurationUtil.measure(buildWav(16000, 1, 16, 96000, 32000000));
        assertEquals(-1, s);
    }

    @Test
    void wavIgnoresDataSizeClaimBeyondFile() {
        // data 块声明超过文件实际长度：只按实际存在的字节计
        byte[] wav = buildWav(16000, 1, 16, 32000, null);
        // 将 data 声明改大 10 倍
        int dataSizeOff = wav.length - 32000 - 4;
        wav[dataSizeOff] = (byte) 0x00;
        wav[dataSizeOff + 1] = (byte) 0xE1;
        wav[dataSizeOff + 2] = (byte) 0x04;
        wav[dataSizeOff + 3] = 0;
        double s = AudioDurationUtil.measure(wav);
        assertEquals(1.0, s, 0.01);
    }

    // ==================== OGG ====================

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;
            for (int j = 0; j < 8; j++) {
                r = (r & 0x80000000) != 0 ? (r << 1) ^ 0x04C11DB7 : r << 1;
            }
            CRC_TABLE[i] = r;
        }
    }

    /** 构造一个 Ogg 页，body 由若干个分组（packet）按标准 lacing 规则切分打包 */
    private byte[] buildOggPageMulti(long granule, int serial, int seq, int headerTypeFlags,
                                     java.util.List<byte[]> packets, boolean corruptCrc) {
        ByteArrayOutputStream segTable = new ByteArrayOutputStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] packet : packets) {
            int remaining = packet.length;
            while (remaining >= 255) {
                segTable.write(255);
                body.write(packet, packet.length - remaining, 255);
                remaining -= 255;
            }
            segTable.write(remaining);
            body.write(packet, packet.length - remaining, remaining);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "OggS");
        out.write(0); // version
        out.write(headerTypeFlags);
        for (int i = 0; i < 8; i++) out.write((int) (granule >>> (8 * i)) & 0xFF);
        writeU32le(out, serial);
        writeU32le(out, seq);
        writeU32le(out, 0); // CRC 占位
        byte[] segBytes = segTable.toByteArray();
        out.write(segBytes.length);
        out.write(segBytes, 0, segBytes.length);
        byte[] bodyBytes = body.toByteArray();
        out.write(bodyBytes, 0, bodyBytes.length);
        byte[] page = out.toByteArray();
        int crc = 0;
        for (byte b : page) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ (b & 0xFF)) & 0xFF];
        }
        page[22] = (byte) crc;
        page[23] = (byte) (crc >> 8);
        page[24] = (byte) (crc >> 16);
        page[25] = (byte) (crc >> 24);
        if (corruptCrc) {
            page[22] ^= 0x55;
        }
        return page;
    }

    private byte[] opusHeadBody() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "OpusHead");
        out.write(1); // version
        out.write(1); // channels
        writeU16le(out, 312); // pre-skip
        writeU32le(out, 48000);
        writeU16le(out, 0);
        out.write(0);
        return out.toByteArray();
    }

    private byte[] opusTagsBody() {
        return new byte[]{'O', 'p', 'u', 's', 'T', 'a', 'g', 's'};
    }

    /** 单帧 Opus 分组：TOC config=11（SILK WB，60ms/帧），frameCountCode=0（单帧） */
    private static final byte[] OPUS_60MS_PACKET = {(byte) ((11 << 3))};

    private java.util.List<byte[]> audioPackets(int count) {
        java.util.List<byte[]> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(OPUS_60MS_PACKET.clone());
        }
        return list;
    }

    @Test
    void oggMeasuresFromRealOpusPacketDurations() {
        // 头页 (BOS)：OpusHead + OpusTags；音频页 (EOS)：50 个 60ms 分组 = 3.0 秒
        // granule 故意留空/无意义，验证时长完全由分组 TOC 推导而非 granule
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPageMulti(0, 7, 0, 0x02, java.util.List.of(opusHeadBody(), opusTagsBody()), false);
        byte[] p1 = buildOggPageMulti(0, 7, 1, 0x04, audioPackets(50), false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        assertEquals(3.0, AudioDurationUtil.measure(out.toByteArray()), 0.001);
    }

    @Test
    void oggIgnoresForgedGranuleAndUsesPacketDuration() {
        // granule 被篡改为一个巨大的值（CRC 重新配平后依然合法），但实际只有 5 个 60ms 分组
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPageMulti(0, 7, 0, 0x02, java.util.List.of(opusHeadBody(), opusTagsBody()), false);
        byte[] p1 = buildOggPageMulti(48000L * 3600, 7, 1, 0x04, audioPackets(5), false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        assertEquals(5 * 0.06, AudioDurationUtil.measure(out.toByteArray()), 0.001);
    }

    @Test
    void oggRejectsBadPageCrc() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPageMulti(0, 7, 0, 0x02, java.util.List.of(opusHeadBody(), opusTagsBody()), false);
        byte[] p1 = buildOggPageMulti(0, 7, 1, 0x04, audioPackets(10), true);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    @Test
    void oggRejectsTrailingGarbage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPageMulti(0, 7, 0, 0x02, java.util.List.of(opusHeadBody(), opusTagsBody()), false);
        byte[] p1 = buildOggPageMulti(0, 7, 1, 0x04, audioPackets(10), false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        out.write(new byte[]{1, 2, 3, 4}, 0, 4);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    @Test
    void oggRejectsNonSequentialPageNumbers() {
        // 页序号跳跃（0 -> 2），防止乱序/丢页/重放
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPageMulti(0, 7, 0, 0x02, java.util.List.of(opusHeadBody(), opusTagsBody()), false);
        byte[] p1 = buildOggPageMulti(0, 7, 2, 0x04, audioPackets(10), false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    @Test
    void oggRejectsMissingEos() {
        // 末页缺少 EOS 标志，流结构不完整 → 拒绝
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPageMulti(0, 7, 0, 0x02, java.util.List.of(opusHeadBody(), opusTagsBody()), false);
        byte[] p1 = buildOggPageMulti(0, 7, 1, 0x00, audioPackets(10), false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    @Test
    void oggVorbisIsUnmeasurable() {
        // 缺乏免解码即可信的 Vorbis 时长推导方式，一律判定为不可测而非按 granule 计费
        ByteArrayOutputStream vorbisId = new ByteArrayOutputStream();
        vorbisId.write(1);
        writeAscii(vorbisId, "vorbis");
        vorbisId.write(new byte[23], 0, 23);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPageMulti(0, 7, 0, 0x02, java.util.List.of(vorbisId.toByteArray()), false);
        byte[] p1 = buildOggPageMulti(48000L * 10, 7, 1, 0x04, java.util.List.of(new byte[100]), false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    // ==================== FLAC ====================

    private static int flacCrc8(byte[] d, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            int b = d[i] & 0xFF;
            crc ^= b;
            for (int j = 0; j < 8; j++) {
                crc = (crc & 0x80) != 0 ? ((crc << 1) ^ 0x07) & 0xFF : (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    private static int flacCrc16(byte[] d, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            int b = d[i] & 0xFF;
            for (int bit = 7; bit >= 0; bit--) {
                int inputBit = (b >>> bit) & 1;
                int topBit = ((crc >>> 15) & 1) ^ inputBit;
                crc = (crc << 1) & 0xFFFF;
                if (topBit != 0) {
                    crc ^= 0x8005;
                }
            }
        }
        return crc & 0xFFFF;
    }

    private byte[] flacStreamInfo(long claimedSamples) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "fLaC");
        // STREAMINFO 块头：last=1, type=0, len=34
        out.write(0x80);
        out.write(0);
        out.write(0);
        out.write(34);
        byte[] info = new byte[34];
        // 采样率 44100 (20bit) + 声道 1-1=0 (3bit，单声道) + 位深 16-1 (5bit) + 总样本数 (36bit)，位于偏移 10 起 8 字节
        long v = (44100L << 44) | (15L << 36) | (claimedSamples & 0xFFFFFFFFFL);
        for (int i = 0; i < 8; i++) {
            info[10 + i] = (byte) (v >>> (56 - 8 * i));
        }
        out.write(info, 0, info.length);
        return out.toByteArray();
    }

    /**
     * 构造一个真实可完整解码的单声道 FLAC 帧：blockSize code 12 (4096)，rate code 9 (44100)，
     * 单声道 code 0，16bit code 4，唯一子帧为 CONSTANT(值 0)，帧体恰好 3 个全零字节 + 合法 CRC-16
     */
    private byte[] buildValidFlacFrame(int frameNumber) {
        byte[] hdr = {(byte) 0xFF, (byte) 0xF8, (byte) 0xC9, (byte) 0x08, (byte) frameNumber, 0};
        hdr[5] = (byte) flacCrc8(hdr, 0, 5);
        ByteArrayOutputStream frameNoCrc = new ByteArrayOutputStream();
        frameNoCrc.write(hdr, 0, hdr.length);
        frameNoCrc.write(new byte[]{0, 0, 0}, 0, 3); // CONSTANT 子帧：pad+type+wasted位 + 16bit 值，恰好 3 字节
        byte[] noCrc = frameNoCrc.toByteArray();
        int crc16 = flacCrc16(noCrc, 0, noCrc.length);
        ByteArrayOutputStream full = new ByteArrayOutputStream();
        full.write(noCrc, 0, noCrc.length);
        full.write((crc16 >> 8) & 0xFF);
        full.write(crc16 & 0xFF);
        return full.toByteArray();
    }

    /** 构造 fLaC + STREAMINFO + frameCount 个真实可解码的有效帧 */
    private byte[] buildFlac(long claimedSamples, int frameCount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] streamInfo = flacStreamInfo(claimedSamples);
        out.write(streamInfo, 0, streamInfo.length);
        for (int f = 0; f < frameCount; f++) {
            byte[] frame = buildValidFlacFrame(f);
            out.write(frame, 0, frame.length);
        }
        return out.toByteArray();
    }

    @Test
    void flacBillsByActualFramesNotStreaminfoClaim() {
        // STREAMINFO 声称仅 1 个样本，但实际有 20 个真实可解码帧 × 4096 样本 → 按实测帧计
        double s = AudioDurationUtil.measure(buildFlac(1, 20));
        assertEquals(20 * 4096 / 44100.0, s, 0.0001);
    }

    @Test
    void flacWithNoValidFramesRejected() {
        assertEquals(-1, AudioDurationUtil.measure(buildFlac(44100L * 3600, 0)));
    }

    @Test
    void flacRejectsNonSequentialFrames() {
        // 篡改第二帧的帧号后需同步重算 CRC-8 与覆盖整帧的 CRC-16，
        // 确保拒绝原因确实是"帧号不连续"而不仅是 CRC 校验失败
        byte[] streamInfo = flacStreamInfo(1);
        byte[] f0 = buildValidFlacFrame(0);
        byte[] f1 = buildValidFlacFrame(1);
        byte[] f2 = buildValidFlacFrame(2);
        f1[4] = 7; // 帧号改为 7，破坏连续性
        f1[5] = (byte) flacCrc8(f1, 0, 5);
        int crc16 = flacCrc16(f1, 0, f1.length - 2);
        f1[f1.length - 2] = (byte) ((crc16 >> 8) & 0xFF);
        f1[f1.length - 1] = (byte) (crc16 & 0xFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(streamInfo, 0, streamInfo.length);
        out.write(f0, 0, f0.length);
        out.write(f1, 0, f1.length);
        out.write(f2, 0, f2.length);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    @Test
    void flacRejectsFakeFrameBodyPastValidHeader() {
        // 复现旧漏洞的攻击样本：帧头 CRC-8 合法，但帧体是任意 64 字节全零垃圾数据
        // （不是真实编码的子帧），必须被拒绝而不是被当作有效帧部分计费
        byte[] streamInfo = flacStreamInfo(1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(streamInfo, 0, streamInfo.length);
        for (int f = 0; f < 20; f++) {
            byte[] hdr = {(byte) 0xFF, (byte) 0xF8, (byte) 0xC9, (byte) 0x08, (byte) f, 0};
            hdr[5] = (byte) flacCrc8(hdr, 0, 5);
            out.write(hdr, 0, hdr.length);
            out.write(new byte[64], 0, 64);
        }
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    // ==================== MP3 / ADTS ====================

    private byte[] buildMp3(int[] rateIndexes, int channels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] rates = {44100, 48000, 32000};
        for (int rateIndex : rateIndexes) {
            int frameLength = 144 * 128000 / rates[rateIndex];
            byte[] frame = new byte[frameLength];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB; // MPEG1 Layer III
            frame[2] = (byte) (0x90 | (rateIndex << 2)); // 128kbps
            frame[3] = (byte) (channels == 1 ? 0xC0 : 0);
            out.write(frame, 0, frame.length);
        }
        return out.toByteArray();
    }

    @Test
    void mp3MeasuresOnlyConsistentAdjacentFrames() {
        assertEquals(3 * 1152 / 44100.0,
                AudioDurationUtil.measure(buildMp3(new int[]{0, 0, 0}, 2)), 0.0001);
        assertEquals(-1, AudioDurationUtil.measure(buildMp3(new int[]{0, 1, 0}, 2)));
    }

    private byte[] buildAdts(int[] rateIndexes, int[] channels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int frameLength = 32;
        for (int i = 0; i < rateIndexes.length; i++) {
            int rateIndex = rateIndexes[i];
            int channelConfig = channels[i];
            byte[] frame = new byte[frameLength];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xF1;
            frame[2] = (byte) (0x40 | (rateIndex << 2) | (channelConfig >> 2));
            frame[3] = (byte) ((channelConfig & 3) << 6 | (frameLength >> 11));
            frame[4] = (byte) (frameLength >> 3);
            frame[5] = (byte) ((frameLength & 7) << 5 | 0x1F);
            frame[6] = (byte) 0xFC;
            out.write(frame, 0, frame.length);
        }
        return out.toByteArray();
    }

    @Test
    void adtsMeasuresOnlyConsistentAdjacentFrames() {
        assertEquals(3 * 1024 / 44100.0,
                AudioDurationUtil.measure(buildAdts(new int[]{4, 4, 4}, new int[]{2, 2, 2})), 0.0001);
        assertEquals(-1,
                AudioDurationUtil.measure(buildAdts(new int[]{4, 3, 4}, new int[]{2, 2, 2})));
        assertEquals(-1,
                AudioDurationUtil.measure(buildAdts(new int[]{4, 4, 4}, new int[]{2, 1, 2})));
    }

    // ==================== MP4 ====================

    @Test
    void mp4WithOnlyMvhdNoSampleTableRejected() {
        // 只有 mvhd 声明时长、没有可解码样本表的 MP4 → 拒绝，不按声明计费
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // ftyp
        writeU32be(out, 16);
        writeAscii(out, "ftyp");
        writeAscii(out, "M4A ");
        writeU32be(out, 0);
        // moov { mvhd }
        ByteArrayOutputStream mvhd = new ByteArrayOutputStream();
        writeU32be(mvhd, 8 + 100);
        writeAscii(mvhd, "mvhd");
        byte[] mvhdBody = new byte[100];
        // version 0: timescale @12, duration @16
        mvhdBody[12 + 2] = 0x03;
        mvhdBody[12 + 3] = (byte) 0xE8; // timescale 1000
        mvhdBody[16 + 3] = 0x01;        // duration 1 (声明 1ms)
        mvhd.write(mvhdBody, 0, mvhdBody.length);
        byte[] mvhdBox = mvhd.toByteArray();
        writeU32be(out, 8 + mvhdBox.length);
        writeAscii(out, "moov");
        out.write(mvhdBox, 0, mvhdBox.length);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    // ==================== PCM (speech 输出专用) ====================

    @Test
    void pcmSecondsFixedFormat() {
        assertEquals(2.0, AudioDurationUtil.pcmSeconds(new byte[96000]), 0.001);
        assertEquals(-1, AudioDurationUtil.pcmSeconds(new byte[0]));
    }

    @Test
    void unknownFormatRejected() {
        byte[] garbage = new byte[4096];
        for (int i = 0; i < garbage.length; i++) {
            garbage[i] = (byte) (i * 31);
        }
        assertTrue(AudioDurationUtil.measure(garbage) <= 0);
    }

    // ==================== 工具 ====================

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        for (char c : s.toCharArray()) {
            out.write(c);
        }
    }

    private static void writeU16le(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeU32le(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeU32be(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
