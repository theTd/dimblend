package dimblend.radio.client;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** WAV 时长：fmt + data 头反推，不解码。 */
final class WavHeader {
    static double durationSeconds(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(44);
            if (head.length < 44 || head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F') {
                return 180.0;
            }
            int byteRate = (head[28] & 0xFF) | ((head[29] & 0xFF) << 8)
                    | ((head[30] & 0xFF) << 16) | ((head[31] & 0xFF) << 24);
            // data chunk：从 36 起找 "data"，读 size
            int dataSize = -1;
            for (int i = 12; i + 8 <= head.length; i++) {
                if (head[i] == 'd' && head[i + 1] == 'a' && head[i + 2] == 't' && head[i + 3] == 'a') {
                    dataSize = (head[i + 4] & 0xFF) | ((head[i + 5] & 0xFF) << 8)
                            | ((head[i + 6] & 0xFF) << 16) | ((head[i + 7] & 0xFF) << 24);
                    break;
                }
            }
            if (dataSize < 0) {
                dataSize = (int) Math.max(0, Files.size(file) - 44);
            }
            if (byteRate <= 0) {
                return 180.0;
            }
            return (double) dataSize / byteRate;
        }
    }

    private WavHeader() {
    }
}
