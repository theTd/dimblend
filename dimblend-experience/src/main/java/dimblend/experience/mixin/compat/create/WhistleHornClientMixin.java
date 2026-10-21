package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlockEntity;
import dimblend.experience.client.TrainHornClient;
import net.createmod.catnip.animation.LerpedFloat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 列车汽笛客户端表现（鸣笛窗口 = {@link TrainHornClient} 按 payload 登记）：
 *
 * <ul>
 * <li><b>张嘴</b>：原生 {@code tick()} 客户端分支里 {@code animation.chase(...)}
 * 紧跟 {@code tickChaser()}；在 tickChaser 前一刻把追逐目标改写成 1（EXP .5，
 * 与原生开启同参），渲染层 {@code WhistleRenderer} 照常读 animation 张嘴——
 * 绕开原生「POWERED 且锅炉激活」判定，本功能补的正是无热源场景。窗口结束
 * 原生 chase(0) 自动接管合嘴，无需收尾。</li>
 * <li><b>音符粒子</b>：{@code tickAudio} HEAD——窗口内每 tick 一颗沿哨口朝向冲出
 * （{@link TrainHornClient#tickHornParticles}）；无热源（powered=false）时 cancel 掉
 * 原方法（此时原方法只做 soundInstance fadeOut，无损失）；有热源时保留原生
 * 汽笛循环与蒸汽粒子，音符叠加其上（拍板口径：不压制 Create 原生行为）。</li>
 * </ul>
 */
@Mixin(WhistleBlockEntity.class)
public abstract class WhistleHornClientMixin {

    @Shadow(remap = false)
    public LerpedFloat animation;

    @Inject(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/createmod/catnip/animation/LerpedFloat;tickChaser()V",
                    remap = false),
            require = 1,
            allow = 1)
    private void dimblend$hornMouthOpen(CallbackInfo ci) {
        WhistleBlockEntity self = (WhistleBlockEntity) (Object) this;
        if (TrainHornClient.isHornActive(self.getBlockPos())) {
            this.animation.chase(1.0, 0.5, LerpedFloat.Chaser.EXP);
        }
    }

    @Inject(method = "tickAudio", at = @At("HEAD"), cancellable = true, remap = false, require = 1, allow = 1)
    private void dimblend$hornParticles(WhistleBlock.WhistleSize size, boolean powered, CallbackInfo ci) {
        WhistleBlockEntity self = (WhistleBlockEntity) (Object) this;
        if (!TrainHornClient.isHornActive(self.getBlockPos())) {
            return;
        }
        TrainHornClient.tickHornParticles(self);
        if (!powered) {
            ci.cancel();
        }
    }
}
