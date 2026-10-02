package dimblend.radio.acoustics;

import dimblend.radio.DimBlendRadio;
import dimblend.radio.acoustics.terrain.SectionGeometryCache;
import dimblend.radio.acoustics.terrain.TerrainGeometryMode;
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
import net.minecraft.world.phys.shapes.CollisionContext;

/** Copies palettes and poses on the client thread; worker raycasts never touch Level. */
public final class AcousticSnapshot {
    private record Frame(FrozenBlocks blocks, Pose3d pose, AABB worldBounds, AABB localBounds,
            SubLevelAccess liveStructure) { }
    private final FrozenBlocks terrain;
    private final List<Frame> structures;
    private final BlockPos emitter;

    private AcousticSnapshot(FrozenBlocks terrain, List<Frame> structures, BlockPos emitter) {
        this.terrain = terrain;
        this.structures = structures;
        this.emitter = emitter;
    }

    public static AcousticSnapshot capture(Level level, Vec3 listener, BlockPos emitter) {
        return capture(level, listener, emitter, 96);
    }

    public static AcousticSnapshot capture(Level level, Vec3 listener, BlockPos emitter, double radius) {
        AABB bounds = new AABB(listener, listener).inflate(radius);
        FrozenBlocks terrain = FrozenBlocks.capture(level, bounds, null);
        List<Frame> frames = new ArrayList<>();
        for (SubLevelAccess structure : SableCompanion.INSTANCE.getAllIntersecting(level, new BoundingBox3d(bounds))) {
            Pose3d pose = new Pose3d(structure.logicalPose());
            AABB world = structure.boundingBox().toMojang().intersect(bounds).inflate(0.01);
            AABB local = new BoundingBox3d(world).transformInverse(pose).toMojang();
            frames.add(new Frame(FrozenBlocks.capture(level, local, structure), pose, world, local, structure));
        }
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, List.copyOf(frames), emitter.immutable());
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
        AcousticSnapshot snapshot = new AcousticSnapshot(terrain, List.copyOf(frames), emitter);
        AcousticUpdateGate.registerSnapshot(snapshot, terrain, frames.stream().map(Frame::blocks).toList(),
                frames.stream().map(Frame::liveStructure).map(SubLevelAccess::getUniqueId).toList(), frames.stream().map(Frame::pose).toList());
        return snapshot;
    }

    public AcousticMesh.Data mesh(Vec3 listener, Vec3 source) {
        Vec3 origin = new Vec3(Math.floor(listener.x / 16) * 16, Math.floor(listener.y / 16) * 16, Math.floor(listener.z / 16) * 16);
        AcousticMesh mesh = new AcousticMesh(origin);
        AABB bounds = new AABB(listener, source).inflate(24);
        // Sodium only meshes sections its visibility traversal visits, so the mirror's coverage
        // is always partial: AUTO mixes exact render geometry with a voxel fill of the gaps.
        boolean mirror = TerrainGeometryMode.CURRENT != TerrainGeometryMode.VOXEL && SectionGeometryCache.active();
        if (!mirror) {
            mesh.append(terrain, bounds, emitter, null);
            logGeometrySource("voxel");
        } else {
            SectionGeometryCache.Coverage coverage = SectionGeometryCache.presentSections(bounds,
                    terrain.minSection(), terrain.maxSection());
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
        return BlockGetter.traverseBlocks(from, to, blocks, (world, pos) -> {
            if (pos.equals(emitter) || world.isOutsideBuildHeight(pos)) return null;
            if (!world.known(pos)) return AcousticRay.unknown(Vec3.atCenterOf(pos));
            BlockState state = world.getBlockState(pos);
            if (state.isAir()) return null;
            try {
                var hit = state.getCollisionShape(world, pos, CollisionContext.empty()).clip(from, to, pos);
                return hit == null ? null : new AcousticRay(AcousticRay.Kind.HIT, hit.getLocation(),
                        Vec3.atLowerCornerOf(hit.getDirection().getNormal()), AcousticRaycaster.reflectivity(state));
            } catch (RuntimeException unsupportedShape) {
                // A modded shape that requires a live Level falls back to its solid voxel.
                var hit = net.minecraft.world.phys.shapes.Shapes.block().clip(from, to, pos);
                return hit == null ? null : new AcousticRay(AcousticRay.Kind.HIT, hit.getLocation(),
                        Vec3.atLowerCornerOf(hit.getDirection().getNormal()), AcousticRaycaster.reflectivity(state));
            }
        }, world -> AcousticRay.miss(to));
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
        static FrozenBlocks capture(Level level, AABB box, SubLevelAccess structure) {
            var frozen = new FrozenBlocks(level, structure != null);
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
                        if (!section.hasOnlyAir()) frozen.sections.put(SectionPos.asLong(x, y, z), section.getStates().copy());
                    }
                }
            }
            AcousticUpdateGate.registerTerrain(frozen, frozen.sections, frozen.chunks,
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
