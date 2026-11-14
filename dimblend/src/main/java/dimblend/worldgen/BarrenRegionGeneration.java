package dimblend.worldgen;

import dimblend.api.RegionOverrides;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * Barren End generation for regions another mod forced to
 * {@link RegionOverrides.Override#END_BARREN} (requirements §3.3, §4; chase terrain).
 *
 * <p>A forced region keeps the End <b>generator</b> — {@link BandLayout#delegateIndex} resolves it
 * to the first End delegate, so terrain noise and the build-surface pass are the vanilla End
 * islands — but must look like an empty sky: one biome, no features, no structures, no
 * generation-time mobs. The biome is rewritten after the delegate filled its palette, because
 * the End's own biome source would otherwise paint highlands/midlands/islands and with them
 * chorus and end-city eligible biomes.
 *
 * <p>The corridor itself is <b>not</b> part of the delegate pass: tracks, vault, glass shell,
 * region walls and warp gates are written by {@link OakTrackCorridor#place} and
 * {@link RegionBoundaryWall#place} from {@link RotatingChunkGenerator#applyBiomeDecoration}
 * <i>after</i> the delegate returns, so skipping the delegate keeps them.
 *
 * <p>Reads {@link RegionOverrides} only; every method here is safe on worldgen worker threads.
 */
public final class BarrenRegionGeneration {
    private BarrenRegionGeneration() {
    }

    /** True when {@code region} must be generated barren. Lock-free; safe on worldgen threads. */
    public static boolean isBarrenRegion(int region) {
        return RegionOverrides.isBarren(region);
    }

    /**
     * True when every column of {@code pos} lies in a barren region. Chunk and region boundaries
     * are both multiples of 16 ({@code RotatingChunkGenerator} enforces the band size), so the
     * chunk's minimum block X decides for the whole chunk — the same key
     * {@link RotatingChunkGenerator} uses to pick the delegate.
     */
    public static boolean isBarrenChunk(RotatingChunkGenerator rotating, ChunkPos pos) {
        return isBarrenRegion(BandLayout.regionOfBlockX(pos.getMinBlockX(), rotating.bandSize()));
    }

    /** Delegate index of the first End lane in an assigner kind array, or -1 when there is none. */
    static int endDelegateIndex(byte[] kinds) {
        for (int index = 0; index < kinds.length; index++) {
            if (kinds[index] == BandLaneAssigner.END) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The delegate that actually generates {@code region}: the first End delegate when the
     * region is forced barren, otherwise the seeded {@code assigned} index. Kept static and
     * side-effect free so the decision is unit-tested without building real generators.
     */
    static int delegateIndexFor(byte[] kinds, int region, int assigned) {
        if (!isBarrenRegion(region)) {
            return assigned;
        }
        int end = endDelegateIndex(kinds);
        return end >= 0 ? end : assigned;
    }

    /** {@code minecraft:end_barrens} holder from the registry of the level being generated. */
    public static Holder<Biome> barrenBiome(RegistryAccess access) {
        return access.registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.END_BARRENS);
    }

    /**
     * Overwrites every section's biome palette with {@code barren}. Replaces the palette the
     * End delegate's biome source produced (highlands/midlands/small islands), which is what
     * makes "every biome = minecraft:end_barrens" true for the saved chunk and every later
     * biome read of the loaded chunk.
     *
     * <p>{@code sampler} is only forwarded to {@link BiomeResolver}; the constant resolver never
     * reads it (vanilla {@code LevelChunkSection#fillBiomesFromNoise} passes it through per
     * 4×4×4 cell, LevelChunkSection.java:180).
     */
    public static void fillBarrenBiome(ChunkAccess chunk, Holder<Biome> barren, Climate.Sampler sampler) {
        BiomeResolver constant = (quartX, quartY, quartZ, ignoredSampler) -> barren;
        for (int section = 0; section < chunk.getSectionsCount(); section++) {
            chunk.getSection(section).fillBiomesFromNoise(constant, sampler, 0, 0, 0);
        }
    }
}
