package dimblend.worldgen;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.DimBlendRegistries;
import dimblend.api.WarpGateMotion;
import dimblend.api.WarpGateParticles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * Warp gate behaviour for Sable sub-levels: the sound, the purple particles, the +X push, the
 * refusal to cross backwards, and the rule that nobody crosses unless the whole crew is on a
 * sub-level. User requirements 2026-11-03, corrected 2026-11-04.
 *
 * <p><b>Why this is a per-tick sweep and not a block callback.</b> A Sable sub-level is not an
 * entity moving through blocks: its blocks live in a plot parked far away and only its
 * <em>bounding box</em> moves through the world. Vanilla's {@code Block#entityInside} and
 * {@code animateTick} never see it, which is why all three 2026-11-03 requirements were reported
 * missing. The only place a crossing exists is the sub-level's world box against the gate's slab,
 * so the sweep compares those two boxes each tick.
 *
 * <p><b>Coordinates.</b> The gate is a real block of the <i>world</i>, so the slab is found by
 * the boundary-column rule in {@link RegionBoundaryWall} and the block read is a world read.
 * The sub-level's box is also a world box ({@code SubLevel#boundingBox()}). Nothing here goes
 * through the plot: the sub-level's own blocks are not what this looks at.
 *
 * <p><b>Units.</b> Sable's physics works in metres per second, so the requirement's 1 m/s is
 * used directly — see {@link WarpGateMotion}.
 *
 * <p><b>Why the refusal moves the body and not only its velocity.</b> The 2026-11-04 correction
 * says enough thrust still pushed a structure backwards through the gate. Clearing the -X velocity
 * once per tick cannot stop that: the physics step runs before this sweep, so every tick the body
 * gains a little more westward displacement before the velocity is cleared again. The refusal is
 * therefore a position constraint — the body is put back outside the face it crossed (see
 * {@link #pin}) — and the velocity is cleared so it does not immediately cross again.
 */
public final class WarpGateSableCrossing {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sub-levels currently overlapping a gate, so the crossing effects fire once per entry. */
    private static final Set<UUID> INSIDE = ConcurrentHashMap.newKeySet();

    /** The last gate a sub-level was at, and which side of it the sub-level was clear of. */
    private static final Map<UUID, Track> TRACK = new ConcurrentHashMap<>();

    /** One sub-level's remembered side of one gate. */
    private record Track(int slabX, WarpGateMotion.Side side) {
    }

    /** Ticks between sweeps that drop records of sub-levels that no longer exist. */
    private static final int PRUNE_INTERVAL = 200;

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
            if (level.getGameTime() % PRUNE_INTERVAL == 0) {
                TRACK.keySet().removeIf(id -> container.getSubLevel(id) == null);
            }
            List<ServerSubLevel> subs = container.getAllSubLevels();
            CrewGate crew = new CrewGate();
            for (ServerSubLevel sub : subs) {
                if (sub.isRemoved()) {
                    TRACK.remove(sub.getUniqueId());
                    INSIDE.remove(sub.getUniqueId());
                    continue;
                }
                try {
                    crossOne(level, rotating, sub, crew);
                } catch (RuntimeException failure) {
                    LOGGER.warn("Warp gate sweep failed for sub-level {}: {}",
                            sub.getUniqueId(), failure.toString());
                }
            }
        }

        /**
         * One sub-level against the gate.
         *
         * <p>The order matters. The reverse refusal comes first, so a structure the gate already
         * released can never be pushed forward again; the crew rule comes next, because it can only
         * ever refuse a forward crossing, never open one.
         */
        private static void crossOne(ServerLevel level, RotatingChunkGenerator rotating, ServerSubLevel sub,
                CrewGate crew) {
            AABB box = worldBox(sub);
            int slabX = overlappingGate(level, rotating, box);
            if (slabX < 0) {
                // Not in a gate: re-arm the effects and let the remembered side follow the box out.
                INSIDE.remove(sub.getUniqueId());
                refreshClearSide(sub.getUniqueId(), box);
                return;
            }
            RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle == null || !handle.isValid()) {
                return;
            }
            Vector3d velocity = handle.getLinearVelocity(new Vector3d());
            WarpGateMotion.Side side = sideFor(sub.getUniqueId(), box, slabX);
            WarpGateMotion.Action action = WarpGateMotion.decide(
                    velocity.x, side, crew.everyPlayerAboard(level));

            if (action == WarpGateMotion.Action.BLOCK_REVERSE) {
                // Refuse the crossing: kill the backward component and put the body back east of
                // the gate. Vertical and lateral motion are untouched — zeroing the whole velocity
                // would drop a moving train out of the sky for the crime of backing into a gate.
                handle.addLinearAndAngularVelocity(
                        new Vector3d(-velocity.x, 0.0, 0.0), new Vector3d(0.0, 0.0, 0.0));
                pin(sub, handle, WarpGateMotion.reverseCorrection(box.minX, slabX));
                return;
            }

            if (action == WarpGateMotion.Action.BLOCK_NO_CREW) {
                // The gate is a wall while anyone is off a sub-level. Only the forward component is
                // cleared: a structure that was already inside and is drifting out keeps drifting.
                if (velocity.x > 0.0) {
                    handle.addLinearAndAngularVelocity(
                            new Vector3d(-velocity.x, 0.0, 0.0), new Vector3d(0.0, 0.0, 0.0));
                }
                pin(sub, handle, -WarpGateMotion.crewCorrection(box.maxX, slabX));
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
         * Puts the body {@code dx} further along +X, through the physics pipeline's own teleport.
         *
         * <p>This is the only way to make the refusal a real wall: an impulse is undone by the next
         * thrust, a position correction is not. The pose is the body's own, so the plot move is the
         * plot's business — the pipeline writes the logical pose as well.
         */
        private static void pin(ServerSubLevel sub, RigidBodyHandle handle, double dx) {
            if (dx == 0.0) {
                return;
            }
            Vector3dc position = sub.logicalPose().position();
            handle.teleport(new Vector3d(position.x() + dx, position.y(), position.z()),
                    sub.logicalPose().orientation());
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
            // 2026-11-04: the requirement names minecraft:block.portal.ambient, not the
            // nether-portal travel sound the first version played.
            level.playSound(null, centre.x, centre.y, centre.z,
                    SoundEvents.PORTAL_AMBIENT, SoundSource.BLOCKS, 1.0F, 1.0F);
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

        /**
         * Whether every player in this dimension is standing on a Sable sub-level.
         *
         * <p>The requirement is "若不是所有玩家（数量不限）均位于 sable 上 则不允许任何 sable 穿过".
         * Two readings are settled here:
         * <ul>
         *   <li><b>This dimension only.</b> A player in another dimension cannot be standing on the
         *       sub-level that is crossing this gate, so counting them would leave the gate shut
         *       for as long as anyone is anywhere else.</li>
         *   <li><b>Spectators do not count.</b> A spectator flies and cannot be on a sub-level;
         *       counting them would shut the gate for the whole session.</li>
         * </ul>
         * The same lookup decides whether a walking player may stand inside a gate cell
         * ({@code WarpGateBlock#mayPass}), so the two can never disagree about what "on a sub-level"
         * means.
         */
        private static boolean allPlayersAboard(ServerLevel level) {
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator()) {
                    continue;
                }
                if (SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player) == null) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * One tick's answer to "is the whole crew aboard", asked at most once.
     *
     * <p>Most dimensions have nothing near a gate, and every sub-level in the dimension is swept
     * every tick. Asking this per sub-level would repeat the same player walk dozens of times, so
     * the answer is memoised for the sweep that asked for it.
     */
    private static final class CrewGate {
        private Boolean aboard;

        boolean everyPlayerAboard(ServerLevel level) {
            if (aboard == null) {
                aboard = Sweep.allPlayersAboard(level);
            }
            return aboard;
        }
    }

    /** The side to judge this sub-level by, remembering the last side it was clear of. */
    private static WarpGateMotion.Side sideFor(UUID id, AABB box, int slabX) {
        Track track = TRACK.get(id);
        WarpGateMotion.Side side = track != null && track.slabX() == slabX
                ? track.side()
                : WarpGateMotion.sideOfCentre(box.getCenter().x, slabX);
        // A box that is entirely on one side of the slab fixes the side; one that straddles keeps
        // it, which is the whole point of remembering.
        WarpGateMotion.Side resolved = WarpGateMotion.sideOf(box.minX, box.maxX, slabX, side);
        if (track == null || track.slabX() != slabX || resolved != track.side()) {
            TRACK.put(id, new Track(slabX, resolved));
        }
        return resolved;
    }

    /**
     * Lets the remembered side follow a sub-level that has left the gate it was tracked at.
     *
     * <p>This is what notices that a structure which crossed forward is now clear on the far side:
     * by the time it is fully east it no longer overlaps a gate, so the sweep only sees it here.
     */
    private static void refreshClearSide(UUID id, AABB box) {
        Track track = TRACK.get(id);
        if (track == null) {
            return;
        }
        WarpGateMotion.Side resolved = WarpGateMotion.sideOf(box.minX, box.maxX, track.slabX(), track.side());
        if (resolved != track.side()) {
            TRACK.put(id, new Track(track.slabX(), resolved));
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
