package dimblend.radio.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dimblend.radio.client.RadioAudibility;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.MusicManager;

/**
 * 电台可闻时压住 vanilla 背景音乐（原版唱片机同款行为），真零点之外放行。
 *
 * <p>防 delay 膨胀：{@code stopPlaying()} 每次 +100 tick，无脑每 tick 调会把
 * nextSongDelay 顶到天上、电台停后半天没音乐。只在切出可闻（上次不可闻→这次可闻）
 * 或正在播音乐时调一次；持续可闻期间不再碰 delay。</p>
 */
@Mixin(MusicManager.class)
public abstract class MusicDuckingMixin {
    @Shadow
    private SoundInstance currentMusic;

    @Shadow
    public abstract void stopPlaying();

    private boolean dimblend$wasAudible;

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void dimblend$duckWhileRadioAudible(CallbackInfo ci) {
        boolean audible = RadioAudibility.anyAudible();
        try {
            if (audible && !this.dimblend$wasAudible) {
                // 切入可闻：停一次（delay +100，自然衔接）
                this.stopPlaying();
            } else if (audible && this.currentMusic != null) {
                // 边界：stop 后新音乐又抢播起来（如换维度），再停一次
                this.stopPlaying();
            }
        } finally {
            this.dimblend$wasAudible = audible;
        }
    }
}
