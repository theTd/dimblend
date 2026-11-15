package dimblend.mixin.voidscape;

import dimblend.compat.VoidscapeUndeadInfusion;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "tamaized.voidscape.block.entity.InfuserBlockEntity", remap = false)
public abstract class InfuserBlockEntityMixin {
    @Inject(method = "tick", at = @At("HEAD"), require = 1, allow = 1)
    private static void dimblend$remoteUndead(
            Level level, BlockPos pos, BlockState state, BlockEntity be, CallbackInfo ci) {
        VoidscapeUndeadInfusion.onInfuserTickHead(level, pos, be);
    }

    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/atomic/AtomicBoolean;get()Z"),
            require = 1,
            allow = 1)
    private static boolean dimblend$remoteCountsAsProcess(AtomicBoolean process) {
        return VoidscapeUndeadInfusion.mergeProcessResult(process);
    }
}
