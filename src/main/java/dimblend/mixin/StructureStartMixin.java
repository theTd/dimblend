package dimblend.mixin;

import dimblend.compat.TwilightBand;
import dimblend.worldgen.RotatingChunkGenerator;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(StructureStart.class)
public abstract class StructureStartMixin {
    @ModifyVariable(method = "placeInChunk", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private ChunkGenerator dimblend$useBandDelegate(
            ChunkGenerator generator,
            WorldGenLevel level,
            StructureManager structures,
            ChunkGenerator ignored,
            RandomSource random,
            BoundingBox box,
            ChunkPos chunkPos
    ) {
        if (TwilightBand.isRotating(level) && generator instanceof RotatingChunkGenerator rotating) {
            return rotating.delegateForBlockX(chunkPos.getMinBlockX());
        }
        return generator;
    }
}
