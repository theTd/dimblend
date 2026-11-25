package dimblend.experience.mixin.client.compat.coldsweat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.momosoftworks.coldsweat.client.gui.tooltip.ClientSoulspringTooltip;

import dimblend.experience.compat.coldsweat.SoulspringLampRules;

/**
 * 灵魂之泉灯的燃料条宽度（客户端 tooltip）。
 *
 * <p>{@code renderImage} 里有三处 {@code blit}（同一描述符
 * {@code (Lnet/minecraft/resources/ResourceLocation;IIIFFIIII)V}）：底条 30px、燃料条
 * {@code (int)(fuel / 2.1333)}、高对比度模式的 16px 小图标。参数位：tex=0, x=1, y=2,
 * 偏移=3, u=4, v=5, <b>width=6</b>, height=7, texW=8, texH=9。</p>
 *
 * <p>两步：</p>
 * <ol>
 *   <li>{@code @ModifyConstant} 把分母 {@code 2.1333}（原版 64 容量的 {@code 64/30}）换成
 *       {@code 256/30 = 8.533333}；</li>
 *   <li>{@code @ModifyArg(index = 6)} 把三处宽度统一夹到 {@code [0, 30]}——30 和 16 夹完不变，
 *       只有燃料条会受限。{@code require = 3, allow = 3} 钉死"确实有 3 处 blit"，
 *       原版加了第四处就启动变红，而不是画歪。</li>
 * </ol>
 */
@Mixin(value = ClientSoulspringTooltip.class, remap = false)
public abstract class SoulspringLampTooltipMixin {

    @ModifyConstant(method = "renderImage", constant = @Constant(doubleValue = 2.1333D),
            remap = false, require = 1, allow = 1)
    private static double dimblend$barDivisor(double original) {
        return SoulspringLampRules.TOOLTIP_DIVISOR;
    }

    @ModifyArg(method = "renderImage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;blit(Lnet/minecraft/resources/ResourceLocation;IIIFFIIII)V"),
            index = 6, remap = false, require = 3, allow = 3)
    private static int dimblend$clampBarWidth(int width) {
        return Math.min(SoulspringLampRules.TOOLTIP_MAX_WIDTH, Math.max(0, width));
    }
}
