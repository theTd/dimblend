package dimblend.radio.mixin.client;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.client.RadioAudibility;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;

/**
 * 电台可闻时压住 vanilla 背景音乐（原版唱片机同款行为），真零点之外放行。
 *
 * <p>实现：可闻期间停掉在播音乐、把 {@code nextSongDelay} 固定重置为 100 tick
 * （vanilla STARTING_DELAY）并整体取消本次 vanilla tick——delay 不倒数，vanilla
 * 不会起播；电台停后从 100 倒数，约 5 秒自然恢复。</p>
 *
 * <p>两个坑缺一不可避：不能用 {@code stopPlaying()}——它每次 {@code +100}，而 vanilla
 * {@code startPlaying} 会把 delay 置为 {@code Integer.MAX_VALUE}，再 +100 溢出成负数，
 * 之后 vanilla tick 的 {@code nextSongDelay-- <= 0} 恒真，每 tick 起播一次又被停一次
 * （SoundEngine 每 tick 开关一条流式音乐通道）；也不能只 pin 不 cancel——CREDITS/END_BOSS
 * 情景音乐的 min/maxDelay 均为 0（Musics.java），vanilla tick 里的 {@code min(100, 0)}
 * 会把 pin 值钳成 0，不取消方法体则该边角仍会每 tick 起播。</p>
 */
@Mixin(MusicManager.class)
public abstract class MusicDuckingMixin {
    @Shadow
    private SoundInstance currentMusic;

    @Shadow
    private int nextSongDelay;

    @Shadow
    @Final
    private Minecraft minecraft;

    private boolean dimblend$wasAudible;

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void dimblend$duckWhileRadioAudible(CallbackInfo ci) {
        boolean audible = RadioAudibility.anyAudible();
        if (audible) {
            if (this.currentMusic != null) {
                this.minecraft.getSoundManager().stop(this.currentMusic);
                this.currentMusic = null;
            }
            this.nextSongDelay = 100;
            ci.cancel();
        }
        if (audible != this.dimblend$wasAudible) {
            DimBlendRadio.LOGGER.info("[radio] bgm ducking {}", audible ? "on" : "off");
        }
        this.dimblend$wasAudible = audible;
    }
}
