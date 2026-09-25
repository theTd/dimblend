package dimblend.mixin.voidscape;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.compat.VoidscapeBand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "tamaized.voidscape.event.VoidicCrystalOreGenerator", remap = false)
public abstract class VoidicCrystalOreGeneratorMixin {
    @WrapOperation(
            method = "*",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"
            )
    )
    private boolean dimblend$keepOreInVoidscapeLane(
            Level level,
            BlockPos pos,
            BlockState state,
            Operation<Boolean> original
    ) {
        if (VoidscapeBand.isRotating(level) && !VoidscapeBand.isVoidscapePos(level, pos)) {
            return false;
        }
        return original.call(level, pos, state);
    }
}
