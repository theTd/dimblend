package dimblend.experience.mixin.compat.dragonsplus;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour;
import dimblend.experience.compat.dragonsplus.FluidHatchRules;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 限制 {@code create_dragons_plus:fluid_hatch} 的单向交互方向。
 *
 * <p>被拦截的方向返回 {@link FluidStack#EMPTY}；上游 {@code useItemOn} 在手持流体容器时仍返回
 * {@code ItemInteractionResult.SUCCESS}，避免手持水桶点击舱口时误把水倒在舱口上。</p>
 */
@Pseudo
@Mixin(targets = "plus.dragons.createdragonsplus.common.fluids.hatch.FluidHatchBlock", remap = false)
public abstract class FluidHatchDirectionMixin {

    @WrapMethod(
            method = "tryEmptyItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;"
                    + "Lnet/neoforged/neoforge/fluids/capability/IFluidHandler;"
                    + "Lcom/simibubi/create/foundation/blockEntity/behaviour/filtering/FilteringBehaviour;)"
                    + "Lnet/neoforged/neoforge/fluids/FluidStack;",
            require = 1,
            allow = 1
    )
    private FluidStack dimblend$guardDeposit(
            Level level,
            Player player,
            InteractionHand hand,
            ItemStack stack,
            BlockEntity blockEntity,
            IFluidHandler tank,
            FilteringBehaviour filter,
            Operation<FluidStack> original
    ) {
        if (!FluidHatchRules.allowsDeposit()) {
            return FluidStack.EMPTY;
        }
        return original.call(level, player, hand, stack, blockEntity, tank, filter);
    }

    @WrapMethod(
            method = "tryFillItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;"
                    + "Lnet/minecraft/world/level/block/entity/BlockEntity;"
                    + "Lnet/neoforged/neoforge/fluids/capability/IFluidHandler;"
                    + "Lcom/simibubi/create/foundation/blockEntity/behaviour/filtering/FilteringBehaviour;)"
                    + "Lnet/neoforged/neoforge/fluids/FluidStack;",
            require = 1,
            allow = 1
    )
    private FluidStack dimblend$guardExtract(
            Level level,
            Player player,
            InteractionHand hand,
            ItemStack stack,
            BlockEntity blockEntity,
            IFluidHandler tank,
            FilteringBehaviour filter,
            Operation<FluidStack> original
    ) {
        if (!FluidHatchRules.allowsExtract()) {
            return FluidStack.EMPTY;
        }
        return original.call(level, player, hand, stack, blockEntity, tank, filter);
    }
}
