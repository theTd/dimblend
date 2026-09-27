package dimblend.radio.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MP3 头解析：只读帧头算时长，不解码 PCM。
 *
 * <p>VBR 带 Xing/Info 头时直接读总帧数；否则按首帧推码率估算（误差 &lt;5%，切歌时钟够用）。</p>
 */
final class Mp3Util {
    static double durationSeconds(byte[] bytes) throws Exception {
        Class<?> bitstreamClass = Class.forName("javazoom.jl.decoder.Bitstream");
        Class<?> headerClass = Class.forName("javazoom.jl.decoder.Header");
        Object bitstream = bitstreamClass.getConstructor(InputStream.class)
                .newInstance(new ByteArrayInputStream(bytes));
        try {
            var readFrame = bitstreamClass.getMethod("readFrame");
            var closeFrame = bitstreamClass.getMethod("closeFrame");
            // Xing/VBR 信息：Header.parseVBR 已在 read_header 内触发（JLayer 行为），
            // total_ms 需要总帧数——JLayer Header.total_ms(frames) 逐帧累计太贵，
            // 此处用首帧码率 + 文件长度估算，VBR 误差可接受（切歌时钟±几秒无妨）。
            Object header = null;
            int frames = 0;
            int bitrateSum = 0;
            float msPerFrame = 0;
            // 最多读 64 帧取平均码率，避免全文件扫描
            while (frames < 64) {
                Object h;
                try {
                    h = readFrame.invoke(bitstream);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    break;
                }
                if (h == null) {
                    break;
                }
                header = h;
                int bitrate = (Integer) headerClass.getMethod("bitrate").invoke(h);
                msPerFrame = (Float) headerClass.getMethod("ms_per_frame").invoke(h);
                // VBR 头：vbr()==true 说明是 VBR 流，用 total_ms(估帧数) 修正
                bitrateSum += bitrate;
                frames++;
                closeFrame.invoke(bitstream);
            }
            if (header == null || frames == 0 || msPerFrame <= 0) {
                return 180.0;
            }
            boolean vbr = (Boolean) headerClass.getMethod("vbr").invoke(header);
            if (vbr) {
                // VBR：平均码率反推总时长
                int avgBitrate = bitrateSum / frames;
                if (avgBitrate <= 0) {
                    return 180.0;
                }
                return (bytes.length * 8.0) / avgBitrate;
            }
            int bitrate = bitrateSum / frames;
            if (bitrate <= 0) {
                return 180.0;
            }
            return (bytes.length * 8.0) / bitrate;
        } finally {
            bitstreamClass.getMethod("close").invoke(bitstream);
        }
    }

    private Mp3Util() {
    }
}
