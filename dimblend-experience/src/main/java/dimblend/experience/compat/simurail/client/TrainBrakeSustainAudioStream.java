package dimblend.experience.compat.simurail.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.sound.sampled.AudioFormat;

import dimblend.experience.compat.simurail.TrainBrakeSustainRules;
import net.minecraft.client.sounds.AudioStream;

/**
 * 刹车持续鸣响的 PCM 流（纯算术，可脱离 OpenAL / Minecraft 单测）。
 *
 * <p>由 {@code train_brake.ogg} 解码出的 PCM（48 kHz / 16-bit / 单声道，{@code 239823} 帧）
 * 构成三条路径：</p>
 *
 * <ol>
 * <li><b>持续段</b>（{@code sustaining}）：游标从 0 自然推进；进入
 * {@code [loopEnd - crossfade, loopEnd)} 后把尾部与 {@code [loopStart, loopStart+crossfade)}
 * 线性交叉淡化，到 {@code loopEnd} 回绕到 {@code loopStart + crossfade}，于是 75% 位置的
 * 稳定摩擦音节可以无爆音地无限重复。</li>
 * <li><b>淡出段</b>（{@link Control#releaseSustain()}，速度跌破 2 m/s）：不再回绕，游标一路
 * 播到 {@code pcm.length}，用的正是素材自带的后 25% 自然衰减尾音，播完返回空
 * {@link ByteBuffer}（EOF）。</li>
 * <li><b>急停</b>（{@link Control#stopImmediately()}，松闸或车架消失）：从当前输出起做
 * {@code 5 ms}（48 kHz = {@code 240} 帧）线性斜坡归零，随即 EOF。</li>
 * </ol>
 *
 * <p>与 OpenAL 的真实关系（必须写明）：{@code Channel#attachBufferStream} 用
 * {@code calculateBufferSize(format, 1)} 取 1 秒一块、一次预灌 4 块，所以源端本身还排着
 * 最多约 4 秒已缓冲的音频。真正「松闸瞬停」是引擎的 {@code AL10.alSourceStop}
 * （{@code SoundEngine#stop}），5 ms 斜坡只在流被提前读走时才有意义，是防爆音的额外保险；
 * 淡出段则确实能生效，因为音量是播放时施加的源增益。</p>
 */
public final class TrainBrakeSustainAudioStream implements AudioStream {

    /** PCM 加载失败时的兜底帧（1 帧静音，构造后立即置 EOF）。 */
    private static final short[] SILENT_FRAME = new short[1];

    /**
     * 控制位。实例构造时创建一次，{@link #getStream} 建流时把同一引用交给流；因此
     * 「异步解码还没完成」与「已有流在播」两种状态都能被同一个对象驱动。
     */
    public static final class Control {

        private volatile boolean sustaining = true;
        private volatile boolean immediateStop;

        public boolean sustaining() {
            return this.sustaining;
        }

        public boolean immediateStopRequested() {
            return this.immediateStop;
        }

        /** 速度跌破 2 m/s：放行素材自带的衰减尾音。幂等。 */
        public void releaseSustain() {
            this.sustaining = false;
        }

        /** 松闸 / 车架消失：5 ms 归零后结束流。幂等。 */
        public void stopImmediately() {
            this.immediateStop = true;
        }

        /** 回调/重连复位（正常播放不需要调用）。 */
        public void reset() {
            this.sustaining = true;
            this.immediateStop = false;
        }
    }

    private final short[] frames;
    private final int channels;
    private final int frameCount;
    private final AudioFormat format;
    private final TrainBrakeSustainRules.LoopWindow window;
    private final int stopRampFrames;
    private final Control control;

    private int pos;
    /** {@code -1} = 未进入急停；{@code >= 0} = 还剩多少帧斜坡。 */
    private int stopRampLeft = -1;
    private boolean closed;
    private boolean eof;

    public TrainBrakeSustainAudioStream(short[] frames, int channels, AudioFormat format, Control control) {
        if (frames == null || control == null || format == null) {
            throw new IllegalArgumentException("frames / control / format must not be null");
        }
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive");
        }
        this.frames = frames;
        this.channels = channels;
        this.format = format;
        this.control = control;
        this.frameCount = frames.length / channels;
        this.window = TrainBrakeSustainRules.loopWindow(this.frameCount, format.getSampleRate());
        this.stopRampFrames = TrainBrakeSustainRules.stopRampFrames(format.getSampleRate());
    }

    /** 自建控制位（单测/独立使用）；实例播放走带 {@link Control} 的构造器。 */
    public TrainBrakeSustainAudioStream(short[] frames, int channels, AudioFormat format) {
        this(frames, channels, format, new Control());
    }

    @Override
    public AudioFormat getFormat() {
        return this.format;
    }

    public Control control() {
        return this.control;
    }

    /** 当前游标（帧下标），单测用。 */
    public int position() {
        return this.pos;
    }

    public TrainBrakeSustainRules.LoopWindow loopWindow() {
        return this.window;
    }

    public boolean atEnd() {
        return this.eof;
    }

    /** 急停斜坡帧数（48 kHz 下 240），单测用。 */
    public int stopRampFrames() {
        return this.stopRampFrames;
    }

    /** 速度跌破 2 m/s（见 {@link #control()}）。 */
    public void releaseSustain() {
        this.control.releaseSustain();
    }

    /** 松闸 / 车架消失。 */
    public void stopImmediately() {
        this.control.stopImmediately();
    }

    @Override
    public ByteBuffer read(int size) {
        int frameBytes = 2 * this.channels;
        int maxFrames = frameBytes <= 0 ? 0 : size / frameBytes;
        if (this.closed || this.eof || maxFrames <= 0) {
            return emptyBuffer();
        }
        ByteBuffer out = ByteBuffer.allocateDirect(maxFrames * frameBytes).order(ByteOrder.LITTLE_ENDIAN);
        int emitted = 0;
        while (emitted < maxFrames) {
            if (this.control.immediateStopRequested()) {
                if (this.stopRampLeft < 0) {
                    this.stopRampLeft = this.stopRampFrames;
                }
                if (this.stopRampLeft <= 0) {
                    this.eof = true;
                    break;
                }
                double scale = (double) this.stopRampLeft / this.stopRampFrames;
                int frameIndex = Math.min(this.pos, this.frameCount - 1);
                this.emitFrame(out, frameIndex, scale);
                this.stopRampLeft--;
                emitted++;
                if (this.stopRampLeft <= 0) {
                    this.eof = true;
                    break;
                }
                continue;
            }
            if (this.pos >= this.frameCount) {
                this.eof = true;
                break;
            }
            this.emitFrame(out, this.pos, 1.0D);
            this.pos++;
            emitted++;
            if (this.control.sustaining() && this.pos >= this.window.end()) {
                this.pos = this.window.start() + this.window.crossfade();
            }
        }
        if (emitted == 0) {
            return emptyBuffer();
        }
        out.flip();
        return out;
    }

    /**
     * 写出一帧。{@code scale} 为幅度系数（1.0 = 原样，急停斜坡取剩余帧数/总帧数）；
     * 交叉淡化只作用在持续段的窗口尾部，且急停态一律不做淡化。
     */
    private void emitFrame(ByteBuffer out, int frameIndex, double scale) {
        int base = frameIndex * this.channels;
        int fadeFrom = this.window.end() - this.window.crossfade();
        boolean blending = this.control.sustaining()
                && !this.control.immediateStopRequested()
                && this.window.crossfade() > 0
                && frameIndex >= fadeFrom
                && frameIndex < this.window.end();
        for (int channel = 0; channel < this.channels; channel++) {
            double sample = this.frames[base + channel];
            if (blending) {
                int offset = frameIndex - fadeFrom;
                int otherIndex = (this.window.start() + offset) * this.channels + channel;
                double alpha = (double) offset / this.window.crossfade();
                sample = (1.0D - alpha) * sample + alpha * this.frames[otherIndex];
            }
            sample *= scale;
            out.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample))));
        }
    }

    private static ByteBuffer emptyBuffer() {
        return ByteBuffer.allocateDirect(0);
    }

    @Override
    public void close() {
        this.closed = true;
        this.eof = true;
    }

    /** PCM 加载失败时的兜底流：格式合法、读到立即 EOF，避免 {@code getStream} 返回 null。 */
    public static TrainBrakeSustainAudioStream silent(AudioFormat format, Control control) {
        TrainBrakeSustainAudioStream stream =
                new TrainBrakeSustainAudioStream(SILENT_FRAME, 1, format, control);
        stream.close();
        return stream;
    }
}
