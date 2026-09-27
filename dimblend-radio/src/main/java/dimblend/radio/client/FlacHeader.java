package dimblend.radio.client;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * FLAC/WAV 时长：只读容器头，不解码 PCM。
 *
 * <p>FLAC：StreamInfo 块（"fLaC" 后首个 metadata 头，type 0，length 34）：
 * sampleRate 20bit + channels(3bit)+bps(5bit) + totalSamples 36bit。
 * （WAV 时长见 {@link WavHeader}。）</p>
 */
final class FlacHeader {
    static double durationSeconds(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(42);
            if (head.length < 42 || head[0] != 'f' || head[1] != 'L' || head[2] != 'a' || head[3] != 'C') {
                return 180.0;
            }
            // metadata 头：1B(isLast 1bit + type 7bit)+3B length；首块必须是 StreamInfo(type 0, 34B)
            int length = ((head[5] & 0xFF) << 16) | ((head[6] & 0xFF) << 8) | (head[7] & 0xFF);
            if ((head[4] & 0x7F) != 0 || length < 34) {
                return 180.0;
            }
            // StreamInfo 内跳过 min/max block(4)+min/max frame(6)，之后按位：
            // sampleRate 20 | channels-1 3 | bps-1 5 | totalSamples 36 → 字节 off+0..off+7
            int off = 8 + 10;
            int sampleRate = ((head[off] & 0xFF) << 12) | ((head[off + 1] & 0xFF) << 4)
                    | ((head[off + 2] & 0xF0) >>> 4);
            long totalSamples = ((long) (head[off + 3] & 0x0F) << 32)
                    | ((long) (head[off + 4] & 0xFF) << 24)
                    | ((long) (head[off + 5] & 0xFF) << 16)
                    | ((long) (head[off + 6] & 0xFF) << 8)
                    | ((long) (head[off + 7] & 0xFF));
            if (sampleRate <= 0 || totalSamples <= 0) {
                return 180.0;
            }
            return (double) totalSamples / sampleRate;
        }
    }

    private FlacHeader() {
    }
}
