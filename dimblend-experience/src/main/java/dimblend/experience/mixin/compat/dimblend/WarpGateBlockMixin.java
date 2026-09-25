package dimblend.experience.mixin.compat.dimblend;

import dimblend.experience.Config;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * G4 折跃门传送功能掐断（dimblend 目标，字符串引用、无编译依赖，
 * 缺席时由 {@code DimBlendMixinPlugin} 按 mod id 过滤）。
 *
 * <p>字节码事实（dimblend 0.1.1 反汇编）：{@code WarpGateBlock#entityInside}
 * 本体仅为 {@code return}（连 super 都不调，原版末地折跃门传送已死）；
 * 服务端 ticker 为原版 {@code portalTick}（仅冷却/年龄，不传送）；
 * 全 jar 无 {@code changeDimension}/{@code travelToDimension}/
 * {@code setAsInsidePortal} 调用（仅指令与暮色混合器用同维度 teleportTo）。
 * 即 0.1.1 的折跃门<b>本来就没有可用传送</b>，本次取消是面向未来版本的保险：
 * dimblend 若恢复方块驱动传送（调 super 或自定义），HEAD 取消一律掐断；
 * 若走 {@code changeDimension} 跨维度路径，rotating 出发的由 {@code PortalBan}
 * 旅行禁令兜底。任意维度生效，留块，只禁传。开关 {@code portalBan}。</p>
 */
@Mixin(targets = "dimblend.block.WarpGateBlock", remap = false)
public abstract class WarpGateBlockMixin {

    @Inject(method = "entityInside(Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/world/level/Level;"
            + "Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void dimblend$banFoldTravel(CallbackInfo ci) {
        if (Config.PORTAL_BAN.get()) {
            ci.cancel();
        }
    }
}
