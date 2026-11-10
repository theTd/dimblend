package dimblend.mixin;

import dimblend.worldgen.CorridorLavaSources;
import java.util.function.UnaryOperator;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** The ninth step call belongs only to GENERATION_PYRAMID; LOADING_PYRAMID is untouched. */
@Mixin(ChunkPyramid.class)
public abstract class CorridorLavaChunkPyramidMixin {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/status/ChunkPyramid$Builder;step(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Ljava/util/function/UnaryOperator;)Lnet/minecraft/world/level/chunk/status/ChunkPyramid$Builder;",
            ordinal = 8), index = 1, require = 1, allow = 1)
    private static UnaryOperator<ChunkStep.Builder> dimblend$waitForLavaSourceHalo(
            UnaryOperator<ChunkStep.Builder> original) {
        return CorridorLavaSources.finalizationDependencies(original);
    }
}
