package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Names NeoForge's per-level tick events in the server profiler. Without this every mod's
 * {@code LevelTickEvent} listener lands in the level's {@code tick} section as unspecified time
 * (most of the slow-tick time in the 2026-10-12 recording). Pushing a section on an inactive
 * profiler is a no-op, so this costs nothing outside profiling.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerLevelTickEventSectionMixin {
    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;fireLevelTickPre(Lnet/minecraft/world/level/Level;Ljava/util/function/BooleanSupplier;)V"))
    private void dimblend$sectionLevelTickPre(Level level, BooleanSupplier haveTime, Operation<Void> original) {
        ProfilerFiller profiler = ((MinecraftServer) (Object) this).getProfiler();
        profiler.push("neoforge:levelTickEvent.pre");
        try {
            original.call(level, haveTime);
        } finally {
            profiler.pop();
        }
    }

    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/event/EventHooks;fireLevelTickPost(Lnet/minecraft/world/level/Level;Ljava/util/function/BooleanSupplier;)V"))
    private void dimblend$sectionLevelTickPost(Level level, BooleanSupplier haveTime, Operation<Void> original) {
        ProfilerFiller profiler = ((MinecraftServer) (Object) this).getProfiler();
        profiler.push("neoforge:levelTickEvent.post");
        try {
            original.call(level, haveTime);
        } finally {
            profiler.pop();
        }
    }
}
