package dimblend.worldgen;

import java.util.OptionalInt;
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
 */
public final class YShiftedStructureElevation {
    private YShiftedStructureElevation() {
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
