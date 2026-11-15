package dimblend.mixin.voidscape;

import dimblend.compat.VoidscapeCollectorCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "tamaized.voidscape.block.entity.CollectorBlockEntity", remap = false)
public abstract class CollectorBlockEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void dimblend$copyBelow(
            Level level, BlockPos pos, BlockState state, BlockEntity be, CallbackInfo ci) {
        VoidscapeCollectorCopy.tick(level, pos, be);
        ci.cancel();
    }
}
