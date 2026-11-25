package dimblend.experience.compat.simurail.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;

import dimblend.experience.DimBlend;
import it.unimi.dsi.fastutil.floats.FloatConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * {@code dimblend_experience:sounds/train_brake.ogg} 的 PCM 缓存（客户端一次性解码）。
 *
 * <p>素材实测：Ogg Vorbis 单声道（{@code channels = 1}）、{@code 48 kHz}、
 * {@code 239823} 帧（约 {@code 4.9963s}），解码后 16-bit PCM 仅约 {@code 480 KB}。
 * 解码走原版 {@link JOrbisAudioStream}，浮点→16-bit 的量化公式与
 * {@code ChunkedSampleByteBuf#accept} 逐字一致
 * （{@code Mth.clamp((int) (value * 32767.5F - 0.5F), -32768, 32767)}），
 * 因此本缓存与引擎自己解码出的字节完全相同，只是搬进了内存以便任意游标读取。</p>
 *
 * <p>只解码一次（懒加载 + 失败只记一条日志），{@link #clear()} 供资源重载/登出复位。
 * 解码在 {@code Util.nonCriticalIoPool} 上执行（由 {@code getStream} 发起），不占客户端 tick。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class TrainBrakePcmCache {

    /** {@code Sound#getPath()} 的口径：{@code FileToIdConverter("sounds", ".ogg").idToFile(...)}。 */
    private static final ResourceLocation OGG =
            ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "sounds/train_brake.ogg");

    /** PCM 加载失败时给引擎的合法格式（与素材一致），配合空流使用。 */
    public static final AudioFormat FALLBACK_FORMAT = new AudioFormat(48_000.0F, 16, 1, true, false);

    private static volatile TrainBrakePcmCache instance;
    private static boolean failed;

    private final short[] frames;
    private final int channels;
    private final int frameCount;
    private final AudioFormat format;

    private TrainBrakePcmCache(short[] frames, int channels, AudioFormat format) {
        this.frames = frames;
        this.channels = channels;
        this.format = format;
        this.frameCount = frames.length / channels;
    }

    /** 懒加载；解码失败（文件缺失/损坏）返回 {@code null} 并只记一次日志。 */
    public static TrainBrakePcmCache load() {
        TrainBrakePcmCache ready = instance;
        if (ready != null) {
            return ready;
        }
        synchronized (TrainBrakePcmCache.class) {
            if (instance != null) {
                return instance;
            }
            if (failed) {
                return null;
            }
            try {
                instance = decode();
                return instance;
            } catch (IOException | RuntimeException exception) {
                failed = true;
                DimBlend.LOGGER.warn("Failed to decode brake sustain PCM from {}", OGG, exception);
                return null;
            }
        }
    }

    /** 资源重载 / 登出复位：下次播放重新解码。 */
    public static void clear() {
        synchronized (TrainBrakePcmCache.class) {
            instance = null;
            failed = false;
        }
    }

    public short[] frames() {
        return this.frames;
    }

    public int channels() {
        return this.channels;
    }

    public int frameCount() {
        return this.frameCount;
    }

    public AudioFormat format() {
        return this.format;
    }

    /** 用本缓存的 PCM 与给定控制位新建一条流。 */
    public TrainBrakeSustainAudioStream newStream(TrainBrakeSustainAudioStream.Control control) {
        return new TrainBrakeSustainAudioStream(this.frames, this.channels, this.format, control);
    }

    private static TrainBrakePcmCache decode() throws IOException {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            throw new IOException("Minecraft instance not available");
        }
        try (InputStream input = minecraft.getResourceManager().open(OGG);
             JOrbisAudioStream decoder = new JOrbisAudioStream(input)) {
            AudioFormat format = decoder.getFormat();
            if (format.getSampleSizeInBits() != 16 || format.isBigEndian()) {
                throw new IOException("Unsupported PCM layout: " + format);
            }
            int channels = format.getChannels();
            if (channels <= 0) {
                throw new IOException("Unsupported channel count: " + channels);
            }
            SampleCollector collector = new SampleCollector();
            while (decoder.readChunk(collector)) {
                // 解码到底
            }
            short[] data = collector.trimmed(channels);
            if (data.length == 0) {
                throw new IOException("Decoded brake sample is empty");
            }
            return new TrainBrakePcmCache(data, channels, format);
        }
    }

    /** 与 {@code ChunkedSampleByteBuf} 同款的浮点→16-bit 收集器（可增长数组，避免装箱）。 */
    private static final class SampleCollector implements FloatConsumer {

        private short[] data = new short[1 << 15];
        private int size;

        @Override
        public void accept(float value) {
            if (this.size == this.data.length) {
                this.data = Arrays.copyOf(this.data, this.data.length * 2);
            }
            this.data[this.size++] = (short) Mth.clamp((int) (value * 32767.5F - 0.5F), -32768, 32767);
        }

        /** 截到整帧边界（解码器理论上总是整帧，防御性取整）。 */
        short[] trimmed(int channels) {
            int whole = this.size - this.size % channels;
            return Arrays.copyOf(this.data, whole);
        }
    }
}
