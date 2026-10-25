package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.decoration.steamWhistle.WhistleBlock;
import com.simibubi.create.content.decoration.steamWhistle.WhistleBlockEntity;
import dimblend.experience.client.TrainHornClient;
import dimblend.experience.compat.create.TrainHorn;
import dimblend.experience.compat.create.TrainHornStateAccess;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 列车汽笛客户端表现（鸣笛窗口 = {@link TrainHornClient} 按 payload 登记）：
 *
 * <ul>
 * <li><b>张嘴</b>：原生 {@code tick()} 客户端分支里 {@code animation.chase(...)}
 * 紧跟 {@code tickChaser()}；在 tickChaser 前一刻把追逐目标改写成 1（EXP .5，
 * 与原生开启同参），渲染层 {@code WhistleRenderer} 照常读 animation 张嘴——
 * 绕开原生「POWERED 且锅炉激活」判定，本功能补的正是无热源场景。窗口结束
 * 原生 chase(0) 自动接管合嘴，无需收尾。</li>
 * <li><b>特殊音频接管</b>：{@code tickAudio} HEAD——原始 POWERED 且邻接引擎时
 * 无条件 cancel 原生方法，特殊供电期间不发原生循环声、chiff 或蒸汽粒子；自定义
 * 窗口内每 tick 一颗音符沿哨口朝向冲出。断电由 {@link TrainHornClient} 对账淡出，
 * 不依赖仅表示「红石 + 有效锅炉」的原生 powered 参数。</li>
 * <li><b>护目镜</b>（2026-10-26 新增）：特殊模式下列车汽笛在 Create 自带
 * {@code generic.pitch}（延长管音阶）行之后追加两行——「当前处于特殊工作模式
 * 『列车汽笛』」与「<种类>：音高=<v>」。音高读 {@link TrainHornStateAccess} 的
 * BE 同步字段（服务端权威，无本地重算）；种类在客户端按与服务端同一套六面邻接
 * 判定取（{@link TrainHorn#kindAt}），两端规则同源。</li>
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
    private void dimblend$specialHornAudio(WhistleBlock.WhistleSize size, boolean powered, CallbackInfo ci) {
        WhistleBlockEntity self = (WhistleBlockEntity) (Object) this;
        if (TrainHornClient.tickSpecialMode(self)) {
            ci.cancel();
        }
    }

    @Inject(method = "addToGoggleTooltip(Ljava/util/List;Z)Z", at = @At("RETURN"), require = 1, allow = 1)
    private void dimblend$appendSpecialModeHud(List<Component> tooltip, boolean isPlayerSneaking,
            CallbackInfoReturnable<Boolean> cir) {
        WhistleBlockEntity self = (WhistleBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }
        BlockState state = self.getBlockState();
        if (!(state.getBlock() instanceof WhistleBlock)) {
            return;
        }
        TrainHornStateAccess access = (TrainHornStateAccess) (Object) this;
        if (!access.dimblend$specialMode()) {
            return;
        }
        TrainHorn.HornKind kind = TrainHorn.kindAt(level, self.getBlockPos(), state);
        if (kind == null) {
            // 标志刚失效（引擎已拆、包还没到）：宁可不显示，也不报错的种类
            return;
        }
        tooltip.add(Component.translatable(TrainHorn.HUD_SPECIAL_MODE));
        tooltip.add(Component.translatable(TrainHorn.HUD_PITCH,
                Component.translatable(kind.hudKey()), TrainHorn.pitchText(access.dimblend$pitchTenths())));
        if (!cir.getReturnValue()) {
            cir.setReturnValue(true);
        }
    }
}
