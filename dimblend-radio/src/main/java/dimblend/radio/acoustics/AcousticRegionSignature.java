package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

/**
 * A cheap change detector for the terrain of a region, read on the client thread without copying
 * anything: every block change and chunk packet bumps its palette's acoustic version, and a chunk
 * reload replaces the palettes. Equal signatures mean nothing was touched; unequal ones only that
 * something may have changed (a reloaded chunk usually holds the same blocks).
 *
 * @param loaded every chunk column of the region is loaded
 */
public record AcousticRegionSignature(long hash, boolean loaded) {
    public static AcousticRegionSignature of(Level level, AABB box) {
        return of(level, box, null);
    }

    /**
     * @param structure read only this structure's plot columns of {@code box} (plot coordinates);
     *     the others are open air, and count as loaded. Null for the world's terrain.
     */
    public static AcousticRegionSignature of(Level level, AABB box, SubLevelAccess structure) {
        int minX = (int) Math.floor(box.minX) >> 4, maxX = (int) Math.floor(box.maxX) >> 4;
        int minZ = (int) Math.floor(box.minZ) >> 4, maxZ = (int) Math.floor(box.maxZ) >> 4;
        int minY = Math.max(level.getMinSection(), (int) Math.floor(box.minY) >> 4);
        int maxY = Math.min(level.getMaxSection() - 1, (int) Math.floor(box.maxY) >> 4);
        long hash = 1;
        boolean loaded = true;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (structure != null && SableCompanion.INSTANCE.getContaining(level, x, z) != structure) {
                    hash = hash * 31 + 2;
                    continue;
                }
                var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null || chunk instanceof EmptyLevelChunk) {
                    loaded = false;
                    hash = hash * 31 + 1;
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    var states = chunk.getSection(level.getSectionIndexFromSectionY(y)).getStates();
                    hash = hash * 31 + System.identityHashCode(states);
                    if (states instanceof AcousticPaletteVersion versioned) hash = hash * 31 + versioned.dimblend$acousticVersion();
                }
            }
        }
        return new AcousticRegionSignature(hash, loaded);
    }
}
