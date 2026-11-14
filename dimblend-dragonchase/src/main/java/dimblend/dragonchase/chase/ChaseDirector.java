package dimblend.dragonchase.chase;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.DimBlendRegistries;
import dimblend.api.BandTrainMarker;
import dimblend.api.RegionLanes;
import dimblend.api.RegionOverrides;
import dimblend.api.WarpGateColumns;
import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.api.DragonChaseTuning;
import dimblend.dragonchase.carriage.DragonCarriages;
import dimblend.dragonchase.entity.ChaseDragonHandle;
import dimblend.dragonchase.entity.ChaseDragons;
import dimblend.dragonchase.entity.ChaseEntityTickets;
import dimblend.dragonchase.structure.CarKind;
import dimblend.dragonchase.structure.DragonCars;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.joml.Vector3d;

/**
 * The chase director: a server-side state machine on the rotating level, ticked from
 * {@link LevelTickEvent.Post}, persisted in {@link DragonChaseSavedData}.
 *
 * <p>Order inside one tick is deliberate:
 * <ol>
 *   <li>prologue spawn/orbit/departure (§3.2),</li>
 *   <li>nest trigger and deferred build (§3.3),</li>
 *   <li>nest end checks: warp gate or disappeared (§3.3, §3.6),</li>
 *   <li>white-line override and the 2 m/s floor while a nest exists (§3.3),</li>
 *   <li>WAITING start, ACTIVE/WON rules, lose check (§3.3, §3.4, §3.6),</li>
 *   <li>breath zone crossing and the deferred fireball car (§3.4).</li>
 * </ol>
 *
 * <p>Everything here is server thread only. Nothing loads chunks: both deferred builds check
 * {@code DragonCars.footprintReady} first and retry on later ticks.
 */
public final class ChaseDirector {
    /** Reused box-centre scratch; this class only ever runs on the server thread. */
    private static final Vector3d CENTER = new Vector3d();
    /** The debug nest is queued this far ahead of the nearest corridor car. */
    private static final int DEBUG_NEST_AHEAD = 300;
    /** Deferred builds are attempted at most once per 0.2 s, so a dropped car lands promptly. */
    private static final long BUILD_RETRY_TICKS = 4L;
    /** A persistent director failure is logged at most this often (10 s), never once per tick. */
    private static final long ERROR_LOG_INTERVAL = 200L;
    /** Game time of the last logged director failure; {@code Long.MIN_VALUE} before the first. */
    private static long lastErrorLogTick = Long.MIN_VALUE;

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            try {
                tick(level);
            } catch (RuntimeException error) {
                long now = level.getGameTime();
                if (lastErrorLogTick == Long.MIN_VALUE || now - lastErrorLogTick >= ERROR_LOG_INTERVAL) {
                    lastErrorLogTick = now;
                    DimBlendDragonChase.LOGGER.error("Dragon Chase director tick failed in {}",
                            level.dimension().location(), error);
                }
            }
        }
    }

    /** A world switch must not leave a stale red marker behind in the next world's progress bar. */
    public static void onServerStopped(ServerStoppedEvent event) {
        BandTrainMarker.setOverride(null);
        ChaseSpawnRule.publish(false, 0.0D, 0.0D);
    }

    /**
     * Installed as {@code NonCrewCarriages}' force-load guard: true while the nest must keep its
     * loading tickets, i.e. from the moment it stands waiting for the train ({@code WAITING})
     * through the whole run ({@code ACTIVE}).
     *
     * <p>Without it {@code RegionSablePurger} would strip the nest's {@code COMMAND_FORCED} ticket
     * as soon as the chain sits outside every player's keep window and looks unmanned — the normal
     * case for a nest behind the train — unload it, and the missing-nest rule would abandon the
     * chase and burn that End region. A {@code WON} nest answers false on purpose: once the
     * rewards are paid, an abandoned nest behind the players may be cleaned up with the region.
     */
    public static boolean isNestForceLoadProtected(ServerLevel level, UUID subLevelId) {
        // The nest only ever exists in the corridor; answering false elsewhere keeps
        // DragonChaseSavedData from being created for a dimension that has no chase.
        if (!level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return false;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        return data.nestId != null
                && data.nestId.equals(subLevelId)
                && (data.phase == DragonChaseSavedData.Phase.WAITING
                        || data.phase == DragonChaseSavedData.Phase.ACTIVE);
    }

    private static void tick(ServerLevel level) {
        if (!level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            return;
        }
        int bandSize = RegionLanes.bandSize(level);
        if (bandSize <= 0) {
            return;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        long now = level.getGameTime();
        data.beginGrace(now);

        List<ChaseTrackScan.Car> all = ChaseTrackScan.forceLoaded(level);
        List<ChaseTrackScan.Car> corridor = ChaseTrackScan.corridor(level, all);

        tickPrologue(level, data, corridor, bandSize, now);
        tickNestQueue(level, data, corridor, bandSize, now);

        ServerSubLevel nest = ChaseTrackScan.byId(level, data.nestId);
        if (data.nestId != null) {
            if (nest == null) {
                // §2.7/§3.3: a load may still be restoring the Sable sub-level; only a nest that is
                // still missing once the grace has expired means the chase is over.
                if (data.graceElapsed(now)) {
                    abandonChase(level, data);
                }
            } else {
                if (!data.nestTicketEnsured) {
                    // A restart drops the nest's COMMAND_FORCED ticket; re-issue it exactly once.
                    DragonCars.ensureNestTicket(level, nest);
                    data.nestTicketEnsured = true;
                }
                if (WarpGateColumns.touchesGate(level, nest.boundingBox().toMojang())) {
                    // §3.3/§3.6: the gate dissolves the nest reward-wagon style and ends the chase.
                    DragonCarriages.dissolve(level, nest);
                    abandonChase(level, data);
                    nest = null;
                }
            }
        }

        tickMarker(data, nest);
        ChaseSpawnRule.publish(data.phase == DragonChaseSavedData.Phase.ACTIVE && nest != null,
                nest == null ? 0.0D : centerX(nest), nest == null ? 0.0D : centerZ(nest));
        tickSpeedFloor(level, all, nest, data.phase);
        tickChase(level, data, corridor, nest, bandSize, now);
        ChaseBreathZones.tick(level, data, all);
        tickPendingCar(level, data, now);
    }

    // ---------------------------------------------------------------- prologue (§3.2)

    private static void tickPrologue(ServerLevel level, DragonChaseSavedData data, List<ChaseTrackScan.Car> corridor,
            int bandSize, long now) {
        if (!data.prologueUsed && !data.prologueDeparting) {
            ChaseTrackScan.Car trigger = farthestAtOrPastPrologue(corridor, bandSize);
            if (trigger != null) {
                Vec3 pos = new Vec3(trigger.centerX(), ChaseMath.TRACK_Y + ChaseMath.PROLOGUE_HEIGHT, trigger.centerZ());
                ChaseDragonHandle dragon = ChaseDragons.spawnPrologue(level, pos);
                data.prologueUsed = true;
                data.prologueDragonId = dragon.uuid();
                data.prologueSpawnTime = now;
                data.prologueSlowTicks = 0;
                data.orbitX = trigger.centerX();
                data.orbitZ = trigger.centerZ();
                data.setDirty();
            }
        }

        ChaseDragonHandle dragon = data.prologueDragonId == null
                ? null
                : ChaseDragons.find(level, data.prologueDragonId);
        if (dragon instanceof Entity dragonEntity && now % 10L == 0L) {
            ChaseEntityTickets.keepTicking(level, dragonEntity);
        }
        if (dragon == null || !dragon.isAliveAndLoaded()) {
            if (data.prologueDragonId != null && prologueGone(level, data, now)) {
                data.prologueDragonId = null;
                data.prologueDeparting = false;
                data.setDirty();
            }
            return;
        }

        ChaseTrackScan.Car farthest = farthest(corridor);
        if (farthest != null) {
            dragon.setOrbitCenter(new Vec3(farthest.centerX(), farthest.centerY(), farthest.centerZ()),
                    ChaseMath.TRACK_Y);
            data.orbitX = farthest.centerX();
            data.orbitZ = farthest.centerZ();
        }
        if (data.prologueDeparting) {
            dragon.depart();
            return;
        }

        double departX = (double) ChaseMath.PROLOGUE_REGION * bandSize + ChaseMath.PROLOGUE_LOCAL_END;
        boolean beyond = farthest != null && farthest.centerX() >= departX;
        boolean slow = false;
        if (farthest != null && data.graceElapsed(now)
                && now >= data.prologueSpawnTime + ChaseMath.PROLOGUE_SPAWN_GRACE_TICKS) {
            if (farthest.vx() < ChaseMath.PROLOGUE_SLOW_SPEED) {
                data.prologueSlowTicks++;
            } else {
                data.prologueSlowTicks = 0;
            }
            slow = data.prologueSlowTicks >= ChaseMath.PROLOGUE_SLOW_TICKS;
        }
        if (beyond || slow || data.prologueDamageTaken) {
            data.prologueDeparting = true;
            data.setDirty();
            dragon.depart();
        }
    }

    /**
     * §3.2: a prologue dragon that is merely unloaded is not a departed one. It is gone only when
     * the raw entity was removed without being saved (killed, discarded) — or when its id no longer
     * resolves while the orbit chunk is entity-loaded and the load grace has expired.
     *
     * <p>Clearing eagerly would erase the prologue and its "Free the End" advancement whenever the
     * entity's chunk briefly unloads.
     */
    private static boolean prologueGone(ServerLevel level, DragonChaseSavedData data, long now) {
        Entity raw = level.getEntity(data.prologueDragonId);
        if (raw != null) {
            return raw.isRemoved()
                    && (raw.getRemovalReason() == null || !raw.getRemovalReason().shouldSave());
        }
        BlockPos orbit = BlockPos.containing(data.orbitX, ChaseMath.TRACK_Y, data.orbitZ);
        return data.graceElapsed(now) && level.areEntitiesLoaded(ChunkPos.asLong(orbit));
    }

    private static ChaseTrackScan.Car farthestAtOrPastPrologue(List<ChaseTrackScan.Car> corridor, int bandSize) {
        ChaseTrackScan.Car best = null;
        for (ChaseTrackScan.Car car : corridor) {
            int region = ChaseMath.regionOf(car.centerX(), bandSize);
            if (!ChaseMath.prologueThresholdReached(region, ChaseMath.localX(car.centerX(), bandSize))) {
                continue;
            }
            if (best == null || car.centerX() > best.centerX()) {
                best = car;
            }
        }
        return best;
    }

    private static ChaseTrackScan.Car farthest(List<ChaseTrackScan.Car> corridor) {
        ChaseTrackScan.Car best = null;
        for (ChaseTrackScan.Car car : corridor) {
            if (best == null || car.centerX() > best.centerX()) {
                best = car;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- nest trigger (§3.3)

    private static void tickNestQueue(ServerLevel level, DragonChaseSavedData data, List<ChaseTrackScan.Car> corridor,
            int bandSize, long now) {
        if (data.pendingNestX == null) {
            if (data.phase != DragonChaseSavedData.Phase.IDLE) {
                return;
            }
            ChaseTrackScan.Car candidate = nestCandidate(level, data, corridor, bandSize);
            if (candidate == null) {
                return;
            }
            int region = ChaseMath.regionOf(candidate.centerX(), bandSize);
            data.pendingNestX = region * bandSize + (int) ChaseMath.NEST_LOCAL_X;
            data.nestRegion = region;
            data.phase = DragonChaseSavedData.Phase.WAITING;
            data.pendingNestRetryAt = 0L;
            data.setDirty();
        }
        if (now < data.pendingNestRetryAt) {
            return;
        }
        data.pendingNestRetryAt = now + BUILD_RETRY_TICKS;
        int x = data.pendingNestX;
        if (!DragonCars.footprintReady(level, CarKind.NEST, x)) {
            return;
        }
        ServerSubLevel nest = DragonCars.buildNest(level, x);
        if (nest == null) {
            return;
        }
        data.nestId = nest.getUniqueId();
        data.nestCenterX = x;
        data.pendingNestX = null;
        data.usedNestRegions.add(data.nestRegion);
        data.lastNestCenterX = Double.NaN;
        data.setDirty();
    }

    private static ChaseTrackScan.Car nestCandidate(ServerLevel level, DragonChaseSavedData data,
            List<ChaseTrackScan.Car> corridor, int bandSize) {
        for (ChaseTrackScan.Car car : corridor) {
            int region = ChaseMath.regionOf(car.centerX(), bandSize);
            if (region < ChaseMath.FIRST_NEST_REGION) {
                continue;
            }
            if (ChaseMath.localX(car.centerX(), bandSize) >= ChaseMath.NEST_LOCAL_X) {
                continue;
            }
            // §3.3/§3.4: a region already forced to barren End (or overridden barren by hand) must
            // never sprout a natural nest later.
            if (data.forcedEndRegions.contains(region) || RegionOverrides.isBarren(region)) {
                continue;
            }
            boolean eligible = ChaseMath.nestRegionEligible(region, RegionLanes.laneName(level, region),
                    RegionLanes.laneName(level, region - 1), RegionLanes.laneName(level, region + 1), false,
                    data.usedNestRegions.contains(region));
            if (!eligible) {
                continue;
            }
            return car;
        }
        return null;
    }

    // ---------------------------------------------------------------- marker and floor (§3.3)

    private static void tickMarker(DragonChaseSavedData data, ServerSubLevel nest) {
        if (nest != null) {
            BandTrainMarker.setOverride(new BandTrainMarker.Marker(centerX(nest), true));
            data.markerSet = true;
        } else {
            clearMarker(data);
        }
    }

    private static void clearMarker(DragonChaseSavedData data) {
        if (data.markerSet) {
            BandTrainMarker.setOverride(null);
            data.markerSet = false;
        }
    }

    /**
     * §3.3: while a nest exists, every force-loaded sub-level below 2 m/s is topped up to 2 m/s —
     * except idle station-depot spares (assumption Q1) and consists the ender brake holds (§2.3).
     *
     * <p>In ACTIVE the nest is skipped here: {@link #tickChase} already raises it to its keep-away
     * target, and two deltas measured against the same stale velocity would overshoot it. WAITING
     * skips it too, so a queued-to-parked nest stays parked where it was built until the train
     * approaches. Only in WON does the floor push the nest on toward a warp gate (§3.6).
     */
    private static void tickSpeedFloor(ServerLevel level, List<ChaseTrackScan.Car> all, ServerSubLevel nest,
            DragonChaseSavedData.Phase phase) {
        if (nest == null || !DragonChaseTuning.speedFloorEnabled()) {
            return;
        }
        UUID nestId = nest.getUniqueId();
        for (ChaseTrackScan.Car car : all) {
            if ((phase == DragonChaseSavedData.Phase.ACTIVE || phase == DragonChaseSavedData.Phase.WAITING)
                    && car.id().equals(nestId)) {
                continue;
            }
            if (car.vx() >= ChaseMath.SPEED_FLOOR) {
                continue;
            }
            if (ChaseTrackScan.isIdleDepotCar(level, car.id()) || ChaseTrackScan.isBrakeLatched(level, car.id())) {
                continue;
            }
            ChaseTrackScan.topUp(car.sub(), ChaseMath.topUpDelta(car.vx(), ChaseMath.SPEED_FLOOR));
        }
    }

    // ---------------------------------------------------------------- chase (§3.3, §3.4, §3.6)

    private static void tickChase(ServerLevel level, DragonChaseSavedData data, List<ChaseTrackScan.Car> corridor,
            ServerSubLevel nest, int bandSize, long now) {
        if (data.phase == DragonChaseSavedData.Phase.WAITING) {
            if (nest == null) {
                // A save edited or a nest removed between queue and build: do not wedge in WAITING.
                // During the post-load grace a Sable sub-level may simply still be restoring, so the
                // demotion waits until the grace has expired.
                if (data.pendingNestX == null && data.graceElapsed(now)) {
                    data.phase = DragonChaseSavedData.Phase.IDLE;
                    data.setDirty();
                }
                return;
            }
            startIfApproached(level, data, corridor, nest, now);
            return;
        }
        if (data.phase != DragonChaseSavedData.Phase.ACTIVE && data.phase != DragonChaseSavedData.Phase.WON) {
            return;
        }
        if (nest == null) {
            return;
        }

        double nestX = centerX(nest);
        double nestZ = centerZ(nest);
        data.nestCenterX = Mth.floor(nestX);
        ChaseTrackScan.Car leader = leaderBehind(corridor, data.nestId, nestX);
        double nestVx = ChaseTrackScan.speedX(nest);
        double minSpeed = data.phase == DragonChaseSavedData.Phase.ACTIVE ? currentMinSpeed(data) : 0.0D;

        ChaseDragonHandle dragon = data.dragonId == null ? null : ChaseDragons.find(level, data.dragonId);
        if (dragon instanceof Entity dragonEntity && now % 10L == 0L) {
            ChaseEntityTickets.keepTicking(level, dragonEntity);
        }
        if (dragon != null && dragon.isAliveAndLoaded()) {
            Vec3 nestPos = new Vec3(nestX, ChaseMath.TRACK_Y, nestZ);
            Vec3 trainFront = new Vec3(leader != null ? leader.frontX() : nestX, ChaseMath.TRACK_Y, nestZ);
            dragon.setChaseAnchors(nestPos, trainFront);
            List<EndCrystal> crystals = DragonCarriages.aliveCrystals(level);
            dragon.setHealingCrystals(crystals);
            // §3.4 crystal-heal: every crystal car's crystals heal, loaded or not, so the count is
            // the registry's live slots rather than only what this level currently holds.
            dragon.setHealingCrystalCount(DragonCarriages.healingCrystalCount(level));
            dragon.setBossBarName(Component.translatable("boss.dimblend_dragonchase.dragon",
                    ChaseMath.romanOrArabic(data.generation), ChaseMath.tenths(nestVx), ChaseMath.tenths(minSpeed)));
            dragon.setBossBarViewers(bossBarViewers(level, nestX, nestZ));
        }

        if (data.phase == DragonChaseSavedData.Phase.ACTIVE) {
            if (!Double.isNaN(data.lastNestCenterX) && nestX > data.lastNestCenterX) {
                data.nestDistance += nestX - data.lastNestCenterX;
            }
            data.lastNestCenterX = nestX;

            // §3.3/§3.4: the minimum speed holds even when no car is inside the keep-away window.
            double target = leader == null
                    ? minSpeed
                    : ChaseMath.nestTargetSpeed(minSpeed, nestX, leader.centerX(), leader.vx());
            ChaseTrackScan.topUp(nest, ChaseMath.topUpDelta(nestVx, target));

            forceBarrenEndAhead(level, data, nestX, bandSize);
        }

        // §3.3 decision nest-trigger: a chase that started and then lost the train behind it ends.
        // §3.6: after the kill the failure rule no longer applies, so WON never fails the chase that
        // already paid out its nest rewards.
        if (data.phase == DragonChaseSavedData.Phase.ACTIVE && data.graceElapsed(now)
                && !anyBehind(corridor, data.nestId, nestX)) {
            failChase(level, data);
        }
    }

    private static double currentMinSpeed(DragonChaseSavedData data) {
        return ChaseMath.minSpeed(DragonChaseTuning.minSpeedCap(), data.generation,
                DragonChaseTuning.generationSpeedStep(), data.nestDistance,
                DragonChaseTuning.minSpeedGrowthDistance());
    }

    private static void startIfApproached(ServerLevel level, DragonChaseSavedData data,
            List<ChaseTrackScan.Car> corridor, ServerSubLevel nest, long now) {
        double nestX = centerX(nest);
        for (ChaseTrackScan.Car car : corridor) {
            if (car.id().equals(data.nestId)) {
                continue;
            }
            if (Math.abs(car.centerX() - nestX) > ChaseMath.CHASE_START_DISTANCE) {
                continue;
            }
            startChase(level, data, nestX, now);
            return;
        }
    }

    private static void startChase(ServerLevel level, DragonChaseSavedData data, double nestX, long now) {
        DragonCarriages.retireHealing(level);
        int online = level.getServer().getPlayerList().getPlayerCount();
        float maxHealth = ChaseMath.maxHealth(online, DragonChaseTuning.healthMultiplier());
        Vec3 spawn = new Vec3(nestX - ChaseMath.CHASE_SPAWN_BACK, ChaseMath.TRACK_Y + ChaseMath.CHASE_SPAWN_UP, 0.0D);
        ChaseDragonHandle dragon = ChaseDragons.spawnChase(level, spawn, maxHealth);

        data.phase = DragonChaseSavedData.Phase.ACTIVE;
        data.dragonId = dragon.uuid();
        data.maxHealth = maxHealth;
        data.nestDistance = 0.0D;
        data.lastNestCenterX = nestX;
        data.startedAt = now;
        Arrays.fill(data.breathFired, false);
        data.fireballAccumulator = 0.0D;
        data.fireballCooldownUntil = 0L;
        data.setDirty();
    }

    /** §3.4: passing local 1024 of the nest's region forces the next region to barren End, once. */
    private static void forceBarrenEndAhead(ServerLevel level, DragonChaseSavedData data, double nestX, int bandSize) {
        int region = ChaseMath.regionOf(nestX, bandSize);
        if (ChaseMath.localX(nestX, bandSize) < ChaseMath.FORCED_END_LOCAL_X) {
            return;
        }
        int ahead = region + 1;
        if (data.forcedEndRegions.contains(ahead)) {
            return;
        }
        if (RegionOverrides.forceLane(level.getServer(), ahead, RegionOverrides.Override.END_BARREN)) {
            data.forcedEndRegions.add(ahead);
            data.setDirty();
        }
    }

    static ChaseTrackScan.Car leaderBehind(List<ChaseTrackScan.Car> corridor, UUID nestId, double nestX) {
        ChaseTrackScan.Car best = null;
        for (ChaseTrackScan.Car car : corridor) {
            if (car.id().equals(nestId) || car.centerX() >= nestX) {
                continue;
            }
            if (best == null || car.centerX() > best.centerX()) {
                best = car;
            }
        }
        return best;
    }

    private static boolean anyBehind(List<ChaseTrackScan.Car> corridor, UUID nestId, double nestX) {
        double limit = DragonChaseTuning.loseDistance();
        for (ChaseTrackScan.Car car : corridor) {
            if (car.id().equals(nestId)) {
                continue;
            }
            if (car.centerX() >= nestX - limit && car.centerX() < nestX) {
                return true;
            }
        }
        return false;
    }

    private static List<ServerPlayer> bossBarViewers(ServerLevel level, double nestX, double nestZ) {
        List<ServerPlayer> viewers = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (ChaseMath.withinRadius(player.getX(), player.getZ(), nestX, nestZ, ChaseMath.ADVANCEMENT_RADIUS)) {
                viewers.add(player);
            }
        }
        return viewers;
    }

    // ---------------------------------------------------------------- fireball car (§3.4)

    private static void tickPendingCar(ServerLevel level, DragonChaseSavedData data, long now) {
        if (data.pendingCarKind == null) {
            return;
        }
        if (now > data.pendingCarDeadline) {
            data.pendingCarKind = null;
            data.setDirty();
            return;
        }
        if (now < data.pendingCarRetryAt) {
            return;
        }
        data.pendingCarRetryAt = now + BUILD_RETRY_TICKS;
        CarKind kind = data.pendingCarKind;
        if (!DragonCars.footprintReady(level, kind, data.pendingCarX)) {
            return;
        }
        ServerSubLevel car = kind == CarKind.BRAKE
                ? DragonCars.buildBrakeCar(level, data.pendingCarX, data.pendingCarVx)
                : DragonCars.buildCrystalCar(level, data.pendingCarX, data.pendingCarVx);
        if (car != null) {
            data.pendingCarKind = null;
            data.setDirty();
        }
    }

    // ---------------------------------------------------------------- transition helpers

    /** §3.3/§3.6: discard the dragon (if alive), clear the marker and forget the chase. */
    private static void abandonChase(ServerLevel level, DragonChaseSavedData data) {
        discardDragon(level, data);
        DragonCarriages.retireHealing(level);
        clearMarker(data);
        data.forgetChase();
        ChaseSpawnRule.publish(false, 0.0D, 0.0D);
    }

    /**
     * §3.3 decision nest-trigger: the chase failed (train fell behind) or the debug command asked
     * for the failure branch. The region stays used.
     */
    public static void failChase(ServerLevel level, DragonChaseSavedData data) {
        discardDragon(level, data);
        DragonCarriages.retireHealing(level);
        ServerSubLevel nest = ChaseTrackScan.byId(level, data.nestId);
        if (nest != null) {
            DragonCarriages.removeNow(level, nest);
        }
        clearMarker(data);
        data.forgetChase();
        ChaseSpawnRule.publish(false, 0.0D, 0.0D);
    }

    /** §3.6: rewards, advancement and the next generation. The nest keeps fleeing until it is gone. */
    public static void killChase(ServerLevel level, DragonChaseSavedData data) {
        if (data.phase != DragonChaseSavedData.Phase.ACTIVE) {
            return;
        }
        DragonCarriages.retireHealing(level);
        ServerSubLevel nest = ChaseTrackScan.byId(level, data.nestId);
        data.phase = DragonChaseSavedData.Phase.WON;
        if (nest != null) {
            double nestX = centerX(nest);
            double nestZ = centerZ(nest);
            long seed = level.getSeed() * 31L + data.generation;
            DragonCars.placeNestRewards(level, nest, seed);
            ChaseAdvancements.grantWithin(level, nestX, nestZ, ChaseAdvancements.RESPAWN_DRAGON,
                    ChaseAdvancements.RESPAWN_DRAGON_CRITERION);
        }
        data.generation++;
        data.setDirty();
    }

    private static void discardDragon(ServerLevel level, DragonChaseSavedData data) {
        if (data.dragonId == null) {
            return;
        }
        ChaseDragonHandle dragon = ChaseDragons.find(level, data.dragonId);
        if (dragon != null) {
            dragon.discardSilently();
        }
        data.dragonId = null;
    }

    // ---------------------------------------------------------------- debug entry points

    /** {@code /dragonchase prologue}: spawn at the nearest force-loaded sub-level, ignoring region rules. */
    public static boolean debugPrologue(ServerLevel level, double nearX, double nearZ) {
        List<ChaseTrackScan.Car> all = new ArrayList<>();
        for (ChaseTrackScan.Car car : ChaseTrackScan.forceLoaded(level)) {
            if (!ChaseTrackScan.isIdleDepotCar(level, car.id())) {
                all.add(car);
            }
        }
        ChaseTrackScan.Car nearest = nearest(all, nearX, nearZ);
        if (nearest == null) {
            return false;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        if (data.prologueDragonId != null) {
            ChaseDragonHandle previous = ChaseDragons.find(level, data.prologueDragonId);
            if (previous != null) {
                previous.discardSilently();
            }
        }
        Vec3 pos = new Vec3(nearest.centerX(), ChaseMath.TRACK_Y + ChaseMath.PROLOGUE_HEIGHT, nearest.centerZ());
        ChaseDragonHandle dragon = ChaseDragons.spawnPrologue(level, pos);
        data.prologueUsed = true;
        data.prologueDragonId = dragon.uuid();
        data.prologueDeparting = false;
        data.prologueDamageTaken = false;
        data.prologueSpawnTime = level.getGameTime();
        data.prologueSlowTicks = 0;
        data.orbitX = nearest.centerX();
        data.orbitZ = nearest.centerZ();
        data.setDirty();
        return true;
    }

    /** {@code /dragonchase nest}: queue one 300 blocks ahead of the nearest corridor car, ignoring rules. */
    public static boolean debugNest(ServerLevel level, double nearX, double nearZ) {
        int bandSize = RegionLanes.bandSize(level);
        if (bandSize <= 0) {
            return false;
        }
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        discardDragon(level, data);
        ServerSubLevel old = ChaseTrackScan.byId(level, data.nestId);
        if (old != null) {
            DragonCarriages.removeNow(level, old);
        }
        clearMarker(data);
        data.forgetChase();

        ChaseTrackScan.Car nearest = nearest(ChaseTrackScan.corridor(level, ChaseTrackScan.forceLoaded(level)), nearX, nearZ);
        if (nearest == null) {
            return false;
        }
        int x = Mth.floor(nearest.centerX()) + DEBUG_NEST_AHEAD;
        data.pendingNestX = x;
        data.nestRegion = ChaseMath.regionOf(x, bandSize);
        data.phase = DragonChaseSavedData.Phase.WAITING;
        data.pendingNestRetryAt = 0L;
        data.setDirty();
        return true;
    }

    /** {@code /dragonchase fireball}: the §3.4 shot, cooldown and swoop included. */
    public static boolean debugFireball(ServerLevel level) {
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        ChaseDragonHandle dragon = data.dragonId == null ? null : ChaseDragons.find(level, data.dragonId);
        if (dragon == null || data.nestId == null) {
            return false;
        }
        Vec3 target = new Vec3(data.nestCenterX - ChaseMath.FIREBALL_BACK, ChaseMath.FIREBALL_Y, 0.0D);
        if (!dragon.fireFireballAt(target)) {
            return false;
        }
        data.fireballAccumulator = 0.0D;
        data.fireballCooldownUntil = level.getGameTime() + ChaseMath.FIREBALL_COOLDOWN_TICKS;
        dragon.beginSwoop(200);
        data.setDirty();
        return true;
    }

    /** {@code /dragonchase breath}: the §3.4 breath animation; the zone appears on its callback. */
    public static boolean debugBreath(ServerLevel level) {
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        ChaseDragonHandle dragon = data.dragonId == null ? null : ChaseDragons.find(level, data.dragonId);
        if (dragon == null || data.nestId == null) {
            return false;
        }
        breatheBehindNest(dragon, data.nestCenterX);
        return true;
    }

    /** {@code /dragonchase kill}: the kill branch without waiting for the last hit. */
    public static boolean debugKill(ServerLevel level) {
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        if (data.phase != DragonChaseSavedData.Phase.ACTIVE) {
            return false;
        }
        ChaseDragonHandle dragon = data.dragonId == null ? null : ChaseDragons.find(level, data.dragonId);
        if (dragon instanceof Entity entity && dragon.isAliveAndLoaded()) {
            // The real thing: hurt goes through the dragon's own death path, so the vanilla death
            // animation and sound play and onKilled -> killChase runs exactly once.
            entity.hurt(level.damageSources().genericKill(), Float.MAX_VALUE);
            return true;
        }
        killChase(level, data);
        discardDragon(level, data);
        return true;
    }

    /** {@code /dragonchase fail}: the lose branch. */
    public static boolean debugFail(ServerLevel level) {
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        if (data.nestId == null && data.dragonId == null) {
            return false;
        }
        failChase(level, data);
        return true;
    }

    /** {@code /dragonchase reset}: forget the chase, the prologue and the used regions. */
    public static void debugReset(ServerLevel level) {
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        discardDragon(level, data);
        DragonCarriages.clearAll(level);
        ChaseDragonHandle prologue = data.prologueDragonId == null
                ? null
                : ChaseDragons.find(level, data.prologueDragonId);
        if (prologue != null) {
            prologue.discardSilently();
        }
        clearMarker(data);
        data.forgetEverything();
        ChaseSpawnRule.publish(false, 0.0D, 0.0D);
    }

    public static void debugSetGeneration(ServerLevel level, int generation) {
        DragonChaseSavedData data = DragonChaseSavedData.get(level);
        data.generation = Math.max(1, generation);
        data.setDirty();
    }

    /** The breath segment §3.4 lays on the track: ~40 blocks behind the nest, from far to near. */
    static void breatheBehindNest(ChaseDragonHandle dragon, double nestX) {
        dragon.breatheOnto(new Vec3(nestX - ChaseMath.BREATH_BACK_FROM, ChaseMath.FIREBALL_Y, 0.0D),
                new Vec3(nestX - ChaseMath.BREATH_BACK_TO, ChaseMath.FIREBALL_Y, 0.0D));
    }

    private static ChaseTrackScan.Car nearest(List<ChaseTrackScan.Car> cars, double x, double z) {
        ChaseTrackScan.Car best = null;
        double bestDistance = Double.MAX_VALUE;
        for (ChaseTrackScan.Car car : cars) {
            double dx = car.centerX() - x;
            double dz = car.centerZ() - z;
            double distance = dx * dx + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = car;
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- shared geometry

    static double centerX(ServerSubLevel sub) {
        return sub.boundingBox().center(CENTER).x;
    }

    static double centerZ(ServerSubLevel sub) {
        return sub.boundingBox().center(CENTER).z;
    }

    /** The rotating level of the running server, or null (used by callbacks that only get a handle). */
    static ServerLevel rotatingLevel() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? null : server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
    }

    private ChaseDirector() {
    }
}
