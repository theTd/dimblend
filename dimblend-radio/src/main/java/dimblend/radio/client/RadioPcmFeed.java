package dimblend.radio.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.FiniteAudioStream;
import net.minecraft.resources.ResourceLocation;

/**
 * 内存 PCM 供给：hash → 解码好的 PCM（{@link RadioLibrary#decode} 结果）。
 *
 * <p>{@code SoundBufferMixin} 在 {@code getStream/getCompleteBuffer} HEAD 按 path 查这里，
 * 命中则跳过 JOrbis。path 形态 = {@code Sound.getPath()} =
 * {@code sounds/radio/<hash>.ogg}（命名空间 dimblend_radio）。</p>
 */
public final class RadioPcmFeed {
    private record PcmEntry(AudioFormat format, byte[] data) {
    }

    private static final Map<String, PcmEntry> PCM = new ConcurrentHashMap<>();

    /** path → hash：只认 dimblend_radio:sounds/radio/*.ogg。 */
    public static String hashOf(ResourceLocation path) {
        if (!path.getNamespace().equals(dimblend.radio.DimBlendRadio.MODID)) {
            return null;
        }
        String p = path.getPath();
        if (!p.startsWith("sounds/radio/") || !p.endsWith(".ogg")) {
            return null;
        }
        return p.substring("sounds/radio/".length(), p.length() - ".ogg".length());
    }

    public static void put(String hash, AudioFormat format, byte[] data) {
        PCM.put(hash, new PcmEntry(format, data));
    }

    public static AudioStream open(ResourceLocation path) {
        String hash = hashOf(path);
        if (hash == null) {
            return null;
        }
        PcmEntry entry = PCM.get(hash);
        if (entry == null) {
            return null;
        }
        return new FeedStream(entry.format(), entry.data());
    }

    private static final class FeedStream implements FiniteAudioStream {
        private final AudioFormat format;
        private final byte[] data;
        private int cursor;

        FeedStream(AudioFormat format, byte[] data) {
            this.format = format;
            this.data = data;
        }

        @Override
        public AudioFormat getFormat() {
            return this.format;
        }

        @Override
        public ByteBuffer read(int bytes) throws IOException {
            int n = Math.min(bytes, this.data.length - this.cursor);
            ByteBuffer buf = ByteBuffer.allocateDirect(n);
            buf.put(this.data, this.cursor, n);
            buf.flip();
            this.cursor += n;
            return buf;
        }

        @Override
        public ByteBuffer readAll() throws IOException {
            ByteBuffer buf = ByteBuffer.allocateDirect(this.data.length);
            buf.put(this.data);
            buf.flip();
            return buf;
        }

        @Override
        public void close() throws IOException {
        }
    }

    private RadioPcmFeed() {
    }
}
