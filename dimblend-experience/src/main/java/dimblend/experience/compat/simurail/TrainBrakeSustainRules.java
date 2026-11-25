package dimblend.experience.compat.simurail;

/**
 * 刹车气阀「持续鸣响」口径（2026-11-25 LOCKED #4 {@code brake_audio_sustain}）。
 *
 * <p>用户口径：制动速度高于 2 m/s 时持续鸣响（75% 音量循环），速度降到 2 m/s 以下
 * 淡出（1 秒内收干净），松闸时立刻停。</p>
 *
 * <p><b>2026-11-25 Slice 4B 实现</b>：NeoForge 21.1.249 的
 * {@code net.minecraft.client.resources.sounds.SoundInstance} 原生声明了
 * {@code default CompletableFuture<AudioStream> getStream(SoundBufferLibrary, Sound, boolean)}，
 * 而 {@code SoundEngine#play} 在 {@code Sound#shouldStream()} 为真时直接调它。于是
 * 「持续鸣响」不再靠定长重触发：客户端把 {@code train_brake.ogg} 解码成
 * 48 kHz / 16-bit 单声道 PCM 缓存（{@code 239823} 帧，约 {@code 4.9963s}），
 * 在 75% 位置的稳定摩擦音节上做 {@code [3.56s, 3.92s]} 窗口的交叉淡化无缝循环，
 * 跌破 2 m/s 时放开游标、让素材自带的最后 25% 自然衰减，松闸则 5 ms 归零后立刻结束流。
 * 具体采样点算术见 {@code TrainBrakeSustainAudioStream}。</p>
 *
 * <p>本类只放<b>可单测的口径常数</b>（纯 JDK，无 Minecraft 类型），
 * PCM 流、客户端实例、网络包各自持有自己的实现。</p>
 */
public final class TrainBrakeSustainRules {

    /** 施闸鸣响音量（2026-11-25 口径：75%）。 */
    public static final float SUSTAIN_VOLUME = 0.75F;

    /** 低于该速度（m/s）即进入淡出；同时是持续段的起播门。 */
    public static final double FADE_BELOW_SPEED = 2.0D;

    /**
     * 循环窗口中心：素材 75% 处（{@code 3.7472s} / 第 {@code 179867} 帧）。
     * 该处是 RMS 稳定的金属摩擦音节，最适合做无缝循环。
     */
    public static final double LOOP_CENTER_FRACTION = 0.75D;
    /** 循环窗口起点：{@code 3.56s} ≈ 全长的 71.25%。 */
    public static final double LOOP_START_FRACTION = 0.7125D;
    /** 循环窗口终点：{@code 3.92s} ≈ 全长的 78.45%；此后是素材自带的 25% 衰减尾音。 */
    public static final double LOOP_END_FRACTION = 0.7845D;

    /**
     * 交叉淡化时长：30 ms。48 kHz 下正好 {@code 1440} 帧，与
     * {@code report-w1.md §8.3} 的窗口算术一致；换采样率时按真实采样率换算。
     */
    public static final double CROSSFADE_SECONDS = 0.030D;

    /** 松闸急停的归零斜坡：5 ms。48 kHz 下 {@code 240} 帧。 */
    public static final double STOP_RAMP_SECONDS = 0.005D;

    /** 速度跌破 2 m/s 后的整段音量淡出：20 tick = 1.0s。 */
    public static final int FADE_OUT_TICKS = 20;

    /**
     * 持续段的「保活重发」间隔：每 40 tick（2s）给附近玩家补发一次开始包。
     * 客户端对已在响的同一车架只刷新音量/音调、不重起循环，因此这是幂等的；
     * 用来覆盖中途进服的玩家、丢包、以及玩家走出/走进 256 格半径的情况。
     */
    public static final int START_REFRESH_TICKS = 40;

    /**
     * 旧「定长重触发」补播间隔（{@code 100 tick = 5.00s}，对齐
     * {@code 239823/48000 ≈ 4.996s} 的采样长度）。
     *
     * <p>Slice 4B 的 PCM 持续流已取代重触发方案；本常量只为保留
     * {@code TrainBrakeHoldAndSustainRulesTest} 的既有契约，运行期不再使用。</p>
     */
    public static final int REPLAY_INTERVAL_TICKS = 100;

    /** 是否应当处于「持续鸣响」态（施闸中、未跌破 2 m/s、音效开关打开）。 */
    public static boolean shouldSustain(double brakeStrength, double speed, boolean soundsEnabled) {
        return soundsEnabled && brakeStrength > 1.0E-6D && speed >= FADE_BELOW_SPEED;
    }

    /** 速度跌破 2 m/s 即进入淡出段（与 {@link #shouldSustain} 的边界互为补集）。 */
    public static boolean shouldFadeOut(double speed) {
        return speed < FADE_BELOW_SPEED;
    }

    /** 循环窗口（帧下标，含头不含尾）。{@code frames} 为采样帧数（单声道即采样点数）。 */
    public record LoopWindow(int start, int end, int crossfade) {
    }

    /**
     * 由素材帧数与采样率推出循环窗口。窗口在 {@code [start, end)} 内循环，
     * 尾部 {@code crossfade} 帧与 {@code [start, start+crossfade)} 线性交叉淡化后回绕。
     */
    public static LoopWindow loopWindow(int frames, float sampleRate) {
        int start = Math.max(0, (int) (frames * LOOP_START_FRACTION));
        int end = Math.min(frames, (int) (frames * LOOP_END_FRACTION));
        if (end <= start) {
            // 极端短素材：退化成整段循环，至少保证 start < end
            return new LoopWindow(0, Math.max(1, frames), 0);
        }
        int wanted = Math.max(1, (int) Math.round(sampleRate * CROSSFADE_SECONDS));
        int crossfade = Math.min(wanted, (end - start) / 4);
        return new LoopWindow(start, end, crossfade);
    }

    /** 松闸急停斜坡帧数（48 kHz 下 {@code 240}）。 */
    public static int stopRampFrames(float sampleRate) {
        return Math.max(1, (int) Math.round(sampleRate * STOP_RAMP_SECONDS));
    }

    /** 下一次保活重发的服务端 tick。 */
    public static long nextRefreshTick(long nowTick) {
        return nowTick + START_REFRESH_TICKS;
    }

    /** 旧的补播间隔读数（见 {@link #REPLAY_INTERVAL_TICKS}，运行期不再使用）。 */
    public static int replayIntervalTicks() {
        return REPLAY_INTERVAL_TICKS;
    }

    private TrainBrakeSustainRules() {
    }
}
