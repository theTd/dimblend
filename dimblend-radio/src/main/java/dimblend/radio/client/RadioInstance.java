package dimblend.radio.client;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 电台播放实例：自定义 TickableSoundInstance，resolve() 自带事件（registry 旁路）。
 *
 * <p>location 取合成事件 RL（{@code dimblend_radio:radio/<hash>}），
 * {@link #resolve} 直接置 sound 并返回自建 {@link WeighedSoundEvents}，
 * {@code SoundEngine.play} 只用 resolve() 结果，registry 无 key 也能播。
 * event 内 Sound 的 getPath() = {@code sounds/radio/<hash>.ogg}，
 * {@code SoundBufferMixin} 按此 path 从 {@link RadioPcmFeed} 取 PCM 分流，
 * 全程不碰 registry、不 reload。</p>
 *
 * <p>volume 固定 1.0：f1 = max(1.0,1.0) * 64 = 64（真零点），
 * LINEAR_DISTANCE 下 gain = 1 - d/64：贴脸满音、32 格半音、64 格归零。
 * side 音量（10%~150%）：PCM 解码时按本曲余量线性预放大（{@link PcmHeadroom}），实例通道增益
 * 实时跟量，变音量（含跨 100%）不换 PCM、不重建。</p>
 */
public class RadioInstance extends AbstractTickableSoundInstance {
    /** 声音分类：唱片机/音符盒（RECORDS），跟随该音量滑块。 */
    public static final SoundSource SOURCE = SoundSource.RECORDS;

    private final BlockPos radioPos;
    private final WeighedSoundEvents event;
    private volatile float gain = 1.0f;

    public RadioInstance(BlockPos pos, String trackHash) {
        this(pos, trackHash, 1.0f);
    }

    /**
     * @param gain 通道增益（0~1，= {@link PcmHeadroom#channelGain}，对按余量预放大的 PCM 实时跟量）。
     *             半径 f1 = max(volume,1.0) * attDist 与 gain 无关：volume 恒 1.0，
     *             f1 = 64 真零点不受 side 影响。
     */
    public RadioInstance(BlockPos pos, String trackHash, float gain) {
        super(SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(
                dimblend.radio.DimBlendRadio.MODID, "radio/" + trackHash)),
                SOURCE, SoundInstance.createUnseededRandom());
        this.radioPos = pos.immutable();
        Vec3 center = Vec3.atCenterOf(pos);
        this.x = center.x;
        this.y = center.y;
        this.z = center.z;
        this.attenuation = SoundInstance.Attenuation.LINEAR;
        this.relative = false;
        this.event = RadioInjector.makeEvent(trackHash);
        // volume 恒 1.0 保住 f1=64 真零点；可闻响度走 gain（引擎 clamp 到 1.0 内线性）。
        this.volume = 1.0f;
        this.gain = gain;
    }

    public BlockPos pos() {
        return this.radioPos;
    }

    public WeighedSoundEvents event() {
        return this.event;
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager manager) {
        this.sound = this.event.getSound(this.random);
        return this.event;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    /** 实时跟量：只改 gain（volume 恒 1.0 不动半径），引擎下 tick 即生效。 */
    public void setVolume(float gain) {
        this.gain = gain;
    }

    @Override
    public float getVolume() {
        return this.gain;
    }

    @Override
    public void tick() {
        // gain 由 RadioController.followSideGain 实时写，不在这里动；
        // 距离衰减由引擎每 tick 用 x/y/z 重算
    }
}
