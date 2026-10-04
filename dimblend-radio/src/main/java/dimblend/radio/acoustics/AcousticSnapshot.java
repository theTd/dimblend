package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dimblend.radio.SubLevelProjection;
import it.unimi.dsi.fastutil.HashCommon;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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

    /** A structure as the reflection scene sees it: the simulated radios' own blocks are left out. */
    private record Structure(Frame frame, Set<BlockPos> emitters) implements ReflectionGeometry.Body {
        @Override public UUID id() { return frame.liveStructure.getUniqueId(); }

        @Override public long contentKey() { return frame.blocks.contentKey ^ emitterKey(emitters); }

        @Override public Pose3dc pose() { return frame.pose; }

        /** Every captured non-air section, voxelized in plot coordinates. */
        @Override public AcousticMesh.Data localMesh(AcousticMesh.Workspace workspace) {
            AABB box = frame.blocks.contentBounds;
            if (box == null) return AcousticMesh.Data.empty(Vec3.ZERO);
            AcousticMesh mesh = new AcousticMesh(new Vec3(box.minX, box.minY, box.minZ), workspace);
            mesh.append(frame.blocks, box, emitters);
            return mesh.data();
        }
    }
    private final FrozenBlocks terrain;
    private final List<Frame> structures;
    /** Cells read as air: the radios this view is for (their sound starts inside them). */
    private final Set<BlockPos> emitters;
    private final long revision;
    private final AABB bounds;
    /** The coordinates reflection runs over this scene use ({@link #inFrameOf}). */
    private final AcousticFrame frame;

    private AcousticSnapshot(FrozenBlocks terrain, List<Frame> structures, Set<BlockPos> emitters, long revision, AABB bounds,
            AcousticFrame frame) {
        this.terrain = terrain;
        this.structures = structures;
        this.emitters = emitters;
        this.revision = revision;
        this.bounds = bounds;
        this.frame = frame;
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
        return forEmitters(Set.of(source.immutable()));
    }

    /** The same frozen scene with every one of {@code sources} read as air: the radios' shared reflection scene. */
    public AcousticSnapshot forEmitters(Collection<BlockPos> sources) {
        Set<BlockPos> cells = Set.copyOf(sources.stream().map(BlockPos::immutable).toList());
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, structures, cells, revision, bounds, frame);
        AcousticUpdateGate.copySnapshot(this, snapshot);
        return snapshot;
    }

    /**
     * The same scene in {@code structure}'s frame ({@link AcousticFrame}), placed where it is in
     * this one; the world's frame when {@code structure} is null or not in the scene.
     */
    public AcousticSnapshot inFrameOf(UUID structure) {
        AcousticFrame next = AcousticFrame.WORLD;
        for (Frame candidate : structures) {
            if (structure != null && candidate.liveStructure.getUniqueId().equals(structure)) next = AcousticFrame.of(structure, candidate.pose);
        }
        if (next.sameAxes(frame)) return this;
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, structures, emitters, revision, bounds, next);
        snapshot.register();
        return snapshot;
    }

    @Override public AcousticFrame frame() { return frame; }

    /** Where {@code structure} is in this scene (its frame to the world), or null when it is not in it. */
    public Pose3dc structurePose(UUID structure) {
        for (Frame candidate : structures) if (candidate.liveStructure.getUniqueId().equals(structure)) return candidate.pose;
        return null;
    }

    /** Some structure of the scene reaches into {@code box} (world coordinates). */
    public boolean structuresIntersect(AABB box) {
        for (Frame candidate : structures) if (candidate.worldBounds.intersects(box)) return true;
        return false;
    }

    /** The update gate's view of this scene: its content, and its structures' poses in its frame. */
    private void register() {
        AcousticUpdateGate.registerSnapshot(this, terrain, structures.stream().map(Frame::blocks).toList(),
                structures.stream().map(Frame::liveStructure).map(SubLevelAccess::getUniqueId).toList(),
                structures.stream().map(Frame::pose).toList(), frame);
    }

    /** Order-independent; one emitter keys as it always has. */
    private static long emitterKey(Set<BlockPos> emitters) {
        long key = 0;
        for (BlockPos emitter : emitters) key += HashCommon.mix(emitter.asLong());
        return key;
    }

    @Override public Set<BlockPos> emitters() { return emitters; }

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
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, List.copyOf(frames), Set.of(emitter.immutable()), revision, bounds,
                AcousticFrame.WORLD);
        snapshot.register();
        return snapshot;
    }

    /**
     * Freezes the terrain of {@code bounds} for a bake on another thread: no structures (they move;
     * the live direct path still occludes them), and without taking over the frame loop's palette
     * watch, which follows only the latest frame capture.
     */
    public static AcousticSnapshot captureTerrain(Level level, AABB bounds, BlockPos emitter) {
        FrozenBlocks terrain = FrozenBlocks.capture(level, bounds, null, 0);
        return new AcousticSnapshot(terrain, List.of(), Set.of(emitter.immutable()), AcousticSceneChanges.revision(), bounds,
                AcousticFrame.WORLD);
    }

    /**
     * Freezes {@code structure}'s blocks within {@code bounds} (plot coordinates) as the terrain of
     * a scene in the structure's own frame, for a bake on another thread: the structure stands still
     * there, and nothing else is in it. Plot columns that are not the structure's are open air.
     */
    public static AcousticSnapshot captureStructure(Level level, AABB bounds, BlockPos emitter, SubLevelAccess structure) {
        FrozenBlocks blocks = FrozenBlocks.capture(level, bounds, structure, 0);
        return new AcousticSnapshot(blocks, List.of(), Set.of(emitter.immutable()), AcousticSceneChanges.revision(), bounds,
                AcousticFrame.WORLD);
    }

    /** How a block cell lets sound and listeners through, for flood fills over captured terrain. */
    public enum Openness { OPEN, SOLID, UNKNOWN }

    /**
     * A block whose collision shape fills less than half the cell (doors, fences, panes, carpets,
     * plants, fluids) is open; the world below its floor is solid and above its ceiling open.
     * Cells outside the capture or in unloaded chunks are unknown.
     */
    public Openness terrainOpenness(int x, int y, int z) {
        if (y < terrain.minimum) return Openness.SOLID;
        if (y >= terrain.minimum + terrain.height) return Openness.OPEN;
        if (!terrain.captured(x, y, z)) return Openness.UNKNOWN;
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = terrain.getBlockState(pos);
        if (state.isAir()) return Openness.OPEN;
        double volume = 0;
        for (AABB box : AcousticVoxelTrace.cell(state, terrain, pos).shape().toAabbs()) {
            volume += box.getXsize() * box.getYsize() * box.getZsize();
        }
        return volume >= 0.5 ? Openness.SOLID : Openness.OPEN;
    }

    /**
     * Called on the client thread each frame: re-places the moving structures where this frame draws
     * them ({@link SubLevelProjection#framePose}), the frame whose camera is the listener, without
     * copying terrain palettes again.
     */
    public AcousticSnapshot currentPoses(float partialTick) {
        List<Frame> frames = new ArrayList<>();
        for (Frame frame : structures) {
            Pose3d pose = new Pose3d(SubLevelProjection.framePose(frame.liveStructure, partialTick));
            AABB bounds = new BoundingBox3d(frame.localBounds).transform(pose).toMojang();
            frames.add(new Frame(frame.blocks, pose, bounds, frame.localBounds, frame.liveStructure));
        }
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, List.copyOf(frames), emitters, revision, bounds, AcousticFrame.WORLD);
        snapshot.register();
        return snapshot;
    }

    /** The whole reflection scene around one listener as a single mesh (self-tests and GameTests). */
    public AcousticMesh.Data mesh(Vec3 listener, Vec3 source) {
        Vec3 origin = new Vec3(Math.floor(listener.x / 16) * 16, Math.floor(listener.y / 16) * 16, Math.floor(listener.z / 16) * 16);
        var workspace = new AcousticMesh.Workspace();
        AcousticMesh mesh = new AcousticMesh(origin, workspace);
        mesh.append(terrain, new AABB(listener, source).inflate(ReflectionMeshCache.MARGIN), emitters);
        for (ReflectionGeometry.Body body : bodies()) mesh.appendPlaced(body.localMesh(workspace), body.pose());
        return mesh.data();
    }

    @Override public AcousticMesh.Data terrainMesh(AABB bounds, Vec3 origin, AcousticMesh.Workspace workspace) {
        AcousticMesh mesh = new AcousticMesh(origin, workspace);
        mesh.append(terrain, bounds, emitters);
        return mesh.data();
    }

    @Override public long terrainSection(long key) { return terrain.sectionState(key); }

    @Override public int minSection() { return terrain.minSection(); }

    @Override public int maxSection() { return terrain.maxSection(); }

    @Override public List<? extends ReflectionGeometry.Body> bodies() {
        List<Structure> bodies = new ArrayList<>(structures.size());
        for (Frame frame : structures) bodies.add(new Structure(frame, emitters));
        return bodies;
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

    /**
     * {@link #cast} for a ray given in {@code frame}'s coordinates: traced through the whole scene
     * where it is now, its hit given back in the frame.
     */
    public AcousticRay castInFrame(AcousticFrame frame, Vec3 from, Vec3 to) {
        if (frame.world()) return cast(from, to);
        AcousticRay hit = cast(frame.toWorld(from), frame.toWorld(to));
        return new AcousticRay(hit.kind(), frame.toLocal(hit.position()), frame.directionToLocal(hit.normal()), hit.material(),
                hit.thickness(), hit.transmission());
    }

    private AcousticRay castLocal(FrozenBlocks blocks, Vec3 from, Vec3 to) {
        return AcousticVoxelTrace.cast(from, to, pos -> {
            if (emitters.contains(pos) || blocks.isOutsideBuildHeight(pos)) return null;
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
        /** Structures only: columns of the structure whose chunk was not loaded. */
        private final LongOpenHashSet missing = new LongOpenHashSet();
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
                    if (chunk == null || chunk instanceof EmptyLevelChunk) {
                        if (structure != null) frozen.missing.add(ChunkPos.asLong(x, z));
                        continue;
                    }
                    frozen.chunks.add(ChunkPos.asLong(x, z));
                    for (int y = frozen.minY; y <= frozen.maxY; y++) {
                        var section = chunk.getSection(level.getSectionIndexFromSectionY(y));
                        long key = SectionPos.asLong(x, y, z);
                        if (generation != 0 && section.getStates() instanceof AcousticPaletteVersion versioned) {
                            versioned.dimblend$observeAcoustics(generation);
                        }
                        if (!section.hasOnlyAir()) {
                            var copy = PALETTES.freeze(section.getStates());
                            frozen.sections.put(key, copy.blocks());
                            frozen.fingerprints.put(key, copy.fingerprint());
                        }
                    }
                }
            }
            if (structure != null) frozen.summarizeContent();
            // Terrain identity includes which chunks were loaded; a structure's only its blocks (see summarizeContent).
            AcousticUpdateGate.registerTerrainIdentity(frozen, frozen.fingerprints, structure != null ? LongSet.of() : frozen.chunks);
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
        /**
         * See {@link ReflectionGeometry#terrainSection}. In a structure's capture the plot columns
         * that are not the structure's are air.
         */
        long sectionState(long key) {
            int x = SectionPos.x(key), y = SectionPos.y(key), z = SectionPos.z(key);
            if (x < minX || x > maxX || y < minY || y > maxY || z < minZ || z > maxZ) return ReflectionGeometry.UNCAPTURED;
            if (!loadedColumn(x, z)) return ReflectionGeometry.UNLOADED;
            return fingerprints.getOrDefault(key, ReflectionGeometry.AIR);
        }
        private boolean loadedColumn(int x, int z) {
            long column = ChunkPos.asLong(x, z);
            return plot ? !missing.contains(column) : chunks.contains(column);
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
        /** Inside the captured section rows of a loaded chunk (in a structure's capture: of a captured column). */
        boolean captured(int x, int y, int z) {
            int row = y >> 4, column = x >> 4, line = z >> 4;
            return row >= minY && row <= maxY && column >= minX && column <= maxX && line >= minZ && line <= maxZ
                    && loadedColumn(column, line);
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
