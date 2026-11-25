package dimblend.experience.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import dimblend.experience.compat.hopper.VanillaHopperSpeed;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * 原版漏斗 ×4（LOCKED DECISION #13）：{@code MOVE_ITEM_SPEED = 8} 被 javac 内联进两个方法，
 * 各只有一处字面量 {@code 8}（见 {@link VanillaHopperSpeed} 的字节码说明）——
 * {@code tryMoveItems} 的 {@code setCooldown(8)} 与 {@code tryMoveInItem} 的 {@code setCooldown(8 - k)}。
 *
 * <p>{@code @ModifyConstant} 各 {@code require = 1, allow = 1}：多出来的一处说明原版改了写法，
 * 宁可启动即红也不要静默半速。{@code isOnCustomCooldown} 的 {@code > 8} 不动（不是节律）。</p>
 */
@Mixin(HopperBlockEntity.class)
public abstract class HopperBlockEntitySpeedMixin {

    @ModifyConstant(
            method = "tryMoveItems(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"
                    + "Lnet/minecraft/world/level/block/entity/HopperBlockEntity;Ljava/util/function/BooleanSupplier;)Z",
            constant = @Constant(intValue = VanillaHopperSpeed.VANILLA_COOLDOWN_TICKS),
            require = 1, allow = 1)
    private static int dimblend$fasterPushCooldown(int original) {
        return VanillaHopperSpeed.COOLDOWN_TICKS;
    }

    @ModifyConstant(
            method = "tryMoveInItem(Lnet/minecraft/world/Container;Lnet/minecraft/world/Container;"
                    + "Lnet/minecraft/world/item/ItemStack;ILnet/minecraft/core/Direction;)"
                    + "Lnet/minecraft/world/item/ItemStack;",
            constant = @Constant(intValue = VanillaHopperSpeed.VANILLA_COOLDOWN_TICKS),
            require = 1, allow = 1)
    private static int dimblend$fasterIncomingCooldown(int original) {
        return VanillaHopperSpeed.COOLDOWN_TICKS;
    }
}
