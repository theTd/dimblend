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
import java.util.HashSet;
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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Warp gate behaviour for Sable sub-levels: the sound, the purple particles, the +X push, the
 * one-way rule, and the crew rule. User requirements 2026-11-03, corrected 2026-11-04 and by the
 * 2026-11-04 supplement.
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
 * <p><b>Velocity only, never position.</b> The first 2026-11-04 correction refused a backward
 * crossing by teleporting the body back outside the gate every tick. In game that fought the
 * physics solver, broke collision boxes and derailed the train. The supplement replaces every
 * refusal with a velocity rule — see {@link WarpGateMotion} — so nothing in this class ever moves
 * a body; it only adds an X velocity delta.
 *
 * <p><b>Units.</b> Sable's physics works in metres per second, so the requirement's 1 m/s is
 * used directly — see {@link WarpGateMotion}.
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
         * <p>The order matters, and it is the order {@link WarpGateMotion#decide} encodes: a
         * structure clear on the +X side is turned back out first, then a forward crossing is held
         * while the crew is split, and only then does the push apply — to a structure somebody is
         * riding. A crossing the gate refuses or holds never plays the crossing effects, because it
         * is not crossing.
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
            boolean crewAboard = crew.everySurvivalPlayerAboard(level);
            boolean rides = crew.playerRides(level, sub);
            WarpGateMotion.Action action = WarpGateMotion.decide(velocity.x, side, crewAboard, rides);

            double deltaX = WarpGateMotion.deltaX(action, velocity.x);
            if (deltaX != 0.0) {
                // Hold means "speed stays 0", which is the whole linear velocity; the two forward
                // rules only ever touch X, so a structure keeps its vertical and lateral motion and
                // is not dropped out of the sky for the crime of meeting a gate.
                boolean hold = action == WarpGateMotion.Action.HOLD_STILL;
                handle.addLinearAndAngularVelocity(
                        new Vector3d(deltaX, hold ? -velocity.y : 0.0, hold ? -velocity.z : 0.0),
                        new Vector3d(0.0, 0.0, 0.0));
            }
            if (action == WarpGateMotion.Action.HOLD_STILL || action == WarpGateMotion.Action.TURN_PLUS_X) {
                return;
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
    }

    /**
     * One tick's answer to two questions about the players, asked once.
     *
     * <p>Every sub-level in the dimension is swept every tick, so asking per sub-level would repeat
     * the same player walk dozens of times. Both answers come from one walk, memoised for the sweep
     * that asked for them.
     */
    private static final class CrewGate {
        private Boolean aboard;
        private Set<UUID> ridden;

        /**
         * Whether every <b>survival-mode</b> player is standing on a Sable sub-level.
         *
         * <p>The supplement narrows the rule to survival players: creative and spectator players may
         * leave the train without shutting the gate for everyone. A player in another dimension
         * cannot be standing on the sub-level that is crossing this gate, so only this dimension's
         * players are walked — counting the others would leave the gate shut whenever anyone is
         * elsewhere.
         */
        boolean everySurvivalPlayerAboard(ServerLevel level) {
            collect(level);
            return aboard;
        }

        /** Whether a player in any game mode is riding this very sub-level. */
        boolean playerRides(ServerLevel level, ServerSubLevel sub) {
            collect(level);
            return ridden.contains(sub.getUniqueId());
        }

        private void collect(ServerLevel level) {
            if (aboard != null) {
                return;
            }
            boolean all = true;
            Set<UUID> ids = new HashSet<>();
            for (ServerPlayer player : level.players()) {
                var riding = SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player);
                if (riding != null) {
                    ids.add(riding.getUniqueId());
                } else if (player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL) {
                    all = false;
                }
            }
            aboard = all;
            ridden = ids;
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
