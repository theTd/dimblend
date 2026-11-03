package dimblend.worldgen;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.DimBlendRegistries;
import dimblend.api.WarpGateMotion;
import dimblend.api.WarpGateParticles;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Warp gate behaviour for Sable sub-levels: the sound, the purple particles, the +X push and
 * the refusal to cross backwards. User requirements, 2026-11-03.
 *
 * <p><b>Why this is a per-tick sweep and not a block callback.</b> A Sable sub-level is not an
 * entity moving through blocks: its blocks live in a plot parked far away and only its
 * <em>bounding box</em> moves through the world. Vanilla's {@code Block#entityInside} and
 * {@code animateTick} never see it, which is why all three requirements were reported missing.
 * The only place a crossing exists is the sub-level's world box against the gate's slab, so the
 * sweep compares those two boxes each tick.
 *
 * <p><b>Coordinates.</b> The gate is a real block of the <i>world</i>, so the slab is found by
 * the boundary-column rule in {@link RegionBoundaryWall} and the block read is a world read.
 * The sub-level's box is also a world box ({@code SubLevel#boundingBox()}). Nothing here goes
 * through the plot: the sub-level's own blocks are not what this looks at.
 *
 * <p><b>Units.</b> Sable's physics works in metres per second, so the requirement's 2 m/s is
 * used directly — see {@link WarpGateMotion}.
 */
public final class WarpGateSableCrossing {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sub-levels currently overlapping a gate, so the crossing effects fire once per entry. */
    private static final Set<UUID> INSIDE = ConcurrentHashMap.newKeySet();

    private WarpGateSableCrossing() {
    }

    /** One server tick, for every level; only the rotating dimension does any work. */
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            onServerTick(level);
        }
    }

    private static void onServerTick(ServerLevel level) {
        if (!ModList.get().isLoaded("sable")) {
            return;
        }
        if (level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        Sweep.tick(level);
    }

    /**
     * The sable-touching half. Kept in its own class so {@link WarpGateSableCrossing} can load
     * without sable installed — the same lazy-resolution pattern as
     * {@link dimblend.band.BandTrainLocator}.
     */
    private static final class Sweep {
        private Sweep() {
        }

        static void tick(ServerLevel level) {
            if (!(level.getChunkSource().getGenerator() instanceof RotatingChunkGenerator rotating)) {
                return;
            }
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }
            List<ServerSubLevel> subs = container.getAllSubLevels();
            for (ServerSubLevel sub : subs) {
                if (sub.isRemoved()) {
                    continue;
                }
                try {
                    crossOne(level, rotating, sub);
                } catch (RuntimeException failure) {
                    LOGGER.warn("Warp gate sweep failed for sub-level {}: {}",
                            sub.getUniqueId(), failure.toString());
                }
            }
        }

        /**
         * One sub-level against the gate.
         *
         * <p>The order matters. Blocking first means a backwards-crossing structure never also
         * gets the forward push that would fight it, and never plays the crossing effects for a
         * crossing the gate is refusing.
         */
        private static void crossOne(ServerLevel level, RotatingChunkGenerator rotating, ServerSubLevel sub) {
            AABB box = worldBox(sub);
            int slabX = overlappingGate(level, rotating, box);
            if (slabX < 0) {
                // Not in a gate: re-arm so a later crossing plays its effects again.
                INSIDE.remove(sub.getUniqueId());
                return;
            }
            RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle == null || !handle.isValid()) {
                return;
            }
            Vector3d velocity = handle.getLinearVelocity(new Vector3d());
            // "from +X toward -X": the structure's centre is still on the far side of the slab
            // while it moves backwards into it.
            boolean crossingFromPositiveX = box.getCenter().x >= slabX + 1.0;
            WarpGateMotion.Action action = WarpGateMotion.decide(
                    velocity.x, velocity.y, velocity.z, crossingFromPositiveX);

            if (action == WarpGateMotion.Action.BLOCK_NEGATIVE_X) {
                // Refuse the crossing: kill the backward component only. Zeroing the whole
                // velocity would also stop vertical and lateral motion, i.e. drop a moving train
                // out of the sky for the crime of backing into a gate.
                handle.addLinearAndAngularVelocity(
                        new Vector3d(-velocity.x, 0.0, 0.0), new Vector3d(0.0, 0.0, 0.0));
                return;
            }

            if (action == WarpGateMotion.Action.PUSH_PLUS_X) {
                double delta = WarpGateMotion.plusXDelta(velocity.x);
                if (delta > 0.0) {
                    handle.addLinearAndAngularVelocity(
                            new Vector3d(delta, 0.0, 0.0), new Vector3d(0.0, 0.0, 0.0));
                }
            }
            playCrossingEffects(level, sub, box, slabX);
        }

        /**
         * The sound and the doubled particle burst, once per sub-level per entry.
         *
         * <p>Guarded so a structure sitting still inside a gate for a hundred ticks does not play
         * a hundred sounds: {@link #INSIDE} holds the sub-levels currently overlapping a gate, so
         * the effects fire on the transition into it. Leaving re-arms, so a second crossing plays
         * again.
         *
         * <p>The track is ours, not a Sable user-data tag: {@code getUserDataTag()} is nullable
         * and has no creating accessor, so writing the flag there would throw on a sub-level the
         * game built without one.
         */
        private static void playCrossingEffects(ServerLevel level, ServerSubLevel sub, AABB box, int slabX) {
            if (!INSIDE.add(sub.getUniqueId())) {
                return;
            }
            Vec3 centre = box.getCenter();
            // Vanilla's nether portal sound, exactly as the requirement names it.
            level.playSound(null, centre.x, centre.y, centre.z,
                    SoundEvents.PORTAL_TRAVEL, SoundSource.BLOCKS, 1.0F, 1.0F);
            emitParticles(level, box, slabX, level.random);
        }

        /** Purple portal particles over the gate cells this sub-level overlaps. */
        private static void emitParticles(ServerLevel level, AABB box, int slabX, RandomSource random) {
            List<BlockPos> cells = overlappingCells(level, box, slabX);
            for (int index : WarpGateParticles.sampledIndices(cells.size())) {
                BlockPos cell = cells.get(index);
                for (int i = 0; i < WarpGateParticles.PARTICLES_PER_CELL; i++) {
                    double x = cell.getX() + random.nextDouble();
                    double y = cell.getY() + random.nextDouble();
                    double z = cell.getZ() + random.nextDouble();
                    double dx = (random.nextDouble() - 0.5) * 0.5;
                    double dy = (random.nextDouble() - 0.5) * 0.5;
                    double dz = (random.nextDouble() - 0.5) * 0.5;
                    level.sendParticles(ParticleTypes.PORTAL, x, y, z, 1, dx, dy, dz, 0.0);
                }
            }
        }
    }

    /** The sub-level's world-space box, as the vanilla box the gate's own maths uses. */
    private static AABB worldBox(ServerSubLevel sub) {
        return sub.boundingBox().toMojang();
    }

    /**
     * The X of the first gate slab this box overlaps, or -1.
     *
     * <p>{@link RegionBoundaryWall#overlappedBoundaryColumn} already answers exactly this for the
     * corridor cross-section; it is reused rather than re-derived so the sweep and the walking
     * player's safety net can never disagree about where a gate is.
     */
    private static int overlappingGate(ServerLevel level, RotatingChunkGenerator rotating, AABB box) {
        int slabX = RegionBoundaryWall.overlappedBoundaryColumn(rotating,
                box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ);
        if (slabX < 0) {
            return -1;
        }
        // The column rule says a gate exists at this X; confirm the cells the box really touches
        // are gate blocks, so a carved-out or replaced column is not treated as a gate.
        return overlappingCells(level, box, slabX).isEmpty() ? -1 : slabX;
    }

    /** The gate blocks this box overlaps inside slab column {@code slabX}. */
    private static List<BlockPos> overlappingCells(ServerLevel level, AABB box, int slabX) {
        BlockState gate = DimBlendRegistries.WARP_GATE.get().defaultBlockState();
        ResourceLocation gateId = BuiltInRegistries.BLOCK.getKey(gate.getBlock());
        List<BlockPos> out = new ArrayList<>();
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.floor(box.maxY);
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.floor(box.maxZ);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                cursor.set(slabX, y, z);
                if (!level.isLoaded(cursor)) {
                    continue;
                }
                if (BuiltInRegistries.BLOCK.getKey(level.getBlockState(cursor).getBlock()).equals(gateId)) {
                    out.add(cursor.immutable());
                }
            }
        }
        return out;
    }
}