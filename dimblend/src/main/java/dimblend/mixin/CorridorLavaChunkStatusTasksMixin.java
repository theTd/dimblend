package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.CorridorLavaSources;
import dimblend.worldgen.RotatingChunkGenerator;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkStatusTasks.class)
public abstract class CorridorLavaChunkStatusTasksMixin {
    @Inject(method = "initializeLight", at = @At("HEAD"), require = 1, allow = 1)
    private static void dimblend$finishLavaBeforeLighting(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (context.level().dimension() == DimBlendRegistries.ROTATING_LEVEL
                && context.generator() instanceof RotatingChunkGenerator rotating
                && chunk.getPersistedStatus().isBefore(ChunkStatus.INITIALIZE_LIGHT)
                && CorridorLavaSources.isNewProto(chunk)) {
            if (step.directDependencies().size() <= 2
                    || step.directDependencies().get(2).isBefore(ChunkStatus.FEATURES)) {
                throw new IllegalStateException("Corridor lava finalization requires FEATURES radius 2; register CorridorLavaChunkPyramidMixin");
            }
            CorridorLavaSources.finish(new WorldGenRegion(context.level(), cache, step, chunk), chunk, rotating);
        }
    }
}
