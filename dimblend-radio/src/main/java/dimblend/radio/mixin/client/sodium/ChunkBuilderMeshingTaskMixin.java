package dimblend.radio.mixin.client.sodium;

import dimblend.radio.compat.sodium.SodiumGeometrySink;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tees finished chunk meshes into the acoustic geometry mirror. At RETURN the vertex buffers are
 * still alive (Sodium frees them only after the render-thread upload), and the task's LevelSlice
 * snapshot is still owned by this worker thread, so block lookups for material classification are
 * safe. Cancelled tasks (null result) carry no geometry.
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class ChunkBuilderMeshingTaskMixin {
    @Inject(method = "execute", at = @At("RETURN"))
    private void dimblend$teeAcousticGeometry(ChunkBuildContext buildContext, CancellationToken cancellationToken,
            CallbackInfoReturnable<ChunkBuildOutput> cir) {
        ChunkBuildOutput output = cir.getReturnValue();
        if (output != null) {
            SodiumGeometrySink.onSectionMeshBuilt(output, buildContext.cache.getWorldSlice());
        }
    }
}
