package dimblend.mixin.twilight;

import dimblend.worldgen.YShiftScope;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import twilightforest.world.components.structures.minotaurmaze.MazeEntranceShaftComponent;

@Mixin(value = MazeEntranceShaftComponent.class, remap = false)
public abstract class MazeEntranceShaftMixin {
    @Redirect(
            method = "<init>(ILnet/minecraft/util/RandomSource;III)V",
            at = @At(value = "NEW", target = "(III)Lnet/minecraft/core/BlockPos;", remap = true)
    )
    private static BlockPos dimblend$shiftedShaftFloor(int x, int y, int z) {
        return new BlockPos(x, y + YShiftScope.current(), z);
    }
}
