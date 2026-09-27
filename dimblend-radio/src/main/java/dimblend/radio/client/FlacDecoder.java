package dimblend.radio.client;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import javax.sound.sampled.AudioFormat;

/**
 * FLAC 解码（jFLAC 二选一，按可用性自动降级）：
 *
 * <p>A 路（首选）：{@code FlacAudioFileReader.getAudioInputStream(File)}（javax.sound SPI，
 * jar 内自带 services 注册）。B 路（SPI 在生产 jar 被 JiJ/合并策略吞掉时）：
 * {@code FLACDecoder} 逐帧 + StreamInfo 算时长 + ChannelData 拼 PCM。</p>
 */
final class FlacDecoder {
    static RadioLibrary.Pcm decode(Path file) throws Exception {
        try {
            return spiDecode(file);
        } catch (Exception spiFail) {
            return frameDecode(file, spiFail);
        }
    }

    private static RadioLibrary.Pcm spiDecode(Path file) throws Exception {
        Class<?> readerClass = Class.forName("org.jflac.sound.spi.FlacAudioFileReader");
        Object reader = readerClass.getConstructor().newInstance();
        try (InputStream in = new FileInputStream(file.toFile())) {
            Object stream = readerClass
                    .getMethod("getAudioInputStream", InputStream.class).invoke(reader, in);
            Class<?> aisClass = Class.forName("javax.sound.sampled.AudioInputStream");
            var getFormat = aisClass.getMethod("getFormat");
            var readAll = aisClass.getMethod("readAllBytes");
            var close = aisClass.getMethod("close");
            try {
                AudioFormat base = (AudioFormat) getFormat.invoke(stream);
                int channels = Math.min(2, base.getChannels());
                AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                        base.getSampleRate(), 16, channels, channels * 2, base.getSampleRate(), false);
                // javax.sound 的转换流：AudioSystem.getAudioInputStream(target, source)
                Class<?> systemClass = Class.forName("javax.sound.sampled.AudioSystem");
                Object pcmStream = systemClass
                        .getMethod("getAudioInputStream", AudioFormat.class, aisClass)
                        .invoke(null, target, stream);
                byte[] data = (byte[]) readAll.invoke(pcmStream);
                double seconds = target.getSampleRate() <= 0 ? 180.0
                        : (double) data.length / target.getFrameSize() / target.getSampleRate();
                return new RadioLibrary.Pcm(target, data, seconds);
            } finally {
                close.invoke(stream);
            }
        }
    }

    private static RadioLibrary.Pcm frameDecode(Path file, Exception spiCause) throws Exception {
        Class<?> decoderClass = Class.forName("org.jflac.FLACDecoder");
        Class<?> pcmProcClass = Class.forName("org.jflac.PCMProcessor");
        try (InputStream in = new FileInputStream(file.toFile())) {
            Object decoder = decoderClass.getConstructor(InputStream.class).newInstance(in);
            Object streamInfo = decoderClass.getMethod("readStreamInfo").invoke(decoder);
            Class<?> infoClass = Class.forName("org.jflac.metadata.StreamInfo");
            int sampleRate = (Integer) infoClass.getMethod("getSampleRate").invoke(streamInfo);
            int srcChannels = (Integer) infoClass.getMethod("getChannels").invoke(streamInfo);
            int bitsPerSample = (Integer) infoClass.getMethod("getBitsPerSample").invoke(streamInfo);
            AudioFormat format = new AudioFormat(sampleRate, 16, srcChannels, true, false);
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    (int) Math.min(1 << 24, Files.size(file)));
            final int bps = bitsPerSample;
            final int ch = srcChannels;
            Object processor = java.lang.reflect.Proxy.newProxyInstance(FlacDecoder.class.getClassLoader(),
                    new Class<?>[] { (Class<?>) pcmProcClass }, (proxy, method, args) -> {
                        if (method.getName().equals("processPCM")
                                && args != null && args.length == 1) {
                            // ByteData（javap 核对：getData()/getLen()）
                            Class<?> byteDataClass = Class.forName("org.jflac.util.ByteData");
                            Object byteData = args[0];
                            byte[] chunk = (byte[]) byteDataClass.getMethod("getData").invoke(byteData);
                            int len = (Integer) byteDataClass.getMethod("getLen").invoke(byteData);
                            // B 路只处理 16bit 输入；24bit 取低 16 位降精度（罕见，不抛错）
                            if (bps > 16) {
                                int bytesPerSample = bps / 8;
                                int samples = len / bytesPerSample / ch;
                                for (int s = 0; s < samples; s++) {
                                    for (int c = 0; c < ch; c++) {
                                        int off = (s * ch + c) * bytesPerSample + (bytesPerSample - 2);
                                        int v = (short) ((chunk[off] & 0xFF) | (chunk[off + 1] << 8));
                                        out.write(v & 0xFF);
                                        out.write((v >>> 8) & 0xFF);
                                    }
                                }
                            } else {
                                out.write(chunk, 0, len);
                            }
                        }
                        return null;
                    });
            decoderClass.getMethod("addPCMProcessor", pcmProcClass).invoke(decoder, processor);
            try {
                decoderClass.getMethod("decode").invoke(decoder);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof java.io.EOFException) {
                    // 正常结束
                } else {
                    throw e;
                }
            }
            byte[] data = out.toByteArray();
            double seconds = sampleRate <= 0 ? 180.0
                    : (double) data.length / format.getFrameSize() / sampleRate;
            return new RadioLibrary.Pcm(format, data, seconds);
        } catch (Exception frameFail) {
            frameFail.addSuppressed(spiCause);
            throw frameFail;
        }
    }

    private FlacDecoder() {
    }
}
