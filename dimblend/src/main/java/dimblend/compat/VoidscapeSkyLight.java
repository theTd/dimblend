package dimblend.compat;

import java.lang.ref.WeakReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

/**
 * Sky-light policy for the Voidscape lane inside {@code dimblend:rotating}.
 *
 * <p>Voidscape's own dimension declares {@code has_skylight: false}: its
 * {@code LevelLightEngine} has no sky engine at all, so vanilla serves every sky
 * query through {@code LayerLightEventListener.DummyLightLayerEventListener} —
 * reads answer 0 and there is no sky data layer to save or send. The rotating
 * dimension cannot drop the sky engine (every other lane needs sky light), so the
 * voidscape columns reproduce both halves of that answer: sky reads answer 0, and
 * those sections ship an empty (all-zero) sky layer, which every reader — vanilla
 * or third-party — sees as "no sky light" while {@code ChunkSerializer} still
 * writes no {@code SkyLight} for them. See {@code SkyLightSectionStorageMixin} /
 * {@code LayerLightSectionStorageMixin}.
 *
 * <p>The lane is an X partition of the rotating dimension, and every biome a
 * Voidscape delegate can place is in the {@code voidscape} namespace, so a single
 * probe decides a whole chunk column. Light reads sit on the mesher's and the
 * spawner's hot paths, so the probe result is cached per thread for the last
 * {@link #CACHE_SIZE} chunk columns.
 */
public final class VoidscapeSkyLight {
    /** Direct-mapped cache size; a power of two so the column index can be masked. */
    private static final int CACHE_SIZE = 64;
    private static final int CACHE_MASK = CACHE_SIZE - 1;

    private static final ThreadLocal<Probe> PROBE = ThreadLocal.withInitial(Probe::new);

    private VoidscapeSkyLight() {
    }

    /**
     * True when sky light at {@code packedBlockPos} must answer 0 (and its
     * section's sky layer must be empty). False for every level that is not the
     * rotating dimension and for every non-voidscape column.
     */
    public static boolean isMasked(BlockGetter level, long packedBlockPos) {
        if (!(level instanceof Level world) || !VoidscapeBand.isRotating(world)) {
            return false;
        }
        int blockX = BlockPos.getX(packedBlockPos);
        int chunkX = SectionPos.blockToSectionCoord(blockX);
        Probe probe = PROBE.get();
        int slot = chunkX & CACHE_MASK;
        WeakReference<Level> owner = probe.levels[slot];
        if (owner != null && owner.get() == world && probe.chunkXs[slot] == chunkX) {
            return probe.masked[slot];
        }
        boolean masked = probe(world, blockX, BlockPos.getY(packedBlockPos), BlockPos.getZ(packedBlockPos), probe.pos);
        probe.levels[slot] = new WeakReference<>(world);
        probe.chunkXs[slot] = chunkX;
        probe.masked[slot] = masked;
        return masked;
    }

    /** {@link #isMasked} for a packed <em>section</em> position, probed at its centre. */
    public static boolean isMaskedSection(BlockGetter level, long packedSectionPos) {
        return isMasked(level, BlockPos.asLong(
                SectionPos.sectionToBlockCoord(SectionPos.x(packedSectionPos), 8),
                SectionPos.sectionToBlockCoord(SectionPos.y(packedSectionPos), 8),
                SectionPos.sectionToBlockCoord(SectionPos.z(packedSectionPos), 8)));
    }

    /**
     * Server: the generator's band layout decides by block X, so the answer holds
     * even before the column is loaded. The layout exists by the time a level can
     * light anything ({@code RotatingChunkGenerator.createState} runs while the
     * level is built); the pre-layout delegate fallback only ever sees the fixed
     * band script, which would miss a random-region voidscape band.
     *
     * <p>Client: there is no layout (band assignment needs the world seed), so the
     * server-synced chunk biome is the second source of truth — every voidscape
     * delegate biome is in the {@code voidscape} namespace, at every Y and Z. It is
     * sampled at quart resolution so the answer cannot drift across a partition wall
     * the way fuzzy {@code getBiome} can, and it fails open (a chunk whose biome is
     * unavailable reads as "not voidscape") because a chunk that is not loaded has no
     * light to show. This runs on whichever thread asks for light, including
     * Sodium's section-clone workers.
     */
    private static boolean probe(Level world, int blockX, int blockY, int blockZ, BlockPos.MutableBlockPos probePos) {
        if (world instanceof ServerLevel server) {
            return VoidscapeBand.isVoidscapeColumn(server, blockX);
        }
        probePos.set(blockX, blockY, blockZ);
        return VoidscapeBand.isVoidscapeBiome(world.getBiomeManager().getNoiseBiomeAtPosition(probePos));
    }

    /**
     * Per-thread direct-mapped column cache: light reads walk one column at a time.
     * The owner is weak so a thread that outlives a level (light worker, render
     * thread, Sodium worker) cannot pin an unloaded {@link Level} and its chunks.
     */
    private static final class Probe {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        @SuppressWarnings("unchecked")
        private final WeakReference<Level>[] levels = new WeakReference[CACHE_SIZE];
        private final int[] chunkXs = new int[CACHE_SIZE];
        private final boolean[] masked = new boolean[CACHE_SIZE];
    }
}
