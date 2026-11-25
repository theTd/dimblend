package dimblend.experience.mixin.client.compat.coldsweat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.momosoftworks.coldsweat.client.renderer.item.SoulSpringLampRenderer;

/**
 * 3D 模型的燃料换算与三段火焰阈值（客户端）。
 *
 * <p>两处（{@code javap -c} 核实）：</p>
 * <ul>
 *   <li>{@code renderByItem}：{@code CSMath.blend(0.0, 1.0, fuel, 0.0, 64.0)}——发光强度按 64 容量算，
 *       容器改成 256 后同样满燃料只有四分之一亮度；常量改 256。</li>
 *   <li>{@code getTexture}：{@code fuel > 43.0 ? 3 : fuel > 22.0 ? 2 : 1}——三段火焰的阈值按 64
 *       容量的 2/3、1/3 取整；容量 256 后按比例换成 171（{@code 256*2/3}）与 85（{@code 256/3}），
 *       与 2D 物品属性的阈值保持一致。</li>
 * </ul>
 */
@Mixin(value = SoulSpringLampRenderer.class, remap = false)
public abstract class SoulspringLampRendererMixin {

    @ModifyConstant(method = "renderByItem", constant = @Constant(doubleValue = 64.0D),
            remap = false, require = 1, allow = 1)
    private static double dimblend$emissionFullCapacity(double original) {
        return 256.0D;
    }

    @ModifyConstant(method = "getTexture", constant = @Constant(doubleValue = 43.0D),
            remap = false, require = 1, allow = 1)
    private static double dimblend$tierThreeThreshold(double original) {
        return 171.0D;
    }

    @ModifyConstant(method = "getTexture", constant = @Constant(doubleValue = 22.0D),
            remap = false, require = 1, allow = 1)
    private static double dimblend$tierTwoThreshold(double original) {
        return 85.0D;
    }
}
