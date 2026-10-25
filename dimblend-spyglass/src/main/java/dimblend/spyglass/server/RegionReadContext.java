package dimblend.spyglass.server;

import java.nio.file.Path;
import java.util.Objects;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Everything the read-only region scan needs, resolved on the server thread so the scan itself can
 * run on an executor.
 *
 * @param targetDataVersion the running game's world data version. Only chunks saved with exactly
 *                          this DataVersion are read; any other version is skipped. Passed in
 *                          instead of read from {@code SharedConstants} inside the parser so the
 *                          read path stays testable without bootstrapping the game.
 * @param minBuildHeight    dimension minimum build height, needed to turn a chunk heightmap's stored
 *                          values into world Y ({@code Heightmap.java:111-116,135-137})
 * @param levelHeight       dimension height (e.g. 384), needed for the heightmap bit width
 *                          ({@code Mth.ceillog2(levelHeight + 1)}, {@code Heightmap.java:34})
 */
record RegionReadContext(
        ResourceKey<Level> dimension,
        int targetDataVersion,
        Path regionDirectory,
        int minBuildHeight,
        int levelHeight) {
    RegionReadContext {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(regionDirectory, "regionDirectory");
        if (targetDataVersion <= 0) {
            throw new IllegalArgumentException("Non-positive data version: " + targetDataVersion);
        }
        if (levelHeight <= 0) {
            throw new IllegalArgumentException("Non-positive level height: " + levelHeight);
        }
        // Heightmaps are a SimpleBitStorage, which only supports 1..32 bits per entry.
        int bits = Mth.ceillog2(levelHeight + 1);
        if (bits < 1 || bits > 32) {
            throw new IllegalArgumentException("Unsupported level height for heightmaps: " + levelHeight);
        }
        if ((long) minBuildHeight + levelHeight > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Level top overflows an int: " + minBuildHeight);
        }
    }
}
