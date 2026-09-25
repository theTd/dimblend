package dimblend.worldgen;

import java.util.OptionalInt;
import java.util.function.IntSupplier;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Twilight Forest's landmark {@code adjustForTerrain} clamps to {@code [sea+1, sea+7]}.
 * That window matches TF's original sea-level surface; after {@link YShiftedDensity}
 * lifts the terrain, the same cap sits in the basement of any hill taller than 7
 * blocks, and structure {@code setBlock} punches leaves into stone. Keep the sea+1
 * floor so pieces stay out of water, but drop the ceiling.
 *
 * <p>Data-driven jigsaws with {@code project_start_to_heightmap} add {@code start_height}
 * to the (already lifted) heightmap. Absolute anchors in that offset must not go
 * through the world-Y shift; sample them with {@link #sampleWithoutAbsoluteYShift}.
 */
public final class YShiftedStructureElevation {
    private YShiftedStructureElevation() {
    }

    /**
     * Runs {@code sample} with the context's absolute Y shift cleared, then restores it.
     * No-op when the shift is already 0.
     */
    public static int sampleWithoutAbsoluteYShift(
            WorldGenerationContextExtension ext,
            IntSupplier sample
    ) {
        int saved = ext.dimblend$absoluteOffset();
        if (saved == 0) {
            return sample.getAsInt();
        }
        ext.dimblend$setAbsoluteOffset(0);
        try {
            return sample.getAsInt();
        } finally {
            ext.dimblend$setAbsoluteOffset(saved);
        }
    }

    /**
     * @return empty when the TF default clamp should run
     */
    public static OptionalInt unclampIfShifted(
            ChunkGenerator generator,
            boolean adjustToTerrain,
            int x,
            int z,
            LevelHeightAccessor height,
            RandomState random
    ) {
        if (!adjustToTerrain || !(generator instanceof YShiftedNoiseChunkGenerator)) {
            return OptionalInt.empty();
        }
        int surface = generator.getFirstOccupiedHeight(
                x, z, Heightmap.Types.WORLD_SURFACE_WG, height, random);
        return OptionalInt.of(Math.max(surface, generator.getSeaLevel() + 1));
    }
}
