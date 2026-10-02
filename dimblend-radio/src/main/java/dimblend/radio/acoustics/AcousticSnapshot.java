package dimblend.radio.acoustics;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.terrain.SectionGeometryCache;
import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
import dimblend.radio.acoustics.terrain.SectionBlockFingerprint;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
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
public final class AcousticSnapshot {
    private static final AcousticPaletteCache<BlockState> PALETTES = new AcousticPaletteCache<>();
    private record Frame(FrozenBlocks blocks, Pose3d pose, AABB worldBounds, AABB localBounds,
            SubLevelAccess liveStructure) { }
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

    public static void clearCache() { PALETTES.clear(); }

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

    public AcousticMesh.Data mesh(Vec3 listener, Vec3 source) {
        Vec3 origin = new Vec3(Math.floor(listener.x / 16) * 16, Math.floor(listener.y / 16) * 16, Math.floor(listener.z / 16) * 16);
        return mesh(new AABB(listener, source).inflate(ReflectionMeshCache.MARGIN), origin);
    }

    public AcousticMesh.Data mesh(AABB bounds) {
        Vec3 center = bounds.getCenter();
        return mesh(bounds, new Vec3(Math.floor(center.x / 16) * 16, Math.floor(center.y / 16) * 16, Math.floor(center.z / 16) * 16));
    }

    private AcousticMesh.Data mesh(AABB bounds, Vec3 origin) {
        AcousticMesh mesh = new AcousticMesh(origin);
        // Sodium only meshes sections its visibility traversal visits, so the mirror's coverage
        // is always partial: AUTO mixes exact render geometry with a voxel fill of the gaps.
        boolean mirror = TerrainGeometryMode.CURRENT != TerrainGeometryMode.VOXEL && SectionGeometryCache.active();
        if (!mirror) {
            mesh.append(terrain, bounds, emitter, null);
            logGeometrySource("voxel");
        } else {
            SectionGeometryCache.Coverage coverage = SectionGeometryCache.presentSections(bounds,
                    terrain.minSection(), terrain.maxSection(), TerrainGeometryMode.CURRENT == TerrainGeometryMode.SODIUM);
            mesh.appendSections(coverage.sections(), emitter);
            if (TerrainGeometryMode.CURRENT == TerrainGeometryMode.AUTO) {
                mesh.append(terrain, bounds, emitter, null, coverage.covered());
                logGeometrySource("render-mesh(" + coverage.sections().size() + " sections)+voxel");
            } else {
                logGeometrySource("render-mesh(" + coverage.sections().size() + " sections)");
            }
        }
        for (Frame frame : structures) mesh.append(frame.blocks, frame.localBounds, emitter, frame.pose);
        return mesh.data();
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

    private static final class FrozenBlocks implements BlockGetter {
        private final Map<Long, PalettedContainer<BlockState>> sections = new HashMap<>();
        private final Set<Long> chunks = new HashSet<>();
        private final int minimum;
        private final int height;
        private final boolean plot;
        private FrozenBlocks(Level level, boolean plot) {
            minimum = level.getMinBuildHeight();
            height = level.getHeight();
            this.plot = plot;
        }
        static FrozenBlocks capture(Level level, AABB box, SubLevelAccess structure, long generation) {
            var frozen = new FrozenBlocks(level, structure != null);
            Map<Long, Long> fingerprints = new HashMap<>();
            int minY = Math.max(level.getMinSection(), (int) Math.floor(box.minY) >> 4);
            int maxY = Math.min(level.getMaxSection() - 1, (int) Math.floor(box.maxY) >> 4);
            for (int x = (int) Math.floor(box.minX) >> 4; x <= ((int) Math.floor(box.maxX) >> 4); x++) {
                for (int z = (int) Math.floor(box.minZ) >> 4; z <= ((int) Math.floor(box.maxZ) >> 4); z++) {
                    if (structure != null && SableCompanion.INSTANCE.getContaining(level, x, z) != structure) continue;
                    var chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk == null || chunk instanceof EmptyLevelChunk) continue;
                    frozen.chunks.add(net.minecraft.world.level.ChunkPos.asLong(x, z));
                    for (int y = minY; y <= maxY; y++) {
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
                                fingerprints.put(key, copy.fingerprint());
                            }
                            if (validateMesh) SectionGeometryCache.expectBlocks(key, SectionBlockFingerprint.of(copy.blocks()::get));
                        }
                    }
                }
            }
            AcousticUpdateGate.registerTerrainIdentity(frozen, fingerprints, frozen.chunks,
                    SectionGeometryCache.foldHash(box, frozen.minSection(), frozen.maxSection()));
            return frozen;
        }
        int minSection() {
            return minimum >> 4;
        }
        /** Exclusive upper bound of the world's section range. */
        int maxSection() {
            return (minimum + height) >> 4;
        }
        boolean known(BlockPos pos) {
            return plot || chunks.contains(net.minecraft.world.level.ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
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
