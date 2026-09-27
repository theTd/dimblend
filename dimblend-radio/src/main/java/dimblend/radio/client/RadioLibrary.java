package dimblend.radio.client;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import net.minecraft.Util;
import net.neoforged.fml.loading.FMLPaths;

import dimblend.radio.DimBlendRadio;

/**
 * 客户端曲库：{@code <gameDir>/dimblend_radio/<1..14>/*.{ogg,mp3,flac,wav}}。
 *
 * <p>解码链：ogg → 原版 JOrbis（运行时反射，不编译依赖）；mp3 → JLayer
 * （{@code Decoder.decodeFrame} 逐帧 → short[]，javap 已核对签名）；
 * flac → jFLAC {@code FlacAudioFileReader.getAudioInputStream}（javax.sound SPI）；
 * wav → {@code AudioSystem}。全部归一成 16bit 小端 PCM（多声道下混立体声），
 * 再按当前 side 做 PCM 预放大（>100% 部分，引擎增益封顶 1.0 做不到）。</p>
 *
 * <p>hash = 文件字节 SHA-256 hex（服务端选曲/同步只传它）。时长 = PCM 帧数/采样率。</p>
 */
public final class RadioLibrary {
    public static final String DIR_NAME = "dimblend_radio";

    public record Track(String hash, String fileName, int station, double seconds) {
    }

    public record Pcm(AudioFormat format, byte[] data, double seconds) {
    }

    private static final Map<Integer, List<Track>> TRACKS = new HashMap<>();
    private static final Map<String, Path> HASH_TO_FILE = new HashMap<>();
    private static volatile boolean scanned;

    public static Path radioDir() {
        return FMLPaths.GAMEDIR.get().resolve(DIR_NAME);
    }

    /** IO 池异步扫目录（启动/进服时调用一次，幂等）。 */
    public static void scanAsync() {
        if (scanned) {
            return;
        }
        scanned = true;
        Util.ioPool().execute(() -> {
            try {
                scanNow();
            } catch (Exception e) {
                DimBlendRadio.LOGGER.warn("[radio] scan dimblend_radio failed", e);
            }
        });
    }

    public synchronized static void scanNow() throws IOException {
        Path root = radioDir();
        Files.createDirectories(root);
        TRACKS.clear();
        HASH_TO_FILE.clear();
        for (int station = 1; station <= 14; station++) {
            Path dir = root.resolve(String.valueOf(station));
            Files.createDirectories(dir);
            List<Track> list = new ArrayList<>();
            try (var stream = Files.list(dir)) {
                for (Path file : stream.filter(Files::isRegularFile).sorted().toList()) {
                    String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(name.endsWith(".ogg") || name.endsWith(".mp3")
                            || name.endsWith(".flac") || name.endsWith(".wav"))) {
                        continue;
                    }
                    try {
                        byte[] bytes = Files.readAllBytes(file);
                        String hash = sha256(bytes);
                        double seconds = probeSeconds(file, bytes);
                        list.add(new Track(hash, file.getFileName().toString(), station, seconds));
                        HASH_TO_FILE.putIfAbsent(hash, file);
                    } catch (Exception e) {
                        DimBlendRadio.LOGGER.warn("[radio] skip unreadable file {}", file, e);
                    }
                }
            }
            TRACKS.put(station, list);
        }
        int total = TRACKS.values().stream().mapToInt(List::size).sum();
        DimBlendRadio.LOGGER.info("[radio] scanned dimblend_radio: {} stations, {} tracks", TRACKS.size(), total);
    }

    public synchronized static Map<Integer, Map<String, Double>> catalogView() {
        Map<Integer, Map<String, Double>> out = new HashMap<>();
        for (var entry : TRACKS.entrySet()) {
            Map<String, Double> tracks = new HashMap<>();
            for (Track track : entry.getValue()) {
                tracks.put(track.hash(), track.seconds());
            }
            out.put(entry.getKey(), tracks);
        }
        return out;
    }

    public synchronized static Path fileOf(String hash) {
        return HASH_TO_FILE.get(hash);
    }

    /** 解码 + 下混 + 预放大（boost ≥1，150%→1.5），返回可直接送通道的 PCM。 */
    public static Pcm decode(Path file, float boost) throws Exception {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Pcm pcm;
        if (name.endsWith(".mp3")) {
            pcm = decodeMp3(Files.readAllBytes(file));
        } else if (name.endsWith(".flac")) {
            pcm = FlacDecoder.decode(file);
        } else if (name.endsWith(".wav")) {
            pcm = decodeSpi(file);
        } else {
            pcm = decodeOgg(Files.readAllBytes(file));
        }
        if (boost != 1.0f) {
            applyBoostInPlace(pcm.data(), boost);
        }
        return pcm;
    }

    // ---- ogg：原版 JOrbis 反射（FloatSampleSource.readChunk 泵 float → 16bit） ----

    private static Pcm decodeOgg(byte[] bytes) throws Exception {
        Class<?> streamClass = Class.forName("net.minecraft.client.sounds.JOrbisAudioStream");
        Class<?> chunkClass = Class.forName("net.minecraft.client.sounds.ChunkedSampleByteBuf");
        try (InputStream in = new java.io.ByteArrayInputStream(bytes);
                java.io.Closeable stream = (java.io.Closeable) streamClass
                        .getConstructor(InputStream.class).newInstance(in)) {
            Object chunk = chunkClass.getConstructor(int.class).newInstance(16384);
            var readChunk = streamClass.getMethod("readChunk", it.unimi.dsi.fastutil.floats.FloatConsumer.class);
            var get = chunkClass.getMethod("get");
            while ((Boolean) readChunk.invoke(stream, chunk)) {
            }
            ByteBuffer buf = (ByteBuffer) get.invoke(chunk);
            byte[] data = new byte[buf.remaining()];
            buf.get(data);
            var getFormat = streamClass.getMethod("getFormat");
            AudioFormat format = (AudioFormat) getFormat.invoke(stream);
            MonoPcm mono = toMono(format, data);
            double seconds = mono.format().getSampleRate() <= 0 ? 180.0
                    : (double) mono.data().length / mono.format().getFrameSize() / mono.format().getSampleRate();
            return new Pcm(mono.format(), mono.data(), seconds);
        }
    }

    // ---- mp3：JLayer（javap 核对：Decoder.decodeFrame(Header, Bitstream)→Obuffer） ----

    private static Pcm decodeMp3(byte[] bytes) throws Exception {
        var bitstreamClass = Class.forName("javazoom.jl.decoder.Bitstream");
        var decoderClass = Class.forName("javazoom.jl.decoder.Decoder");
        var sampleBufferClass = Class.forName("javazoom.jl.decoder.SampleBuffer");
        Object bitstream = bitstreamClass.getConstructor(InputStream.class)
                .newInstance(new ByteArrayInputStream(bytes));
        Object decoder = decoderClass.getConstructor().newInstance();
        var readFrame = bitstreamClass.getMethod("readFrame");
        var decodeFrame = decoderClass.getMethod("decodeFrame",
                Class.forName("javazoom.jl.decoder.Header"), Class.forName("javazoom.jl.decoder.Bitstream"));
        var closeFrame = bitstreamClass.getMethod("closeFrame");
        // 流式拼 PCM：按帧写固定 chunk，不再 ByteArrayOutputStream 指数扩容
        java.util.List<byte[]> chunks = new java.util.ArrayList<>(4096);
        int totalBytes = 0;
        int channels = 2;
        float sampleRate = 44100;
        try {
            while (true) {
                Object header;
                try {
                    header = readFrame.invoke(bitstream);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    break; // 流尾
                }
                if (header == null) {
                    break;
                }
                Object obuffer = decodeFrame.invoke(decoder, header, bitstream);
                short[] samples = (short[]) sampleBufferClass.getMethod("getBuffer").invoke(obuffer);
                int len = (Integer) sampleBufferClass.getMethod("getBufferLength").invoke(obuffer);
                channels = (Integer) sampleBufferClass.getMethod("getChannelCount").invoke(obuffer);
                sampleRate = (Integer) sampleBufferClass.getMethod("getSampleFrequency").invoke(obuffer);
                byte[] frame = new byte[len * 2];
                for (int i = 0; i < len; i++) {
                    short s = samples[i];
                    frame[i * 2] = (byte) (s & 0xFF);
                    frame[i * 2 + 1] = (byte) ((s >>> 8) & 0xFF);
                }
                chunks.add(frame);
                totalBytes += frame.length;
                if (totalBytes > 256L << 20) {
                    throw new IOException("decoded MP3 exceeds 256MB, refusing: " + totalBytes + " bytes");
                }
                closeFrame.invoke(bitstream);
            }
        } finally {
            bitstreamClass.getMethod("close").invoke(bitstream);
        }
        byte[] data = new byte[totalBytes];
        int off = 0;
        for (byte[] frame : chunks) {
            System.arraycopy(frame, 0, data, off, frame.length);
            off += frame.length;
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, channels, true, false);
        MonoPcm mono = toMono(format, data);
        return new Pcm(mono.format(), mono.data(),
                sampleRate <= 0 ? 180.0 : (double) mono.data().length / mono.format().getFrameSize() / sampleRate);
    }

    // ---- flac/wav：javax.sound SPI（jFLAC 注册 FlacAudioFileReader） ----

    private static Pcm decodeSpi(Path file) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file.toFile())) {
            AudioFormat base = in.getFormat();
            // 全声道解出再 toMono（不要在这里截成立体声，否则下混基准错）
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    base.getSampleRate(), 16, base.getChannels(),
                    base.getChannels() * 2, base.getSampleRate(), false);
            try (AudioInputStream pcm = AudioSystem.getAudioInputStream(target, in)) {
                byte[] data = pcm.readAllBytes();
                MonoPcm mono = toMono(target, data);
                double seconds = mono.format().getSampleRate() <= 0 ? 180.0
                        : (double) mono.data().length / mono.format().getFrameSize() / mono.format().getSampleRate();
                return new Pcm(mono.format(), mono.data(), seconds);
            }
        }
    }

    private static double probeSeconds(Path file, byte[] bytes) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            if (name.endsWith(".mp3")) {
                return Mp3Util.durationSeconds(bytes);
            } else if (name.endsWith(".flac")) {
                return FlacHeader.durationSeconds(file);
            } else if (name.endsWith(".wav")) {
                return WavHeader.durationSeconds(file);
            } else {
                return OggHeader.durationSeconds(bytes);
            }
        } catch (Exception e) {
            DimBlendRadio.LOGGER.warn("[radio] probe duration failed for {}", file, e);
            return 180.0;
        }
    }

    /**
     * 下混单声道（OpenAL 定位要求：立体声源无空间化，听感就是“立体声、没有距离渐隐”。
     * 单声道源才有距离方向感。多声道→能量平均，保证响度不塌）。
     * 返回新数组 + 新 format（帧长减半，无尾部旧字节问题）。
     */
    private record MonoPcm(AudioFormat format, byte[] data) {
    }

    private static MonoPcm toMono(AudioFormat format, byte[] data) {
        int channels = format.getChannels();
        if (channels == 1) {
            return new MonoPcm(format, data);
        }
        int frames = data.length / format.getFrameSize();
        byte[] mixed = new byte[frames * 2];
        int oldFrame = format.getFrameSize();
        for (int f = 0; f < frames; f++) {
            int acc = 0;
            for (int c = 0; c < channels; c++) {
                int off = f * oldFrame + c * 2;
                acc += (short) ((data[off] & 0xFF) | (data[off + 1] << 8));
            }
            int v = acc / channels;
            mixed[f * 2] = (byte) v;
            mixed[f * 2 + 1] = (byte) (v >> 8);
        }
        return new MonoPcm(new AudioFormat(format.getSampleRate(), 16, 1, true, false), mixed);
    }

    /** 线性预放大（150% 响度来源），clamp 防削波。 */
    static void applyBoostInPlace(byte[] data, float boost) {
        for (int i = 0; i + 1 < data.length; i += 2) {
            short s = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            int v = Math.round(s * boost);
            v = Math.max(-32768, Math.min(32767, v));
            data[i] = (byte) v;
            data[i + 1] = (byte) (v >> 8);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private RadioLibrary() {
    }
}
