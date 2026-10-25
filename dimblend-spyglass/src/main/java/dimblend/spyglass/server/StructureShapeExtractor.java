package dimblend.spyglass.server;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShapeCodec;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Extracts the real generated blocks of the structures that fit in a snapshot.
 *
 * <p>Runs on the scan executor only. For every selected structure it first reads the {@code Status}
 * and region-header timestamp of <b>every chunk a piece intersects</b>; a structure start is computed
 * up to 8 chunks outside the generated area (the FEATURES step requires
 * {@code STRUCTURE_STARTS} at distance 8, {@code ChunkPyramid.java:36-40}), so many saved starts have
 * no blocks yet. Only when all covering chunks are at or after {@code minecraft:features} does it read
 * their sections. Blocks are placed during the FEATURES step ({@code ChunkStatusTasks.java:154} →
 * {@code ChunkGenerator.applyBiomeDecoration}, which calls {@code placeInChunk},
 * {@code ChunkGenerator.java:316,352}), so that status is exactly the "blocks may exist" gate.
 *
 * <p>Kept blocks must be inside at least one piece box (the union of the boxes is not enough: a
 * jigsaw structure's pieces leave the gaps between them empty), must not be air and must not be a
 * pure fluid block. Waterlogged non-liquid blocks are kept: they are the real generated block state
 * and a mesh is expected to render them, while vanilla {@code LiquidBlock} states are rendered by the
 * fluid renderer.
 *
 * <p>A second stage then drops the kept blocks that are fully buried: {@link BuriedBlockFilter}
 * searches from the cells above each column's saved {@code WORLD_SURFACE} heightmap
 * ({@link ChunkHeightmaps}, read from the same chunk NBT plus a one-chunk ring of heightmap-only
 * reads) through the piece boxes' interior air, and keeps only blocks it can reach from open air. A
 * structure that is entirely underground therefore ends up with an empty shape instead of a solid
 * mesh behind the terrain.
 *
 * <p>The result is encoded with {@link StructureShapeCodec}. Every resource bound of one structure
 * skips its shape instead of forcing a huge payload or a long scan: the per-structure block cap
 * ({@link StructureShapeCodec#MAX_BLOCKS}), the piece cap ({@link #MAX_SHAPE_PIECES}), the visited-cell
 * budget ({@link #MAX_VISITED_CELLS}, shared with the visibility volume) and the covering-chunk cap
 * ({@link #MAX_COVERING_CHUNKS}, also bounding the heightmap ring); the whole scan additionally stops
 * at {@link #MAX_ENCODED_BYTES_PER_SCAN} encoded bytes and when the scan thread is interrupted.
 */
final class StructureShapeExtractor {
    private static final int CHUNK_SIZE = 16;
    /** Biome paletted containers use 4 cells per axis (strategy {@code SECTION_BIOMES}). */
    private static final int BIOME_CELLS_PER_AXIS = 4;
    /**
     * Upper bound of region chunks read for one structure. Even a structure filling the whole
     * display ring does not cover this many chunks; the bound keeps a corrupt or absurd start from
     * turning one scan into thousands of file reads. Larger structures get no shape.
     */
    static final int MAX_COVERING_CHUNKS = 1024;
    /**
     * Upper bound of saved pieces a structure may have for shape extraction. The snapshot already
     * caps the transmitted outline at {@code SpyglassSnapshotPayload#MAX_PIECES_PER_STRUCTURE} and
     * the total at {@code MAX_TOTAL_PIECES}, but it deliberately keeps the <b>full</b> piece list for
     * extraction. A corrupt start can carry tens of thousands of chunk-sized pieces, which would make
     * one scan iterate every piece inside every section of every covering chunk; such a structure
     * gets no shape.
     */
    static final int MAX_SHAPE_PIECES = 16_384;
    /**
     * Upper bound of cells one structure may visit, summing the volume of every piece-section
     * intersection. Air counts: the cost of the filter is in visiting the cells, not in how many of
     * them survive, so a corrupt start whose pieces cover huge empty volumes is aborted and gets no
     * shape. The visibility search's volume is bounded by the same number.
     */
    static final long MAX_VISITED_CELLS = 16_777_216L;
    /**
     * Upper bound of encoded shape bytes one scan keeps alive. The shared {@link
     * SpyglassServerShapeCache} has its own bound, but the scan result list and the per-player
     * pending queues reference the encoded shapes independently of it, so the scan itself is capped
     * too: nearest structures are kept first and everything farther gets no shape this scan.
     */
    static final long MAX_ENCODED_BYTES_PER_SCAN = 32L << 20;
    /**
     * Largest {@code x}/{@code z} {@link BlockPos#asLong} can pack. The packed form stores X and Z in
     * 26-bit signed fields and Y in 12 bits ({@code BlockPos.java:51-56}) and masks instead of
     * rejecting out-of-range values ({@code BlockPos.java:117-121}), so both ends are checked here.
     * The world border (30,000,000) is smaller than this bound.
     */
    static final int MAX_PACKABLE_BLOCK = (1 << 25) - 1;
    private static final int MIN_PACKABLE_Y = -2048;
    private static final int MAX_PACKABLE_Y = 2047;

    private StructureShapeExtractor() {
    }

    /** Caps of one extraction run; production uses {@link #production()}. */
    record Limits(int maxPiecesPerStructure, long maxVisitedCells, long maxEncodedBytes) {
        Limits {
            if (maxPiecesPerStructure <= 0 || maxVisitedCells <= 0L || maxEncodedBytes < 0L) {
                throw new IllegalArgumentException("Invalid extraction limits");
            }
        }

        static Limits production() {
            return new Limits(MAX_SHAPE_PIECES, MAX_VISITED_CELLS, MAX_ENCODED_BYTES_PER_SCAN);
        }
    }

    static Extraction extract(
            RegionReadContext context,
            Registry<Biome> biomeRegistry,
            List<SpyglassSnapshotLimiter.LimitedStructure> structures,
            SpyglassServerShapeCache cache,
            RegionReadErrors errors) {
        return extract(
                context,
                biomeRegistry,
                structures,
                cache,
                errors,
                Limits.production(),
                Thread.currentThread()::isInterrupted);
    }

    /**
     * Extraction with explicit caps and a cancellation check that is consulted at least once per
     * section. The production entry point passes {@link Limits#production()} and the scan thread's
     * interrupt flag; tests drive smaller caps and deterministic cancellation through here.
     */
    static Extraction extract(
            RegionReadContext context,
            Registry<Biome> biomeRegistry,
            List<SpyglassSnapshotLimiter.LimitedStructure> structures,
            SpyglassServerShapeCache cache,
            RegionReadErrors errors,
            Limits limits,
            BooleanSupplier cancelled) {
        List<EncodedStructureShape> shapes = new ArrayList<>();
        long producedBytes = 0L;
        int notGenerated = 0;
        int readFailed = 0;
        int tooManyBlocks = 0;
        int empty = 0;
        int unencodable = 0;
        int visibilityVolumeTooLarge = 0;
        int cacheHits = 0;
        int omittedByBytes = 0;
        boolean aborted = false;
        for (int index = 0; index < structures.size(); index++) {
            // A running scan ends with the server's scan executor; checking the flag per structure
            // and per section keeps a stop from waiting for the whole region walk.
            if (cancelled.getAsBoolean()) {
                aborted = true;
                break;
            }
            SpyglassSnapshotLimiter.LimitedStructure structure = structures.get(index);
            StructureKey key = structure.outline().key();
            List<OutlineBox> pieces = structure.allPieces();
            if (pieces.size() > limits.maxPiecesPerStructure()) {
                // (F1a) No shape for this structure; the scan summary counts it as unencodable.
                unencodable++;
                continue;
            }
            try {
                long[] chunks;
                try {
                    chunks = coveringChunks(pieces);
                } catch (IllegalArgumentException exception) {
                    unencodable++;
                    continue;
                }
                ChunkGate gate = readGate(context, chunks, errors, cancelled);
                if (gate.aborted()) {
                    aborted = true;
                    break;
                }
                if (gate.readFailures() > 0) {
                    readFailed++;
                    continue;
                }
                if (!gate.complete()) {
                    notGenerated++;
                    continue;
                }
                SpyglassServerShapeCache.Key cacheKey = new SpyglassServerShapeCache.Key(
                        context.dimension().location(), key, gate.revision());
                EncodedStructureShape cached = cache.get(cacheKey);
                if (cached != null) {
                    if (exceedsByteBudget(producedBytes, cached.bodyBytes(), limits.maxEncodedBytes())) {
                        omittedByBytes = structures.size() - index;
                        break;
                    }
                    producedBytes += cached.bodyBytes();
                    shapes.add(cached);
                    cacheHits++;
                    continue;
                }

                AccumulatedBlocks accumulated = readBlocks(
                        context,
                        biomeRegistry,
                        pieces,
                        chunks,
                        errors,
                        limits.maxVisitedCells(),
                        cancelled);
                if (accumulated.aborted) {
                    aborted = true;
                    break;
                }
                if (accumulated.visitedBudgetExceeded) {
                    // (F1b) Same no-shape outcome as the piece and block caps.
                    unencodable++;
                    continue;
                }
                if (accumulated.readFailed) {
                    readFailed++;
                    continue;
                }
                if (accumulated.tooManyBlocks) {
                    tooManyBlocks++;
                    continue;
                }
                if (accumulated.ringTooLarge) {
                    // The one-chunk heightmap ring is bounded like the covering chunks; a start whose
                    // ring exceeds that bound is treated like any other unencodable start.
                    unencodable++;
                    continue;
                }
                if (accumulated.blocks.isEmpty()) {
                    empty++;
                    continue;
                }
                BuriedBlockFilter.Result visible = BuriedBlockFilter.filter(
                        accumulated.blocks,
                        accumulated.heightmaps,
                        new PieceBoxIndex(pieces),
                        limits.maxVisitedCells(),
                        cancelled);
                if (visible.aborted()) {
                    aborted = true;
                    break;
                }
                if (visible.volumeTooLarge()) {
                    // The visibility search cannot bound its work, so this structure gets no shape at
                    // all: shipping the unfiltered blocks would show a solid block of "buried" mesh.
                    visibilityVolumeTooLarge++;
                    continue;
                }
                if (visible.blocks().isEmpty()) {
                    // Fully buried (or only reachable through more than 8 steps of interior air):
                    // no shape, the client shows nothing for this structure.
                    empty++;
                    continue;
                }
                // Biome tint still comes from the sections that contributed blocks before the
                // visibility filter, so a structure does not change tint because its interior was
                // dropped.
                Optional<ResourceKey<Biome>> biome = dominantBiome(accumulated.biomeCells);
                if (biome.isEmpty()) {
                    unencodable++;
                    continue;
                }
                EncodedStructureShape shape =
                        encode(key, gate.revision(), visible.blocks(), biome.get());
                if (shape == null) {
                    unencodable++;
                    continue;
                }
                if (exceedsByteBudget(producedBytes, shape.bodyBytes(), limits.maxEncodedBytes())) {
                    // (F3) Nearest structures are kept first; everything farther gets no shape for
                    // this scan.
                    omittedByBytes = structures.size() - index;
                    break;
                }
                producedBytes += shape.bodyBytes();
                cache.put(cacheKey, shape);
                shapes.add(shape);
            } catch (RuntimeException exception) {
                // One odd structure must not cost the player its whole snapshot or shape set.
                unencodable++;
                DimBlendSpyglass.LOGGER.debug(
                        "Spyglass shape extraction failed for {}: {}", key, exception.toString());
            }
        }
        ShapeStats stats = new ShapeStats(
                notGenerated, readFailed, tooManyBlocks, empty, unencodable,
                visibilityVolumeTooLarge, cacheHits, aborted ? 0 : shapes.size());
        if (aborted) {
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass shape extraction interrupted; {} shape(s) discarded", shapes.size());
            return new Extraction(List.of(), stats);
        }
        if (omittedByBytes > 0) {
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass shape scan reached its {}-byte encoded-shape budget; {} structure(s) "
                            + "get no shape this scan",
                    limits.maxEncodedBytes(),
                    omittedByBytes);
        }
        return new Extraction(List.copyOf(shapes), stats);
    }

    private static boolean exceedsByteBudget(long producedBytes, int nextBytes, long maxEncodedBytes) {
        return producedBytes + nextBytes > maxEncodedBytes;
    }

    /**
     * Every chunk that intersects at least one piece box. Pieces, not the union bounds: a jigsaw
     * structure may be spread far apart, and only chunks intersecting a piece can contain its blocks.
     */
    static long[] coveringChunks(List<OutlineBox> pieces) {
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (OutlineBox piece : pieces) {
            requirePackable(piece);
            int minChunkX = Math.floorDiv(piece.minX(), CHUNK_SIZE);
            int maxChunkX = Math.floorDiv(piece.maxX(), CHUNK_SIZE);
            int minChunkZ = Math.floorDiv(piece.minZ(), CHUNK_SIZE);
            int maxChunkZ = Math.floorDiv(piece.maxZ(), CHUNK_SIZE);
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    chunks.add(ChunkPos.asLong(chunkX, chunkZ));
                    if (chunks.size() > MAX_COVERING_CHUNKS) {
                        throw new IllegalArgumentException("Structure covers more than "
                                + MAX_COVERING_CHUNKS + " chunks");
                    }
                }
            }
        }
        long[] sorted = chunks.toLongArray();
        Arrays.sort(sorted);
        return sorted;
    }

    private static void requirePackable(OutlineBox piece) {
        if (!isPackable(piece.minX(), piece.minY(), piece.minZ())
                || !isPackable(piece.maxX(), piece.maxY(), piece.maxZ())) {
            throw new IllegalArgumentException("Structure piece outside the packable world: " + piece);
        }
    }

    private static boolean isPackable(int x, int y, int z) {
        return x >= -MAX_PACKABLE_BLOCK && x <= MAX_PACKABLE_BLOCK
                && z >= -MAX_PACKABLE_BLOCK && z <= MAX_PACKABLE_BLOCK
                && y >= MIN_PACKABLE_Y && y <= MAX_PACKABLE_Y;
    }

    /** First pass: existence, status gate and the revision, without inflating section data. */
    private static ChunkGate readGate(
            RegionReadContext context, long[] chunks, RegionReadErrors errors, BooleanSupplier cancelled) {
        int missing = 0;
        int ungenerated = 0;
        int readFailures = 0;
        int revision = 0;
        for (Map.Entry<Long, LongArrayList> group : groupByRegion(chunks).entrySet()) {
            if (cancelled.getAsBoolean()) {
                return ChunkGate.ABORTED;
            }
            Path regionPath = regionPath(context, group.getKey());
            ReadOnlyRegionFile region;
            try {
                region = ReadOnlyRegionFile.open(regionPath);
            } catch (IOException | RuntimeException exception) {
                // (F5) Keep the counts and revision gathered so far and still read the remaining
                // regions; the structure gets no shape because readFailures is non-zero.
                errors.regionFailure(exception);
                readFailures += group.getValue().size();
                continue;
            }
            if (region == null) {
                missing += group.getValue().size();
                continue;
            }
            try (ReadOnlyRegionFile open = region) {
                for (long chunkKey : group.getValue()) {
                    if (cancelled.getAsBoolean()) {
                        return ChunkGate.ABORTED;
                    }
                    int chunkX = ChunkPos.getX(chunkKey);
                    int chunkZ = ChunkPos.getZ(chunkKey);
                    if (!open.hasChunk(chunkX, chunkZ)) {
                        missing++;
                        continue;
                    }
                    revision = Math.max(revision, open.timestamp(chunkX, chunkZ));
                    ChunkShapeNbt.StatusRead status;
                    InputStream stream;
                    try {
                        stream = open.openChunkData(chunkX, chunkZ);
                    } catch (IOException | RuntimeException exception) {
                        errors.chunkReadFailure(exception);
                        readFailures++;
                        continue;
                    }
                    if (stream == null) {
                        missing++;
                        continue;
                    }
                    try (InputStream in = stream) {
                        status = ChunkShapeNbt.readStatus(
                                new DataInputStream(in),
                                context.targetDataVersion(),
                                chunkX,
                                chunkZ);
                    } catch (IOException | RuntimeException exception) {
                        errors.parseFailure(exception);
                        readFailures++;
                        continue;
                    }
                    switch (status.skipReason()) {
                        case UNSUPPORTED_DATA_VERSION -> {
                            errors.unsupportedDataVersionSkip();
                            readFailures++;
                        }
                        case IDENTITY_MISMATCH -> {
                            errors.identityFailure(chunkX, chunkZ);
                            readFailures++;
                        }
                        case NONE -> {
                            if (!ChunkShapeNbt.isGenerated(status.status())) {
                                ungenerated++;
                            }
                        }
                    }
                }
            } catch (IOException exception) {
                errors.regionFailure(exception);
                readFailures += group.getValue().size();
            }
        }
        return new ChunkGate(revision, missing, ungenerated, readFailures, false);
    }

    /** Second pass: read and filter the blocks of every covering chunk. */
    private static AccumulatedBlocks readBlocks(
            RegionReadContext context,
            Registry<Biome> biomeRegistry,
            List<OutlineBox> pieces,
            long[] chunks,
            RegionReadErrors errors,
            long maxVisitedCells,
            BooleanSupplier cancelled) {
        AccumulatedBlocks accumulated = new AccumulatedBlocks(
                new ChunkHeightmaps(context.minBuildHeight(), context.levelHeight()));
        for (Map.Entry<Long, LongArrayList> group : groupByRegion(chunks).entrySet()) {
            if (cancelled.getAsBoolean()) {
                accumulated.aborted = true;
                return accumulated;
            }
            Path regionPath = regionPath(context, group.getKey());
            ReadOnlyRegionFile region;
            try {
                region = ReadOnlyRegionFile.open(regionPath);
            } catch (IOException | RuntimeException exception) {
                errors.regionFailure(exception);
                accumulated.readFailed = true;
                return accumulated;
            }
            if (region == null) {
                accumulated.readFailed = true;
                return accumulated;
            }
            try (ReadOnlyRegionFile open = region) {
                for (long chunkKey : group.getValue()) {
                    if (cancelled.getAsBoolean()) {
                        accumulated.aborted = true;
                        return accumulated;
                    }
                    int chunkX = ChunkPos.getX(chunkKey);
                    int chunkZ = ChunkPos.getZ(chunkKey);
                    ChunkShapeNbt.SectionsRead sections;
                    InputStream stream;
                    try {
                        stream = open.openChunkData(chunkX, chunkZ);
                    } catch (IOException | RuntimeException exception) {
                        errors.chunkReadFailure(exception);
                        accumulated.readFailed = true;
                        return accumulated;
                    }
                    if (stream == null) {
                        accumulated.readFailed = true;
                        return accumulated;
                    }
                    try (InputStream in = stream) {
                        sections = ChunkShapeNbt.readSections(
                                new DataInputStream(in),
                                context.targetDataVersion(),
                                chunkX,
                                chunkZ,
                                biomeRegistry);
                    } catch (IOException | RuntimeException exception) {
                        errors.parseFailure(exception);
                        accumulated.readFailed = true;
                        return accumulated;
                    }
                    switch (sections.skipReason()) {
                        case UNSUPPORTED_DATA_VERSION -> {
                            errors.unsupportedDataVersionSkip();
                            accumulated.readFailed = true;
                            return accumulated;
                        }
                        case IDENTITY_MISMATCH -> {
                            errors.identityFailure(chunkX, chunkZ);
                            accumulated.readFailed = true;
                            return accumulated;
                        }
                        case NONE -> {
                        }
                    }
                    // The same read that produces the blocks also carries the chunk's surface
                    // heightmap; a missing or malformed one simply closes this chunk's columns.
                    accumulated.heightmaps.put(chunkX, chunkZ, sections.heightmaps());
                    if (!accumulateChunk(
                            accumulated, sections.sections(), pieces, chunkX, chunkZ, maxVisitedCells, cancelled)) {
                        return accumulated;
                    }
                }
            } catch (IOException exception) {
                errors.regionFailure(exception);
                accumulated.readFailed = true;
                return accumulated;
            }
        }
        readRingHeightmaps(context, accumulated, chunks, cancelled);
        return accumulated;
    }

    /**
     * Heightmap read for the one-chunk ring around the covering chunks. The visibility search grows
     * the block extent by one block, so structures that touch a chunk border also need the neighbour
     * columns' surfaces. Ring chunks are read for their heightmap only.
     *
     * <p>Best effort by design: a ring chunk that is missing, not generated, unreadable or has no
     * heightmap closes its columns instead of failing this structure, because those chunks are often
     * simply not saved yet. A ring larger than the covering-chunk cap is different — it means the
     * extra read work is unbounded, so the structure is marked {@link
     * AccumulatedBlocks#ringTooLarge}.
     */
    private static void readRingHeightmaps(
            RegionReadContext context,
            AccumulatedBlocks accumulated,
            long[] coveringChunks,
            BooleanSupplier cancelled) {
        LongOpenHashSet covering = new LongOpenHashSet(coveringChunks);
        LongOpenHashSet ring = new LongOpenHashSet();
        for (long chunkKey : coveringChunks) {
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    long neighbour = ChunkPos.asLong(chunkX + offsetX, chunkZ + offsetZ);
                    if (!covering.contains(neighbour)) {
                        ring.add(neighbour);
                    }
                }
            }
            if (ring.size() > MAX_COVERING_CHUNKS) {
                accumulated.ringTooLarge = true;
                return;
            }
        }
        long[] ringChunks = ring.toLongArray();
        Arrays.sort(ringChunks);
        for (Map.Entry<Long, LongArrayList> group : groupByRegion(ringChunks).entrySet()) {
            if (cancelled.getAsBoolean()) {
                accumulated.aborted = true;
                return;
            }
            ReadOnlyRegionFile region;
            try {
                region = ReadOnlyRegionFile.open(regionPath(context, group.getKey()));
            } catch (IOException | RuntimeException exception) {
                continue;
            }
            if (region == null) {
                continue;
            }
            try (ReadOnlyRegionFile open = region) {
                for (long chunkKey : group.getValue()) {
                    if (cancelled.getAsBoolean()) {
                        accumulated.aborted = true;
                        return;
                    }
                    int chunkX = ChunkPos.getX(chunkKey);
                    int chunkZ = ChunkPos.getZ(chunkKey);
                    if (!open.hasChunk(chunkX, chunkZ)) {
                        continue;
                    }
                    try {
                        InputStream stream = open.openChunkData(chunkX, chunkZ);
                        if (stream == null) {
                            continue;
                        }
                        try (InputStream in = stream) {
                            CompoundTag heightmaps = ChunkShapeNbt.readHeightmaps(
                                    new DataInputStream(in),
                                    context.targetDataVersion(),
                                    chunkX,
                                    chunkZ);
                            accumulated.heightmaps.put(chunkX, chunkZ, heightmaps);
                        }
                    } catch (IOException | RuntimeException exception) {
                        // Closed columns; see the method comment.
                    }
                }
            } catch (IOException exception) {
                // Closing the region fails at most the columns of this group, which stay closed.
            }
        }
    }

    /**
     * @return {@code false} when the block cap was exceeded or the extraction was cancelled
     */
    private static boolean accumulateChunk(
            AccumulatedBlocks accumulated,
            List<ChunkShapeNbt.Section> sections,
            List<OutlineBox> pieces,
            int chunkX,
            int chunkZ,
            long maxVisitedCells,
            BooleanSupplier cancelled) {
        int chunkMinX = chunkX * CHUNK_SIZE;
        int chunkMinZ = chunkZ * CHUNK_SIZE;
        int chunkMaxX = chunkMinX + CHUNK_SIZE - 1;
        int chunkMaxZ = chunkMinZ + CHUNK_SIZE - 1;
        for (ChunkShapeNbt.Section section : sections) {
            if (cancelled.getAsBoolean()) {
                accumulated.aborted = true;
                return false;
            }
            int sectionMinY = section.sectionY() * CHUNK_SIZE;
            int sectionMaxY = sectionMinY + CHUNK_SIZE - 1;
            boolean contributed = false;
            for (OutlineBox piece : pieces) {
                int minX = Math.max(piece.minX(), chunkMinX);
                int maxX = Math.min(piece.maxX(), chunkMaxX);
                int minZ = Math.max(piece.minZ(), chunkMinZ);
                int maxZ = Math.min(piece.maxZ(), chunkMaxZ);
                int minY = Math.max(piece.minY(), sectionMinY);
                int maxY = Math.min(piece.maxY(), sectionMaxY);
                if (minX > maxX || minZ > maxZ || minY > maxY) {
                    continue;
                }
                // (F1b) Every cell of the intersection is visited by the loop below, air included.
                accumulated.visitedCells += (long) (maxX - minX + 1)
                        * (maxY - minY + 1)
                        * (maxZ - minZ + 1);
                if (accumulated.visitedCells > maxVisitedCells) {
                    accumulated.visitedBudgetExceeded = true;
                    return false;
                }
                for (int y = minY; y <= maxY; y++) {
                    int localY = y & (CHUNK_SIZE - 1);
                    for (int z = minZ; z <= maxZ; z++) {
                        int localZ = z & (CHUNK_SIZE - 1);
                        for (int x = minX; x <= maxX; x++) {
                            BlockState state = section.states().get(x & (CHUNK_SIZE - 1), localY, localZ);
                            if (!keeps(state)) {
                                continue;
                            }
                            accumulated.blocks.put(BlockPos.asLong(x, y, z), state);
                            contributed = true;
                            if (accumulated.blocks.size() > StructureShapeCodec.MAX_BLOCKS) {
                                accumulated.tooManyBlocks = true;
                                return false;
                            }
                        }
                    }
                }
            }
            if (contributed) {
                countBiomes(section, accumulated.biomeCells);
            }
        }
        return true;
    }

    /** Air is not part of the generated shape; pure fluid states are drawn by fluid rendering. */
    private static boolean keeps(BlockState state) {
        return !state.isAir() && !(state.getBlock() instanceof LiquidBlock);
    }

    /**
     * Adds one biome cell count per cell of a section that contributed blocks, so sections are
     * weighted equally regardless of how many of their blocks survived the piece filter.
     */
    private static void countBiomes(
            ChunkShapeNbt.Section section, Map<ResourceKey<Biome>, Integer> counts) {
        for (int y = 0; y < BIOME_CELLS_PER_AXIS; y++) {
            for (int z = 0; z < BIOME_CELLS_PER_AXIS; z++) {
                for (int x = 0; x < BIOME_CELLS_PER_AXIS; x++) {
                    Holder<Biome> holder = section.biomes().get(x, y, z);
                    Optional<ResourceKey<Biome>> key = holder.unwrapKey();
                    if (key.isPresent()) {
                        counts.merge(key.get(), 1, Integer::sum);
                    }
                }
            }
        }
    }

    private static Optional<ResourceKey<Biome>> dominantBiome(
            Map<ResourceKey<Biome>, Integer> counts) {
        ResourceKey<Biome> best = null;
        int bestCount = 0;
        for (Map.Entry<ResourceKey<Biome>, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (best == null
                    || count > bestCount
                    || (count == bestCount && entry.getKey().location().toString()
                            .compareTo(best.location().toString()) < 0)) {
                best = entry.getKey();
                bestCount = count;
            }
        }
        return Optional.ofNullable(best);
    }

    @Nullable
    private static EncodedStructureShape encode(
            StructureKey key,
            int revision,
            Long2ObjectOpenHashMap<BlockState> blocks,
            ResourceKey<Biome> biome) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (long position : blocks.keySet()) {
            int x = BlockPos.getX(position);
            int y = BlockPos.getY(position);
            int z = BlockPos.getZ(position);
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        if (sizeX > StructureShapeCodec.MAX_AXIS
                || sizeY > StructureShapeCodec.MAX_AXIS
                || sizeZ > StructureShapeCodec.MAX_AXIS) {
            return null;
        }
        List<StructureShapeCodec.BlockEntry> entries = new ArrayList<>(blocks.size());
        for (Long2ObjectMap.Entry<BlockState> entry : blocks.long2ObjectEntrySet()) {
            long position = entry.getLongKey();
            int relX = BlockPos.getX(position) - minX;
            int relY = BlockPos.getY(position) - minY;
            int relZ = BlockPos.getZ(position) - minZ;
            long index = ((long) relY * sizeZ + relZ) * sizeX + relX;
            entries.add(new StructureShapeCodec.BlockEntry(index, entry.getValue()));
        }
        byte[] body;
        try {
            body = StructureShapeCodec.encode(
                    minX,
                    minY,
                    minZ,
                    sizeX,
                    sizeY,
                    sizeZ,
                    biome.location(),
                    entries);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        return EncodedStructureShape.split(key, revision, entries.size(), body);
    }

    private static Path regionPath(RegionReadContext context, long regionKey) {
        return context.regionDirectory().resolve("r."
                + ChunkPos.getX(regionKey) + "." + ChunkPos.getZ(regionKey) + ".mca");
    }

    private static Map<Long, LongArrayList> groupByRegion(long[] chunks) {
        Map<Long, LongArrayList> groups = new LinkedHashMap<>();
        for (long chunkKey : chunks) {
            int chunkX = ChunkPos.getX(chunkKey);
            int chunkZ = ChunkPos.getZ(chunkKey);
            long regionKey = ChunkPos.asLong(Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
            groups.computeIfAbsent(regionKey, ignored -> new LongArrayList()).add(chunkKey);
        }
        return groups;
    }

    private static final class AccumulatedBlocks {
        private final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();
        private final Map<ResourceKey<Biome>, Integer> biomeCells = new HashMap<>();
        /** Surface heightmaps of the covering chunks plus their one-chunk ring. */
        private final ChunkHeightmaps heightmaps;
        private long visitedCells;
        private boolean tooManyBlocks;
        private boolean visitedBudgetExceeded;
        private boolean ringTooLarge;
        private boolean readFailed;
        private boolean aborted;

        private AccumulatedBlocks(ChunkHeightmaps heightmaps) {
            this.heightmaps = heightmaps;
        }
    }

    private record ChunkGate(int revision, int missing, int ungenerated, int readFailures, boolean aborted) {
        private static final ChunkGate ABORTED = new ChunkGate(0, 0, 0, 0, true);

        boolean complete() {
            return this.missing == 0 && this.ungenerated == 0;
        }
    }

    /** Per-structure outcome plus the counters reported in one DEBUG line. */
    record ShapeStats(
            int notGenerated,
            int readFailed,
            int tooManyBlocks,
            int empty,
            int unencodable,
            int visibilityVolumeTooLarge,
            int cacheHits,
            int extracted) {
    }

    record Extraction(List<EncodedStructureShape> shapes, ShapeStats stats) {
        Extraction {
            shapes = List.copyOf(shapes);
        }
    }
}
