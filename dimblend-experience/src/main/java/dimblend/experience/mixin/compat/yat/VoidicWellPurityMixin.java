package dimblend.experience.mixin.compat.yat;

import dimblend.experience.compat.yat.VoidicWellPurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Voidscape's Voidic Well produces water for adjacent fluid handlers. Both the well's own tank
 * and neighbour fills are tagged with {@code Purity: 0} before the handler sees them, and
 * cauldrons that the well actually filled are marked dirty afterwards.
 *
 * <p>{@code @Pseudo}: Voidscape is not on a public Maven, so this mixin only references vanilla,
 * NeoForge and Yet Another Thirst types (the plugin skips it when either mod is absent).</p>
 */
@Pseudo
@Mixin(targets = "tamaized.voidscape.block.entity.WellBlockEntity", remap = false)
public abstract class VoidicWellPurityMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At("HEAD"), remap = false, require = 1, allow = 1)
    private static void dimblend$captureCauldronMask(Level level, BlockPos pos, BlockState state,
            BlockEntity be, CallbackInfo ci) {
        VoidicWellPurityRules.capturePreTickCauldrons(level, pos);
    }

    @ModifyArg(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;fill(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)I"),
            index = 0, remap = false, require = 1, allow = 1)
    private static FluidStack dimblend$dirtyTankWater(FluidStack resource) {
        return VoidicWellPurityRules.markDirtyWater(resource);
    }

    @ModifyArg(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/fluids/capability/IFluidHandler;fill(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)I"),
            index = 0, remap = false, require = 1, allow = 1)
    private static FluidStack dimblend$dirtyNeighbourWater(FluidStack resource) {
        return VoidicWellPurityRules.markDirtyWater(resource);
    }

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At("RETURN"), remap = false)
    private static void dimblend$applyDirtyWater(Level level, BlockPos pos, BlockState state,
            BlockEntity be, CallbackInfo ci) {
        VoidicWellPurityRules.finishPostTick(level, pos);
    }
}
