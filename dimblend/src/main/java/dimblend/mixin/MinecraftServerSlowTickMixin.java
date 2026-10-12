package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dimblend.diagnostics.SlowTickRecorder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.SingleTickProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks {@link SlowTickRecorder} where vanilla builds each tick's profiler: the spot meant for
 * {@code SingleTickProfiler}, whose factory returns null in release builds. A no-op unless a
 * {@code /dimblend slowticks start} session is running.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerSlowTickMixin {
    @WrapOperation(method = "startMetricsRecordingTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/profiling/SingleTickProfiler;decorateFiller(Lnet/minecraft/util/profiling/ProfilerFiller;Lnet/minecraft/util/profiling/SingleTickProfiler;)Lnet/minecraft/util/profiling/ProfilerFiller;"))
    private ProfilerFiller dimblend$recordSlowTicks(ProfilerFiller filler, SingleTickProfiler single,
                                                    Operation<ProfilerFiller> original) {
        return SlowTickRecorder.decorate(original.call(filler, single));
    }

    @Inject(method = "endMetricsRecordingTick", at = @At("RETURN"))
    private void dimblend$finishSlowTick(CallbackInfo ci) {
        SlowTickRecorder.endTick();
    }
}
