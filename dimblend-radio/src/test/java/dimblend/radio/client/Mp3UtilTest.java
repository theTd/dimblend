package dimblend.radio.client;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MP3 时长逐帧累加：内嵌大封面（ID3v2）与尾部 ID3v1 标签都不能把时长撑大。
 *
 * <p>夹具 {@code tone_2s_32k.mp3}：ffmpeg 生成的 2 秒 440Hz 单声道 32kbps，78 帧 ≈ 2.04s。
 * 旧估算“文件字节 × 8 / 码率”遇到 2MB 封面会算成 ~526s。</p>
 */
class Mp3UtilTest {
    private static final double ONE_FRAME = 1152 / 44100.0;
    private static final double EXPECTED_SECONDS = 78 * ONE_FRAME;

    @Test
    void plainFileDurationMatchesFrameCount() throws Exception {
        assertEquals(EXPECTED_SECONDS, Mp3Util.durationSeconds(tone()), 0.01);
    }

    @Test
    void largeCoverArtTagDoesNotInflateDuration() throws Exception {
        byte[] tagged = concat(id3v2Tag(2 << 20), tone());
        assertEquals(EXPECTED_SECONDS, Mp3Util.durationSeconds(tagged), 0.01);
    }

    @Test
    void trailingId3v1TagIsIgnored() throws Exception {
        byte[] trailer = new byte[128];
        trailer[0] = 'T';
        trailer[1] = 'A';
        trailer[2] = 'G';
        // 尾随标签时 JLayer 读帧会丢最后一帧（解码同源，时长与实际可播 PCM 一致）：允许差 1 帧，不许撑大
        assertEquals(EXPECTED_SECONDS, Mp3Util.durationSeconds(concat(tone(), trailer)), ONE_FRAME + 0.001);
    }

    private static byte[] tone() throws Exception {
        try (InputStream in = Mp3UtilTest.class.getResourceAsStream("tone_2s_32k.mp3")) {
            assertNotNull(in, "fixture tone_2s_32k.mp3 missing");
            return in.readAllBytes();
        }
    }

    /** ID3v2.3 标签：10 字节头（大小为 syncsafe 编码）+ 全零填充体，模拟内嵌封面。 */
    private static byte[] id3v2Tag(int bodySize) {
        byte[] tag = new byte[10 + bodySize];
        tag[0] = 'I';
        tag[1] = 'D';
        tag[2] = '3';
        tag[3] = 3;
        tag[6] = (byte) ((bodySize >> 21) & 0x7F);
        tag[7] = (byte) ((bodySize >> 14) & 0x7F);
        tag[8] = (byte) ((bodySize >> 7) & 0x7F);
        tag[9] = (byte) (bodySize & 0x7F);
        return tag;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
