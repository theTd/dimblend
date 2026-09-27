package dimblend.radio.client;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ogg Vorbis 时长：只读末页 granulepos（总采样数），不解码。
 *
 * <p>Ogg 页结构：末 4 字节 capture pattern "OggS" 定位，granulepos 在页头 +6 偏移 8 字节 LE。
 * identification 包头在首包（byte[30] 处 rate LE32，channels 在 byte[28]）。</p>
 */
final class OggHeader {
    static double durationSeconds(byte[] bytes) throws Exception {
        if (bytes.length < 64) {
            return 180.0;
        }
        // 首包 headers：version(1)+channels(1)+rate(4LE) — 偏移：27（首包数据起始）+1+1
        int channels = 2;
        int rate = 44100;
        try {
            // OggS 页头 27 字节，首包类型 0x01(vorbis id)，"vorbis"(6)，version(4)，channels(1)，rate(4LE)
            int idOff = findIdPacket(bytes);
            if (idOff > 0) {
                channels = bytes[idOff + 11] & 0xFF;
                rate = (bytes[idOff + 12] & 0xFF) | ((bytes[idOff + 13] & 0xFF) << 8)
                        | ((bytes[idOff + 14] & 0xFF) << 16) | ((bytes[idOff + 15] & 0xFF) << 24);
            }
        } catch (Exception ignored) {
        }
        // 末页 granulepos：从尾部找最后一个 "OggS"，+6 读 8 字节 LE
        long totalSamples = -1;
        for (int i = bytes.length - 4; i >= Math.max(0, bytes.length - 65536); i--) {
            if (bytes[i] == 'O' && bytes[i + 1] == 'g' && bytes[i + 2] == 'g' && bytes[i + 3] == 'S') {
                if (i + 14 <= bytes.length) {
                    long granule = 0;
                    for (int b = 0; b < 8; b++) {
                        granule |= ((long) (bytes[i + 6 + b] & 0xFF)) << (8 * b);
                    }
                    if (granule > 0 && granule < Long.MAX_VALUE - 1) {
                        totalSamples = granule;
                        break;
                    }
                }
            }
        }
        if (totalSamples <= 0 || rate <= 0) {
            return 180.0;
        }
        return (double) totalSamples / rate;
    }

    private static int findIdPacket(byte[] bytes) {
        // 首个 OggS 页内容起始（27 字节页头 + 段表）
        if (bytes.length < 40 || bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S') {
            return -1;
        }
        int segments = bytes[26] & 0xFF;
        int off = 27 + segments;
        // 段长表之后：packet type(1) + "vorbis"(6)
        if (off + 12 > bytes.length) {
            return -1;
        }
        if (bytes[off + 1] == 'v' && bytes[off + 2] == 'o') {
            return off;
        }
        return -1;
    }

    private OggHeader() {
    }
}
