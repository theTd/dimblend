package dimblend.radio.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MP3 时长：逐帧读帧头累加每帧时长，不解码 PCM。
 *
 * <p>旧做法“文件字节 × 8 / 首 64 帧均码率”把 ID3 标签（内嵌封面动辄几 MB）也算成音频，
 * 时长虚高几秒到一百多秒，服务端按它排曲终，曲间静音远超 5 秒。逐帧累加只数音频帧，
 * 与完整解码的 PCM 时长一致（实测 14 首误差 &lt;0.05s），读头不解码，单曲几十毫秒。</p>
 */
final class Mp3Util {
    /** 兜底时长：读不到任何帧时（文件损坏）用。 */
    private static final double FALLBACK_SECONDS = 180.0;

    static double durationSeconds(byte[] bytes) throws Exception {
        Class<?> bitstreamClass = Class.forName("javazoom.jl.decoder.Bitstream");
        Class<?> headerClass = Class.forName("javazoom.jl.decoder.Header");
        Object bitstream = bitstreamClass.getConstructor(InputStream.class)
                .newInstance(new ByteArrayInputStream(bytes));
        try {
            var readFrame = bitstreamClass.getMethod("readFrame");
            var closeFrame = bitstreamClass.getMethod("closeFrame");
            var msPerFrame = headerClass.getMethod("ms_per_frame");
            double totalMs = 0;
            int frames = 0;
            while (true) {
                Object header;
                try {
                    header = readFrame.invoke(bitstream);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    break; // 流尾杂数据（ID3v1/APE 标签等）
                }
                if (header == null) {
                    break;
                }
                totalMs += (Float) msPerFrame.invoke(header);
                frames++;
                closeFrame.invoke(bitstream);
            }
            if (frames == 0 || totalMs <= 0) {
                return FALLBACK_SECONDS;
            }
            return totalMs / 1000.0;
        } finally {
            bitstreamClass.getMethod("close").invoke(bitstream);
        }
    }

    private Mp3Util() {
    }
}
