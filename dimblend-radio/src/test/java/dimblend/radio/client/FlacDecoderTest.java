package dimblend.radio.client;

import java.net.URL;
import java.nio.file.Path;

import javax.sound.sampled.AudioFormat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FLAC 解码链：STREAMINFO 时长、逐帧解码保留全声道、各位宽转 16bit、曲库下混单声道。
 *
 * <p>夹具均为 ffmpeg {@code sine=f=440}（幅度 1/8 → 16bit 峰值 ≈ 4096）：
 * 16bit 立体声 44.1k 2s、24bit 单声道 48k 1.5s、16bit 5.1 声道 22.05k 1s。
 * 峰值落在 4096 附近即说明采样位宽/字节序/声道交织都解对了。</p>
 */
class FlacDecoderTest {
    private static final String FIXTURES = """
            tone_2s_44k_stereo16.flac, 44100, 2, 2.0
            tone_1p5s_48k_mono24.flac, 48000, 1, 1.5
            tone_1s_22k_5p1.flac,      22050, 6, 1.0
            """;

    @ParameterizedTest
    @CsvSource(textBlock = FIXTURES)
    void headerDurationMatchesStreamInfo(String name, int rate, int channels, double seconds) throws Exception {
        assertEquals(seconds, FlacHeader.durationSeconds(fixture(name)), 1e-6);
    }

    @ParameterizedTest
    @CsvSource(textBlock = FIXTURES)
    void libraryDecodeIsMono16WithExactDuration(String name, int rate, int channels, double seconds)
            throws Exception {
        RadioLibrary.Pcm pcm = RadioLibrary.decode(fixture(name));
        assertMono16(pcm.format(), rate);
        assertEquals(seconds, pcm.seconds(), 1e-6);
        assertEquals(Math.round(seconds * rate) * 2, pcm.data().length);
        assertSinePeak(pcm.data(), 2, 0);
    }

    @ParameterizedTest
    @CsvSource(textBlock = FIXTURES)
    void decoderKeepsAllChannelsAs16Bit(String name, int rate, int channels, double seconds) throws Exception {
        assertFullChannelPcm(FlacDecoder.decode(fixture(name)), rate, channels, seconds);
    }

    /** 夹具覆盖不到的位宽（ffmpeg 只出 16/24bit）：满幅与负满幅都映射到 16bit 两端。 */
    @ParameterizedTest
    @CsvSource(textBlock = """
            8,  127,        -128,        32512
            12, 2047,       -2048,       32752
            16, 32767,      -32768,      32767
            20, 524287,     -524288,     32767
            24, 8388607,    -8388608,    32767
            32, 2147483647, -2147483648, 32767
            """)
    void to16ScalesEveryBitDepth(int bits, int max, int min, int expectedMax) {
        assertEquals(expectedMax, FlacDecoder.to16(max, bits));
        assertEquals(-32768, FlacDecoder.to16(min, bits));
        assertEquals(0, FlacDecoder.to16(0, bits));
    }

    private static void assertFullChannelPcm(RadioLibrary.Pcm pcm, int rate, int channels, double seconds) {
        AudioFormat f = pcm.format();
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, f.getEncoding());
        assertEquals(16, f.getSampleSizeInBits());
        assertEquals(channels, f.getChannels());
        assertEquals(rate, f.getSampleRate(), 0.0);
        assertTrue(!f.isBigEndian());
        assertEquals(Math.round(seconds * rate) * 2 * channels, pcm.data().length);
        assertEquals(seconds, pcm.seconds(), 1e-6);
        for (int c = 0; c < channels; c++) {
            assertSinePeak(pcm.data(), channels * 2, c * 2);
        }
    }

    private static void assertMono16(AudioFormat f, int rate) {
        assertEquals(AudioFormat.Encoding.PCM_SIGNED, f.getEncoding());
        assertEquals(16, f.getSampleSizeInBits());
        assertEquals(1, f.getChannels());
        assertEquals(rate, f.getSampleRate(), 0.0);
        assertTrue(!f.isBigEndian());
    }

    /** 小端 16bit 指定声道的峰值应 ≈ 4096（sine 幅度 1/8）。 */
    private static void assertSinePeak(byte[] data, int frameSize, int channelOffset) {
        int peak = 0;
        for (int off = channelOffset; off + 1 < data.length; off += frameSize) {
            int v = (short) ((data[off] & 0xFF) | (data[off + 1] << 8));
            peak = Math.max(peak, Math.abs(v));
        }
        assertTrue(peak > 3800 && peak < 4400, "sine peak out of range: " + peak);
    }

    private static Path fixture(String name) throws Exception {
        URL url = FlacDecoderTest.class.getResource(name);
        assertNotNull(url, "fixture " + name + " missing");
        return Path.of(url.toURI());
    }
}
