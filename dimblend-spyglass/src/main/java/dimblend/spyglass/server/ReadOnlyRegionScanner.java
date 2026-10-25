package dimblend.spyglass.server;

import dimblend.spyglass.structure.StructureOutline;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

/**
 * Read-only scan of existing region files for saved structure starts.
 *
 * <p>The scan only ever opens files through {@link ReadOnlyRegionFile}, which uses
 * {@code StandardOpenOption.READ} and never goes through the vanilla {@code RegionFile} (it may write
 * or repair headers); it never asks the level for chunks and never creates directories.
 *
 * <p>The region header is snapshotted when the file is opened, but the sectors are read later. A
 * concurrent server write can therefore free/reuse a sector between the two, in which case the bytes
 * may belong to another chunk. Every chunk is checked against its own saved {@code xPos}/{@code zPos}
 * and a mismatch is dropped (and counted) instead of being reported as this structure.
 *
 * <p>Failures skip only the failing chunk and are counted per category for one throttled warning.
 */
final class ReadOnlyRegionScanner {
    private ReadOnlyRegionScanner() {
    }

    static RegionScanResult scan(RegionReadContext context, List<StructureCandidate> candidates) {
        RegionReadErrors errors = new RegionReadErrors();
        List<StructureOutline> outlines = scanInto(context, candidates, errors);
        return new RegionScanResult(outlines, errors.snapshot());
    }

    /** Scan variant that accumulates read failures into a caller-owned counter. */
    static List<StructureOutline> scanInto(
            RegionReadContext context, List<StructureCandidate> candidates, RegionReadErrors errors) {
        Map<Long, List<StructureCandidate>> byRegion = new LinkedHashMap<>();
        for (StructureCandidate candidate : candidates) {
            byRegion.computeIfAbsent(candidate.regionKey(), key -> new ArrayList<>()).add(candidate);
        }

        List<StructureOutline> outlines = new ArrayList<>();
        for (Map.Entry<Long, List<StructureCandidate>> regionEntry : byRegion.entrySet()) {
            int regionX = ChunkPos.getX(regionEntry.getKey());
            int regionZ = ChunkPos.getZ(regionEntry.getKey());
            Path regionPath = context.regionDirectory().resolve("r." + regionX + "." + regionZ + ".mca");
            ReadOnlyRegionFile region;
            try {
                region = ReadOnlyRegionFile.open(regionPath);
            } catch (IOException | RuntimeException exception) {
                errors.regionFailure(exception);
                continue;
            }
            if (region == null) {
                continue;
            }
            try (ReadOnlyRegionFile open = region) {
                Map<Long, List<StructureCandidate>> byChunk = new LinkedHashMap<>();
                for (StructureCandidate candidate : regionEntry.getValue()) {
                    byChunk.computeIfAbsent(candidate.chunkKey(), key -> new ArrayList<>()).add(candidate);
                }
                for (Map.Entry<Long, List<StructureCandidate>> chunkEntry : byChunk.entrySet()) {
                    readChunk(
                            open,
                            context,
                            ChunkPos.getX(chunkEntry.getKey()),
                            ChunkPos.getZ(chunkEntry.getKey()),
                            chunkEntry.getValue(),
                            outlines,
                            errors);
                }
            } catch (IOException | RuntimeException exception) {
                errors.regionFailure(exception);
            }
        }
        return List.copyOf(outlines);
    }

    private static void readChunk(
            ReadOnlyRegionFile region,
            RegionReadContext context,
            int chunkX,
            int chunkZ,
            List<StructureCandidate> chunkCandidates,
            List<StructureOutline> outlines,
            RegionReadErrors errors) {
        Set<ResourceLocation> requestedIds = new HashSet<>();
        for (StructureCandidate candidate : chunkCandidates) {
            requestedIds.add(candidate.id());
        }

        InputStream stream;
        try {
            stream = region.openChunkData(chunkX, chunkZ);
        } catch (IOException | RuntimeException exception) {
            errors.chunkReadFailure(exception);
            return;
        }
        if (stream == null) {
            return;
        }
        try (InputStream open = stream) {
            StructureStartNbt.StartsRead read;
            try {
                read = StructureStartNbt.readOutlines(
                        new DataInputStream(open),
                        context.targetDataVersion(),
                        chunkX,
                        chunkZ);
            } catch (IOException | RuntimeException exception) {
                errors.parseFailure(exception);
                return;
            }
            switch (read.skipReason()) {
                case UNSUPPORTED_DATA_VERSION -> {
                    errors.unsupportedDataVersionSkip();
                    return;
                }
                case IDENTITY_MISMATCH -> {
                    errors.identityFailure(chunkX, chunkZ);
                    return;
                }
                case NONE -> {
                }
            }
            for (StructureOutline outline : read.outlines()) {
                if (requestedIds.contains(outline.id())) {
                    outlines.add(outline);
                }
            }
        } catch (IOException exception) {
            errors.chunkReadFailure(exception);
        }
    }
}
