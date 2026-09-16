package dimblend.mixin.voidscape;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.compat.VoidscapeBand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "tamaized.voidscape.block.entity.GerminatorBlockEntity", remap = false)
public abstract class GerminatorBlockEntityMixin {
    @WrapMethod(method = "tick")
    private static void dimblend$voidscapeLane(
            Level level,
            BlockPos blockPos,
            BlockState blockState,
            BlockEntity be,
            Operation<Void> original
    ) {
        VoidscapeBand.run(level, blockPos, () -> original.call(level, blockPos, blockState, be));
    }
}
