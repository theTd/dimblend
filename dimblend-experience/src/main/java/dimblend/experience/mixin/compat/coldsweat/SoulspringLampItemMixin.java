package dimblend.experience.mixin.compat.coldsweat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import com.momosoftworks.coldsweat.common.item.SoulspringLampItem;

import com.llamalad7.mixinextras.sugar.Local;
import dimblend.experience.compat.coldsweat.SoulspringLampRules;
import dimblend.experience.exploration.RotatingDimension;
import net.minecraft.world.level.Level;

/**
 * 灵魂之泉灯：硬编码的 64 / 64.0 容量上限全部抬到 256 / 256.0，并且把维度门<b>加性</b>放开到
 * 旋转维度（LOCKED DECISION #15）。
 *
 * <p>{@code javap -c}/{@code -p} 核实的常量分布（每处都注明 {@code require}，多一处就让启动变红）：</p>
 * <ul>
 *   <li>{@code getDefaultInstance} —— {@code setFuel(stack, 64.0)}，默认实例满燃料；</li>
 *   <li>{@code addFuel(ItemStack, double)} —— {@code Math.min(64.0, ...)}，加燃料的上限；</li>
 *   <li>{@code onEntityHit} —— {@code getFuel(stack) < 64.0}，抽取生物热量的门槛；</li>
 *   <li>{@code appendHoverText} —— 字面量 {@code " / 64"}，悬浮提示；</li>
 *   <li>{@code overrideOtherStackedOnMe} —— 两处 {@code 64.0}（比较 + {@code (64.0 - currentFuel)} 的扣减量）。</li>
 * </ul>
 *
 * <p>不做"整数值 64"的猜测：{@code addFuel} 的 {@code (int)(...)} 截断是实参算出来的，
 * 上面这些全是 {@code double} 常量或字符串，所以 {@code @Constant(doubleValue = 64.0)} 不会误伤别的数值。</p>
 *
 * <p>维度门：{@code inventoryTick} 里唯一的 {@code List#contains} 就是
 * {@code ConfigSettings.LAMP_DIMENSIONS.get(...).contains(dimensionTypeRegistration())}；
 * 这里只做 {@code original || RotatingDimension.is(level)}——用户自己的 "Valid Dimensions" 配置
 * 一格都不改，旋转维度额外放行。</p>
 */
@Mixin(value = SoulspringLampItem.class, remap = false)
public abstract class SoulspringLampItemMixin {

    @ModifyConstant(method = "getDefaultInstance", constant = @Constant(doubleValue = 64.0D),
            remap = false, require = 1, allow = 1)
    private static double dimblend$defaultInstanceCapacity(double original) {
        return SoulspringLampRules.CAPACITY;
    }

    @ModifyConstant(method = "addFuel", constant = @Constant(doubleValue = 64.0D),
            remap = false, require = 1, allow = 1)
    private static double dimblend$addFuelCap(double original) {
        return SoulspringLampRules.CAPACITY;
    }

    @ModifyConstant(method = "onEntityHit", constant = @Constant(doubleValue = 64.0D),
            remap = false, require = 1, allow = 1)
    private static double dimblend$entityHitThreshold(double original) {
        return SoulspringLampRules.CAPACITY;
    }

    @ModifyConstant(method = "appendHoverText", constant = @Constant(stringValue = " / 64"),
            remap = false, require = 1, allow = 1)
    private static String dimblend$hoverCapacityLabel(String original) {
        return " / 256";
    }

    @ModifyConstant(method = "overrideOtherStackedOnMe", constant = @Constant(doubleValue = 64.0D),
            remap = false, require = 2, allow = 2)
    private static double dimblend$refuelCap(double original) {
        return SoulspringLampRules.CAPACITY;
    }

    @ModifyExpressionValue(
            method = "inventoryTick",
            at = @At(value = "INVOKE", target = "Ljava/util/List;contains(Ljava/lang/Object;)Z"),
            remap = false, require = 1, allow = 1)
    private static boolean dimblend$allowRotatingDimension(boolean original, @Local(argsOnly = true) Level level) {
        return original || RotatingDimension.is(level);
    }
}
