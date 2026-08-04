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

    private byte[] buildOggPage(long granule, int serial, int seq, byte[] body, boolean corruptCrc) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "OggS");
        out.write(0); // version
        out.write(seq == 0 ? 2 : 0); // header type
        for (int i = 0; i < 8; i++) out.write((int) (granule >>> (8 * i)) & 0xFF);
        writeU32le(out, serial);
        writeU32le(out, seq);
        writeU32le(out, 0); // CRC 占位
        int segments = (body.length / 255) + 1;
        out.write(segments);
        int rest = body.length;
        for (int i = 0; i < segments; i++) {
            out.write(Math.min(rest, 255));
            rest -= 255;
        }
        out.write(body, 0, body.length);
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

    @Test
    void oggMeasuresFromValidPages() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPage(0, 7, 0, opusHeadBody(), false);
        byte[] p1 = buildOggPage(48000L * 10, 7, 1, new byte[100], false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        double s = AudioDurationUtil.measure(out.toByteArray());
        assertEquals(10.0, s, 0.01);
    }

    @Test
    void oggRejectsAppendedForgedLowGranulePage() {
        // 追加一个 granule=1 的伪造尾页（CRC 正确），granule 非单调 → 拒绝
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPage(0, 7, 0, opusHeadBody(), false);
        byte[] p1 = buildOggPage(48000L * 600, 7, 1, new byte[100], false);
        byte[] forged = buildOggPage(1, 7, 2, new byte[10], false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        out.write(forged, 0, forged.length);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    @Test
    void oggRejectsBadPageCrc() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPage(0, 7, 0, opusHeadBody(), false);
        byte[] p1 = buildOggPage(48000L * 10, 7, 1, new byte[100], true);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        assertEquals(-1, AudioDurationUtil.measure(out.toByteArray()));
    }

    @Test
    void oggRejectsTrailingGarbage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] p0 = buildOggPage(0, 7, 0, opusHeadBody(), false);
        byte[] p1 = buildOggPage(48000L * 10, 7, 1, new byte[100], false);
        out.write(p0, 0, p0.length);
        out.write(p1, 0, p1.length);
        out.write(new byte[]{1, 2, 3, 4}, 0, 4);
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

    /** 构造 fLaC + STREAMINFO（声明 claimedSamples）+ frameCount 个带合法 CRC-8 帧头的帧 */
    private byte[] buildFlac(long claimedSamples, int frameCount) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "fLaC");
        // STREAMINFO 块头：last=1, type=0, len=34
        out.write(0x80);
        out.write(0);
        out.write(0);
        out.write(34);
        byte[] info = new byte[34];
        // 采样率 44100 (20bit) + 声道 2-1 (3bit) + 位深 16-1 (5bit) + 总样本数 (36bit)，位于偏移 10 起 8 字节
        long v = (44100L << 44) | (1L << 41) | (15L << 36) | (claimedSamples & 0xFFFFFFFFFL);
        for (int i = 0; i < 8; i++) {
            info[10 + i] = (byte) (v >>> (56 - 8 * i));
        }
        out.write(info, 0, info.length);
        for (int f = 0; f < frameCount; f++) {
            // 帧头: FF F8, blockSize code 12 (4096), rate code 9 (44100), 立体声 code 1, 16bit code 4
            byte[] hdr = {(byte) 0xFF, (byte) 0xF8, (byte) 0xC9, (byte) 0x18, (byte) f, 0};
            hdr[5] = (byte) flacCrc8(hdr, 0, 5);
            out.write(hdr, 0, hdr.length);
            out.write(new byte[64], 0, 64); // 伪帧体（扫描按同步字查找下一帧）
        }
        return out.toByteArray();
    }

    @Test
    void flacBillsByActualFramesNotStreaminfoClaim() {
        // STREAMINFO 声称仅 1 个样本，但实际有 20 帧 × 4096 样本 ≈ 1.86 秒 → 按实测帧计
        double s = AudioDurationUtil.measure(buildFlac(1, 20));
        assertEquals(20 * 4096 / 44100.0, s, 0.05);
    }

    @Test
    void flacWithNoValidFramesRejected() {
        assertEquals(-1, AudioDurationUtil.measure(buildFlac(44100L * 3600, 0)));
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
