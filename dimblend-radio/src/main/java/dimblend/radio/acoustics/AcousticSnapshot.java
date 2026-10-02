package dimblend.radio.acoustics;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.terrain.SectionGeometryCache;
import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
import dimblend.radio.acoustics.terrain.SectionBlockFingerprint;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Copies palettes and poses on the client thread; worker raycasts never touch Level. */
public final class AcousticSnapshot implements ReflectionGeometry {
    private static final AcousticPaletteCache<BlockState> PALETTES = new AcousticPaletteCache<>();
    private record Frame(FrozenBlocks blocks, Pose3d pose, AABB worldBounds, AABB localBounds,
            SubLevelAccess liveStructure) { }

    /** A structure as one radio's reflection scene sees it: that radio's own block is left out. */
    private record Structure(Frame frame, BlockPos emitter) implements ReflectionGeometry.Body {
        @Override public UUID id() { return frame.liveStructure.getUniqueId(); }

        @Override public long contentKey() { return frame.blocks.contentKey ^ HashCommon.mix(emitter.asLong()); }

        @Override public Pose3dc pose() { return frame.pose; }

        /** Every captured non-air section, voxelized in plot coordinates. */
        @Override public AcousticMesh.Data localMesh(AcousticMesh.Workspace workspace) {
            AABB box = frame.blocks.contentBounds;
            if (box == null) return AcousticMesh.Data.empty(Vec3.ZERO);
            AcousticMesh mesh = new AcousticMesh(new Vec3(box.minX, box.minY, box.minZ), workspace);
            mesh.append(frame.blocks, box, emitter);
            return mesh.data();
        }
    }
    private final FrozenBlocks terrain;
    private final List<Frame> structures;
    private final BlockPos emitter;
    private final long revision;
    private final AABB bounds;

    private AcousticSnapshot(FrozenBlocks terrain, List<Frame> structures, BlockPos emitter, long revision, AABB bounds) {
        this.terrain = terrain;
        this.structures = structures;
        this.emitter = emitter;
        this.revision = revision;
        this.bounds = bounds;
    }

    public long revision() { return revision; }

    /** World region whose blocks and structures were frozen. */
    public AABB bounds() { return bounds; }

    public boolean covers(AABB region) {
        return contains(bounds, region);
    }

    public static boolean contains(AABB outer, AABB inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY && outer.maxZ >= inner.maxZ;
    }

    public static void clearCache() {
        PALETTES.clear();
        AcousticSurfaceKinds.clear();
    }

    /** All nearby radios share frozen terrain/poses, but exclude their own emitter block. */
    public AcousticSnapshot forEmitter(BlockPos source) {
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, structures, source.immutable(), revision, bounds);
        AcousticUpdateGate.copySnapshot(this, snapshot);
        return snapshot;
    }

    public static AcousticSnapshot capture(Level level, Vec3 listener, BlockPos emitter) {
        return capture(level, listener, emitter, 96);
    }

    public static AcousticSnapshot capture(Level level, Vec3 listener, BlockPos emitter, double radius) {
        return capture(level, new AABB(listener, listener).inflate(radius), emitter);
    }

    /** Freezes only {@code bounds}: callers size it to the radios' meshes and direct paths. */
    public static AcousticSnapshot capture(Level level, AABB bounds, BlockPos emitter) {
        long revision = AcousticSceneChanges.revision();
        long generation = level.isClientSide ? AcousticSceneChanges.beginCapture() : 0;
        FrozenBlocks terrain = FrozenBlocks.capture(level, bounds, null, generation);
        List<Frame> frames = new ArrayList<>();
        for (SubLevelAccess structure : SableCompanion.INSTANCE.getAllIntersecting(level, new BoundingBox3d(bounds))) {
            Pose3d pose = new Pose3d(structure.logicalPose());
            AABB world = structure.boundingBox().toMojang().intersect(bounds).inflate(0.01);
            AABB local = new BoundingBox3d(world).transformInverse(pose).toMojang();
            frames.add(new Frame(FrozenBlocks.capture(level, local, structure, generation), pose, world, local, structure));
        }
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, List.copyOf(frames), emitter.immutable(), revision, bounds);
        AcousticUpdateGate.registerSnapshot(snapshot, terrain, frames.stream().map(Frame::blocks).toList(),
                frames.stream().map(Frame::liveStructure).map(SubLevelAccess::getUniqueId).toList(), frames.stream().map(Frame::pose).toList());
        return snapshot;
    }

    /** Called on the client thread: refresh moving poses without copying terrain palettes again. */
    public AcousticSnapshot currentPoses() {
        List<Frame> frames = new ArrayList<>();
        for (Frame frame : structures) {
            Pose3d pose = new Pose3d(frame.liveStructure.logicalPose());
            AABB bounds = new BoundingBox3d(frame.localBounds).transform(pose).toMojang();
            frames.add(new Frame(frame.blocks, pose, bounds, frame.localBounds, frame.liveStructure));
        }
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, List.copyOf(frames), emitter, revision, bounds);
        AcousticUpdateGate.registerSnapshot(snapshot, terrain, frames.stream().map(Frame::blocks).toList(),
                frames.stream().map(Frame::liveStructure).map(SubLevelAccess::getUniqueId).toList(), frames.stream().map(Frame::pose).toList());
        return snapshot;
    }

    /** The whole reflection scene around one listener as a single mesh (self-tests and GameTests). */
    public AcousticMesh.Data mesh(Vec3 listener, Vec3 source) {
        Vec3 origin = new Vec3(Math.floor(listener.x / 16) * 16, Math.floor(listener.y / 16) * 16, Math.floor(listener.z / 16) * 16);
        var workspace = new AcousticMesh.Workspace();
        AcousticMesh mesh = new AcousticMesh(origin, workspace);
        appendTerrain(mesh, new AABB(listener, source).inflate(ReflectionMeshCache.MARGIN));
        for (ReflectionGeometry.Body body : bodies()) mesh.appendPlaced(body.localMesh(workspace), body.pose());
        return mesh.data();
    }

    @Override public AcousticMesh.Data terrainMesh(AABB bounds, Vec3 origin, AcousticMesh.Workspace workspace) {
        AcousticMesh mesh = new AcousticMesh(origin, workspace);
        appendTerrain(mesh, bounds);
        return mesh.data();
    }

    private void appendTerrain(AcousticMesh mesh, AABB bounds) {
        if (!renderMirror()) {
            mesh.append(terrain, bounds, emitter);
            logGeometrySource("voxel");
            return;
        }
        SectionGeometryCache.Coverage coverage = SectionGeometryCache.presentSections(bounds,
                terrain.minSection(), terrain.maxSection(), TerrainGeometryMode.CURRENT == TerrainGeometryMode.SODIUM);
        mesh.appendSections(coverage.sections(), emitter);
        if (TerrainGeometryMode.CURRENT == TerrainGeometryMode.AUTO) {
            mesh.append(terrain, bounds, emitter, coverage.covered());
            logGeometrySource("render-mesh(" + coverage.sections().size() + " sections)+voxel");
        } else {
            logGeometrySource("render-mesh(" + coverage.sections().size() + " sections)");
        }
    }

    /**
     * Sodium only meshes sections its visibility traversal visits, so the mirror's coverage is
     * always partial: AUTO mixes exact render geometry with a voxel fill of the gaps.
     */
    private static boolean renderMirror() {
        return TerrainGeometryMode.CURRENT != TerrainGeometryMode.VOXEL && SectionGeometryCache.active();
    }

    /** The mirror's fold, complemented so an empty mirror still differs from pure voxel terrain. */
    @Override public long renderGeometryVersion(AABB bounds) {
        return renderMirror() ? ~SectionGeometryCache.foldHash(bounds, terrain.minSection(), terrain.maxSection()) : 0;
    }

    @Override public long terrainSection(long key) { return terrain.sectionState(key); }

    @Override public int minSection() { return terrain.minSection(); }

    @Override public int maxSection() { return terrain.maxSection(); }

    @Override public List<? extends ReflectionGeometry.Body> bodies() {
        List<Structure> bodies = new ArrayList<>(structures.size());
        for (Frame frame : structures) bodies.add(new Structure(frame, emitter));
        return bodies;
    }

    /** One INFO line per source switch: proves which geometry feeds the reflection GPU upload. */
    private static volatile String lastGeometrySource;

    private static void logGeometrySource(String source) {
        if (!source.equals(lastGeometrySource)) {
            lastGeometrySource = source;
            DimBlendRadio.LOGGER.info("[radio] reflection geometry: {}", source);
        }
    }

    public AcousticRay cast(Vec3 from, Vec3 to) {
        AcousticRay nearest = castLocal(terrain, from, to);
        double distance = from.distanceToSqr(nearest.position());
        for (Frame frame : structures) {
            double[] range = AcousticRaycaster.clipRange(from, to, frame.worldBounds);
            if (range == null) continue;
            Vec3 delta = to.subtract(from);
            AcousticRay local = castLocal(frame.blocks,
                    frame.pose.transformPositionInverse(from.add(delta.scale(range[0]))),
                    frame.pose.transformPositionInverse(from.add(delta.scale(range[1]))));
            if (local.kind() == AcousticRay.Kind.MISS) continue;
            AcousticRay hit = AcousticRaycaster.projectHit(local, frame.pose);
            double candidate = from.distanceToSqr(hit.position());
            if (candidate < distance || nearest.kind() == AcousticRay.Kind.MISS) {
                nearest = hit;
                distance = candidate;
            }
        }
        return nearest;
    }

    private AcousticRay castLocal(FrozenBlocks blocks, Vec3 from, Vec3 to) {
        return AcousticVoxelTrace.cast(from, to, pos -> {
            if (pos.equals(emitter) || blocks.isOutsideBuildHeight(pos)) return null;
            if (!blocks.known(pos)) return AcousticVoxelTrace.Cell.UNKNOWN;
            BlockState state = blocks.getBlockState(pos);
            return state.isAir() ? null : AcousticVoxelTrace.cell(state, blocks, pos);
        });
    }

    private static final class FrozenBlocks implements AcousticMesh.SectionSource {
        private final Long2ObjectOpenHashMap<PalettedContainer<BlockState>> sections = new Long2ObjectOpenHashMap<>();
        /** Block fingerprint of every captured non-air section. */
        private final Long2LongOpenHashMap fingerprints = new Long2LongOpenHashMap();
        private final LongOpenHashSet chunks = new LongOpenHashSet();
        private final int minimum;
        private final int height;
        private final boolean plot;
        /** Captured chunk columns and section rows, inclusive; nothing is captured while a minimum exceeds its maximum. */
        private int minX = 1, maxX, minY = 1, maxY, minZ = 1, maxZ;
        /** Structures only: identity and section-aligned bounds of the captured non-air sections. */
        private long contentKey;
        private AABB contentBounds;
        private FrozenBlocks(Level level, boolean plot) {
            minimum = level.getMinBuildHeight();
            height = level.getHeight();
            this.plot = plot;
        }
        static FrozenBlocks capture(Level level, AABB box, SubLevelAccess structure, long generation) {
            var frozen = new FrozenBlocks(level, structure != null);
            frozen.minY = Math.max(level.getMinSection(), (int) Math.floor(box.minY) >> 4);
            frozen.maxY = Math.min(level.getMaxSection() - 1, (int) Math.floor(box.maxY) >> 4);
            frozen.minX = (int) Math.floor(box.minX) >> 4;
            frozen.maxX = (int) Math.floor(box.maxX) >> 4;
            frozen.minZ = (int) Math.floor(box.minZ) >> 4;
            frozen.maxZ = (int) Math.floor(box.maxZ) >> 4;
            for (int x = frozen.minX; x <= frozen.maxX; x++) {
                for (int z = frozen.minZ; z <= frozen.maxZ; z++) {
                    if (structure != null && SableCompanion.INSTANCE.getContaining(level, x, z) != structure) continue;
                    var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk == null || chunk instanceof EmptyLevelChunk) continue;
                    frozen.chunks.add(ChunkPos.asLong(x, z));
                    for (int y = frozen.minY; y <= frozen.maxY; y++) {
                        var section = chunk.getSection(level.getSectionIndexFromSectionY(y));
                        long key = SectionPos.asLong(x, y, z);
                        if (generation != 0 && section.getStates() instanceof AcousticPaletteVersion versioned) {
                            versioned.dimblend$observeAcoustics(generation, key);
                            AcousticSceneChanges.observe(generation, key);
                        }
                        // Fingerprint an edited section once; the next edit re-arms it.
                        boolean validateMesh = SectionGeometryCache.awaitingFingerprint(key);
                        if (!section.hasOnlyAir() || validateMesh) {
                            var copy = PALETTES.freeze(section.getStates());
                            if (!section.hasOnlyAir()) {
                                frozen.sections.put(key, copy.blocks());
                                frozen.fingerprints.put(key, copy.fingerprint());
                            }
                            if (validateMesh) SectionGeometryCache.expectBlocks(key, SectionBlockFingerprint.of(copy.blocks()::get));
                        }
                    }
                }
            }
            if (structure != null) frozen.summarizeContent();
            // Terrain identity includes which chunks were loaded; a structure's only its blocks (see summarizeContent).
            AcousticUpdateGate.registerTerrainIdentity(frozen, frozen.fingerprints, structure != null ? LongSet.of() : frozen.chunks,
                    SectionGeometryCache.foldHash(box, frozen.minSection(), frozen.maxSection()));
            return frozen;
        }
        /**
         * A structure's mesh depends on its non-air sections only: which empty plot chunks a
         * rotated capture box happens to reach must not invalidate it.
         */
        private void summarizeContent() {
            contentKey = AcousticUpdateGate.contentHash(fingerprints, LongSet.of());
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (LongIterator keys = fingerprints.keySet().iterator(); keys.hasNext(); ) {
                long key = keys.nextLong();
                x0 = Math.min(x0, SectionPos.x(key));
                x1 = Math.max(x1, SectionPos.x(key));
                y0 = Math.min(y0, SectionPos.y(key));
                y1 = Math.max(y1, SectionPos.y(key));
                z0 = Math.min(z0, SectionPos.z(key));
                z1 = Math.max(z1, SectionPos.z(key));
            }
            if (x0 <= x1) contentBounds = new AABB(x0 << 4, y0 << 4, z0 << 4, (x1 + 1) << 4, (y1 + 1) << 4, (z1 + 1) << 4);
        }
        /** See {@link ReflectionGeometry#terrainSection}. */
        long sectionState(long key) {
            int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return ReflectionGeometry.UNCAPTURED;
            if (!chunks.contains(ChunkPos.asLong(x, z))) return ReflectionGeometry.UNLOADED;
            return fingerprints.getOrDefault(key, ReflectionGeometry.AIR);
        }
        int minSection() {
            return minimum >> 4;
        }
        /** Exclusive upper bound of the world's section range. */
        int maxSection() {
            return (minimum + height) >> 4;
        }
        boolean known(BlockPos pos) {
            return plot || chunks.contains(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        }
        @Override public PalettedContainer<BlockState> section(int x, int y, int z) {
            return sections.get(SectionPos.asLong(x, y, z));
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            var section = sections.get(SectionPos.asLong(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
            return section == null ? Blocks.AIR.defaultBlockState() : section.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public int getHeight() { return height; }
        @Override public int getMinBuildHeight() { return minimum; }
    }
}
