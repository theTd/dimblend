package dimblend.experience.chassis;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.api.StationDepotSites;
import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Builds and Sable-assembles the R6 depot's two pre-placed chassis.
 *
 * <p><b>Why this is lazy and why it lives here.</b> Worldgen runs on a worker thread,
 * with no players and before Create's track graph exists, so it can only write plain
 * blocks — and a chassis needs configured block entities (bogeys, copycat beams) plus
 * Sable assembly, both of which belong to the server thread. The depot therefore
 * generates with its platform, sidings and furniture; the two chassis are built and
 * assembled here the first time a player comes within
 * {@link StationDepotAssemblyRule#TRIGGER_RADIUS} blocks.
 *
 * <p><b>Idempotent and retryable.</b> {@link StationDepotAssemblyData} remembers, per
 * site, whether the blocks were built and whether the sub-level was formed. A restart
 * can therefore never assemble a second car over the first, and a site that fails is
 * retried on a later tick up to {@link StationDepotAssemblyRule#MAX_ATTEMPTS} times.
 *
 * <p><b>Cheap when idle.</b> The distance test runs before anything else; a site with no
 * player nearby costs one squared-distance comparison and no chunk or block access.
 */
public final class StationDepotAssembly {
    /** Sites examined per server tick, so a save with many depots never pays for all of them. */
    private static final int SITES_PER_TICK = 2;
    private static int cursor;

    private StationDepotAssembly() {
    }

    /** Server-thread tick for the rotating dimension. */
    public static void tick(ServerLevel level) {
        if (!ChassisEvents.available()) {
            return;
        }
        var data = StationDepotAssemblyData.get(level);
        List<StationDepotAssemblyData.Site> sites = new ArrayList<>(data.sites);
        discoverNearbySites(level, data, sites);
        if (sites.isEmpty()) {
            return;
        }
        int examined = 0;
        for (int i = 0; i < sites.size() && examined < SITES_PER_TICK; i++) {
            var site = sites.get(Math.floorMod(cursor + i, sites.size()));
            if (!site.assembled) {
                examined++;
                try {
                    if (advance(level, data, site)) {
                        data.setDirty();
                    }
                } catch (RuntimeException failure) {
                    DimBlend.LOGGER.warn("Station depot chassis at {} failed (attempt {}): {}",
                            site.center, site.attempts, failure.toString());
                    failed(site);
                }
            } else {
                examined++;
                try {
                    if (verifyCar(level, data, site)) {
                        data.setDirty();
                    }
                } catch (RuntimeException failure) {
                    DimBlend.LOGGER.warn("Station depot car check at {} failed: {}",
                            site.center, failure.toString());
                }
            }
            // The junction's switch is fitted on the same visit: it needs the same
            // conditions (loaded chunks, a player near) and must not depend on the
            // chassis having succeeded.
            fitJunctionSwitch(level, data, site);
        }
        cursor += SITES_PER_TICK;
    }

    /**
     * Fits this boundary's junction furniture: the two merge curves first, then the switch
     * machine that works the carriage siding's merge.
     *
     * <p>The curves come first because the switch's exits are their ends: a machine placed
     * before the curves exist would bind to nothing. The switch is then placed once and
     * verified on later visits — Create binds its edge point on the block entity's next
     * tick, so "placed" and "usable" are two different moments and only the second one is
     * reported as done. See {@link StationDepotSwitch} for why the machine stands beside
     * the rails rather than above them, and why it works this junction.
     */
    private static void fitJunctionSwitch(ServerLevel level, StationDepotAssemblyData data,
            StationDepotAssemblyData.Site site) {
        if (!StationDepotAssemblyData.carriesJunction(site.center)) {
            return;
        }
        int boundaryX = site.center.getX();
        if (!StationDepotAssemblyData.fitsSwitch()) {
            fitCurves(level, data, boundaryX);
            return;
        }
        if (!fitCurves(level, data, boundaryX)) {
            return;
        }
        BlockPos machine = StationDepotSwitch.machine(boundaryX);
        retireStaleMachines(level, data, boundaryX, machine);
        if (data.switchFitted(machine)) {
            // 2026-11-04: "fitted" is a claim about the past, and the machine is only a block in
            // the world — a region that is cleared and regenerated takes it with it. Ask the world
            // instead, and let a machine that is gone be re-fitted (which also re-arms the report).
            var switchBlock = BuiltInRegistries.BLOCK.getOptional(StationDepotSwitch.SWITCH_ID).orElse(null);
            if (switchBlock != null && level.getBlockState(machine).is(switchBlock)) {
                return;
            }
            data.forgetSwitch(machine);
        }
        var result = StationDepotSwitch.fit(level, boundaryX);
        if (result.fitted()) {
            data.markSwitchFitted(machine);
            DimBlend.LOGGER.info("Station depot switch: {}", result.detail());
        } else if (result.done()) {
            if (data.firstSwitchReport(machine)) {
                DimBlend.LOGGER.warn("Station depot switch not fitted: {}", result.detail());
            }
        } else if (data.firstSwitchReport(machine)) {
            // Placed but not bound yet, or waiting for the rail: reported once so a dead
            // switch is visible in the log instead of only in the world.
            DimBlend.LOGGER.info("Station depot switch pending: {}", result.detail());
        }
    }

    /**
     * Takes away switch machines this depot placed at a junction it no longer works.
     *
     * <p><b>Why this exists.</b> The machine moved from the empty siding's merge to the
     * carriage siding's merge on 2026-11-02. A world generated before that move still
     * carries the old block, which would leave two machines standing at the junction — one
     * of them connected to nothing. Only positions this depot recorded are touched, so a
     * switch a player placed by hand is never removed.
     *
     * <p><b>Why it is scoped to one boundary.</b> A world can hold several depots at once
     * (2048, 8192, …), and every depot records its own machine in one shared list. The first
     * version of this method treated every recorded machine as its own stale one, so two
     * depots deleted each other's machine for ever: each fit re-placed its own, the other
     * deleted it again — 1192 blocks removed and re-placed over 39 seconds, most of it in
     * bursts of a hundred or more per second. That churn
     * mutated Create's rail graph while the Netty thread was encoding
     * {@code create:sync_rail_graph}, which threw {@code ConcurrentModificationException}
     * and dropped the player out of the world.
     */
    private static void retireStaleMachines(ServerLevel level, StationDepotAssemblyData data,
            int boundaryX, BlockPos current) {
        var switchBlock = BuiltInRegistries.BLOCK.getOptional(StationDepotSwitch.SWITCH_ID).orElse(null);
        if (switchBlock == null) {
            return;
        }
        for (BlockPos stale : data.switchMachines()) {
            if (stale.equals(current)) {
                continue;
            }
            if (!isMachineOf(boundaryX, stale)) {
                continue;
            }
            if (level.getBlockState(stale).is(switchBlock)) {
                level.setBlock(stale, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                DimBlend.LOGGER.info("Station depot switch: retired the machine at {} (it now works {})",
                        stale, current);
            }
            data.forgetSwitch(stale);
        }
    }

    /**
     * Whether {@code pos} lies inside the hall this boundary owns, i.e. whether this depot
     * could have put a machine there. Depots are {@code BAND_SIZE} blocks apart and a hall is
     * under a hundred wide, so no other depot's machine can answer true.
     */
    static boolean isMachineOf(int boundaryX, BlockPos pos) {
        int dx = pos.getX() - boundaryX;
        return dx >= StationDepotSites.CAVERN_MIN_DX && dx <= StationDepotSites.CAVERN_MAX_DX;
    }

    /**
     * Builds this boundary's two merge curves. Idempotent per curve, retried on later
     * visits while a failure is retryable, and warned about once per boundary — the
     * alternative is a siding that looks connected and is not.
     */
    private static boolean fitCurves(ServerLevel level, StationDepotAssemblyData data, int boundaryX) {
        boolean allDone = true;
        for (var curve : dimblend.api.StationDepotCurves.all(boundaryX)) {
            BlockPos key = new BlockPos(curve.fromX(), StationDepotSites.CHASSIS_Y - 1, curve.fromZ());
            if (data.curveAttached(key)) {
                continue;
            }
            var result = StationDepotCurveBuilder.build(level, boundaryX, curve);
            if (result.done()) {
                data.markCurveAttached(key);
                data.setDirty();
                continue;
            }
            allDone = false;
            if (result.retryable()) {
                data.warnOnceForCurve(key, DimBlend.LOGGER,
                        "Station depot curve {} not attachable yet ({}); retrying on the next visit",
                        curve, result.detail());
            } else {
                DimBlend.LOGGER.warn("Station depot curve {} failed permanently: {}", curve, result.detail());
            }
        }
        return allDone;
    }

    private static boolean advance(ServerLevel level, StationDepotAssemblyData data,
            StationDepotAssemblyData.Site site) {
        if (site.built || site.assembled) {
            // Nothing left to decide about a site that already started: the budget gates the
            // FIRST block only, so a half-built site always finishes.
            return advanceNow(level, site);
        }
        if (StationDepotAssemblyData.carriesJunction(site.center) && !carriageBudgetAllows(level, data, site)) {
            return false;
        }
        return advanceNow(level, site);
    }

    /**
     * The R6 gate: a depot car is only handed out while the save holds fewer than
     * {@code stationCarriageLimit} force-loaded frame-carrying Sable structures. Checked once,
     * before the first block, so a site never stalls half-built when the budget closes on it.
     *
     * <p>The requirement counts the save, not the dimension, and counts this mod's own
     * pre-placed cars too — so the x=32 power car and the car of an earlier depot are part of
     * the number a new depot sees.
     */
    private static boolean carriageBudgetAllows(ServerLevel level, StationDepotAssemblyData data,
            StationDepotAssemblyData.Site site) {
        if (!Config.isLoaded()) {
            return true;
        }
        int limit = Config.STATION_CARRIAGE_LIMIT.get();
        int existing = StationDepotCarriageBudget.countCarsEverywhere(level);
        if (StationDepotCarriageBudget.admits(existing, limit)) {
            return true;
        }
        if (data.firstBudgetReport(site.center)) {
            DimBlend.LOGGER.info(
                    "Station depot chassis at {} skipped: the save already holds {} force-loaded cars (limit {})",
                    site.center, existing, limit);
        }
        return false;
    }

    private static boolean advanceNow(ServerLevel level, StationDepotAssemblyData.Site site) {
        boolean loaded = ChassisSite.loaded(level, site.center);
        double distanceSq = nearestPlayerDistanceSq(level, site.center);
        boolean present = site.built && anyBlueprintBlockPresent(level, site);
        var step = StationDepotAssemblyRule.step(site.built, site.assembled, site.attempts, present,
                loaded, distanceSq);
        return switch (step) {
            case IDLE -> false;
            case GIVE_UP -> {
                site.assembled = true;
                yield true;
            }
            case BUILD -> build(level, site);
            case ASSEMBLE -> assemble(level, site);
        };
    }

    private static boolean build(ServerLevel level, StationDepotAssemblyData.Site site) {
        if (!ChassisSite.clear(level, site.center, Map.of(), false, true)) {
            BlockPos blocker = ChassisSite.firstObstruction(level, site.center);
            DimBlend.LOGGER.warn("Station depot chassis at {} is blocked{}", site.center,
                    blocker == null
                            ? " (a sub-level occupies the site, or its chunks are not loaded)"
                            : " by " + level.getBlockState(blocker) + " at " + blocker);
            return failed(site);
        }
        Map<BlockPos, BlockState> placed = new HashMap<>();
        for (var cell : site.blueprint.cells()) {
            BlockPos pos = site.center.offset(cell.x(), 0, cell.z());
            BlockState state = ChassisBlocks.state(cell);
            level.setBlock(pos, state, Block.UPDATE_ALL);
            // The shell is a carriage, but the player gets a car that drives itself.
            ChassisBlocks.configureBogey(level, pos, site.blueprint, cell,
                    StationDepotAssemblyData.drivesEndBogeys());
            placed.put(pos, state);
        }
        site.built = true;
        site.placed.putAll(placed);
        return true;
    }

    private static boolean assemble(ServerLevel level, StationDepotAssemblyData.Site site) {
        Map<BlockPos, BlockState> placed = site.placed.isEmpty()
                ? blueprintBlocks(level, site)
                : site.placed;
        if (placed.isEmpty()) {
            return failed(site);
        }
        if (!ChassisSite.clear(level, site.center, placed, false, true)) {
            return failed(site);
        }
        var centre = site.center;
        var car = SubLevelAssemblyHelper.assembleBlocks(level, centre, placed.keySet(),
                new BoundingBox3i(centre.getX() - 22, centre.getY(), centre.getZ() - 2,
                        centre.getX() + 22, centre.getY(), centre.getZ() + 2));
        if (car == null || car.isRemoved()) {
            return failed(site);
        }
        // Sable's plot has its own light engine and assembly never fills it; without this the
        // chassis renders and reloads fully black (see SablePlotRelight).
        dimblend.experience.compat.sable.SablePlotRelight.relight(level, car);
        SubLevelContainer.getContainer(level)
                .addForceLoadTicket(car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        site.assembled = true;
        site.carId = car.getUniqueId();
        site.placed.clear();
        return true;
    }

    /**
     * Checks that a site recorded as assembled still owns a real car, and puts the site back to
     * "not built" when it does not.
     *
     * <p><b>Why this exists.</b> {@code assembled} used to be a one-way claim: once written, no
     * visit ever looked at the car again. A car whose stored blocks were lost therefore stayed
     * invisible for ever — the 8192 depot on 2026-11-04 held a sub-level whose storage file was
     * 4096 zero bytes while a working depot's file was 22887, and nothing ever rebuilt it.
     *
     * <p><b>What counts as lost.</b> Only answers the world can give are trusted:
     * <ul>
     *   <li>the site's chunks are loaded, so "the car is not loaded" cannot be the explanation;</li>
     *   <li>the recorded sub-level is missing, or still exists with an empty plot (a shell).</li>
     * </ul>
     * A car the player coupled to a train is a <i>real</i> sub-level wherever it has been towed
     * to, so it is left alone: this never hands out a second car for one the player already took.
     *
     * @return true when the site was reset and needs a rebuild
     */
    private static boolean verifyCar(ServerLevel level, StationDepotAssemblyData data,
            StationDepotAssemblyData.Site site) {
        if (!ChassisSite.loaded(level, site.center)) {
            return false; // "not loaded" and "gone" are the same answer while the area is away
        }
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        var car = findCar(container, site);
        if (car != null) {
            var plot = car.getPlot().getBoundingBox();
            boolean empty = plot.maxX() < plot.minX() || plot.maxY() < plot.minY()
                    || plot.maxZ() < plot.minZ();
            if (!empty) {
                if (site.carId == null) {
                    // A record written before the identity was stored: adopt it now, so this site
                    // never has to guess by position again.
                    site.carId = car.getUniqueId();
                    return true;
                }
                return false; // the car is there and has blocks
            }
            // A shell: the pointer and the force-load ticket survive, the blocks do not. It has to
            // go before the site is rebuilt, or the rebuild would assemble a second body on top.
            container.removeSubLevel(car,
                    dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
            DimBlend.LOGGER.info("Station depot car at {} is empty; removed the shell", site.center);
        } else {
            DimBlend.LOGGER.info("Station depot car at {} is missing; rebuilding it", site.center);
        }
        site.assembled = false;
        site.built = false;
        site.attempts = 0;
        site.placed.clear();
        site.carId = null;
        return true;
    }

    /**
     * The sub-level this site assembled: by identity when the record has one, by world box when it
     * does not (a record written before the identity was stored).
     */
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel findCar(
            dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container,
            StationDepotAssemblyData.Site site) {
        if (site.carId != null) {
            var byId = container.getSubLevel(site.carId);
            return byId instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel server && !server.isRemoved()
                    ? server : null;
        }
        double x = site.center.getX();
        double y = site.center.getY();
        double z = site.center.getZ();
        for (dev.ryanhcode.sable.sublevel.ServerSubLevel candidate : container.getAllSubLevels()) {
            if (candidate.isRemoved()) {
                continue;
            }
            var box = candidate.boundingBox();
            if (x >= box.minX() && x <= box.maxX()
                    && y >= box.minY() && y <= box.maxY()
                    && z >= box.minZ() && z <= box.maxZ()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean failed(StationDepotAssemblyData.Site site) {        site.attempts++;
        if (site.attempts >= StationDepotAssemblyRule.MAX_ATTEMPTS) {
            DimBlend.LOGGER.warn("Station depot chassis at {} left unfinished after {} attempts",
                    site.center, site.attempts);
        }
        return true;
    }

    private static boolean anyBlueprintBlockPresent(ServerLevel level, StationDepotAssemblyData.Site site) {
        for (var cell : site.blueprint.cells()) {
            if (!level.getBlockState(site.center.offset(cell.x(), 0, cell.z())).isAir()) {
                return true;
            }
        }
        return false;
    }

    private static Map<BlockPos, BlockState> blueprintBlocks(ServerLevel level, StationDepotAssemblyData.Site site) {
        Map<BlockPos, BlockState> placed = new HashMap<>();
        for (var cell : site.blueprint.cells()) {
            BlockPos pos = site.center.offset(cell.x(), 0, cell.z());
            BlockState state = level.getBlockState(pos);
            if (!state.isAir()) {
                placed.put(pos, state);
            }
        }
        return placed;
    }

    /**
     * Adds a record for every depot boundary near a player. A site is only ever
     * discovered through a boundary the generator itself says carries a depot, so the
     * two modules cannot disagree about where depots are.
     */
    private static void discoverNearbySites(ServerLevel level, StationDepotAssemblyData data,
            List<StationDepotAssemblyData.Site> sites) {
        int bandSize = bandSize(level);
        if (bandSize <= 0) {
            return;
        }
        double radiusSq = (double) StationDepotAssemblyRule.TRIGGER_RADIUS
                * (double) StationDepotAssemblyRule.TRIGGER_RADIUS;
        for (ServerPlayer player : level.players()) {
            Vec3 position = player.position();
            int playerX = player.getBlockX();
            int reach = StationDepotAssemblyRule.TRIGGER_RADIUS;
            int first = Math.floorDiv(playerX - reach, bandSize);
            int last = Math.floorDiv(playerX + reach, bandSize);
            for (int boundary = first; boundary <= last; boundary++) {
                int boundaryX = boundary * bandSize;
                if (!carriesDepot(level, boundaryX, bandSize)) {
                    continue;
                }
                addIfNear(data, sites, position, radiusSq,
                        StationDepotAssemblyData.sidingCenter(boundaryX),
                        StationDepotAssemblyData.sidingBlueprint());
            }
            if (handOutPowerChassis(level)) {
                addIfNear(data, sites, position, radiusSq,
                        StationDepotAssemblyData.powerCenter(), StationDepotAssemblyData.powerBlueprint());
            }
        }
    }

    /**
     * Whether this world may receive the free power chassis at X=32.
     *
     * <p>R6 asks for it in a <b>newly created world</b>. The marker for that already exists:
     * {@code StarterDepot} sets it once, when the overworld's spawn position is created, and
     * copies it to the rotating generator. Without this check any older save would grow a car
     * the moment somebody walked past X=32 — a structure the player never asked for, in a
     * world whose layout they already live in.
     */
    private static boolean handOutPowerChassis(ServerLevel level) {
        return level.getChunkSource().getGenerator()
                instanceof dimblend.worldgen.RotatingChunkGenerator rotating && rotating.hasStarterDepot();
    }

    private static void addIfNear(StationDepotAssemblyData data, List<StationDepotAssemblyData.Site> sites,
            Vec3 playerPosition, double radiusSq, BlockPos centre, ChassisBlueprint blueprint) {
        if (playerPosition.distanceToSqr(Vec3.atCenterOf(centre)) > radiusSq) {
            return;
        }
        var site = data.site(centre, blueprint);
        if (!sites.contains(site)) {
            sites.add(site);
        }
    }

    private static boolean carriesDepot(ServerLevel level, int boundaryX, int bandSize) {
        if (!(level.getChunkSource().getGenerator()
                instanceof dimblend.worldgen.RotatingChunkGenerator rotating)) {
            return false;
        }
        var layout = rotating.layoutOrNull();
        if (layout == null) {
            return false;
        }
        return StationDepotSites.buildsDepot(boundaryX, bandSize,
                region -> layout.laneName(layout.delegate(region)));
    }

    private static double nearestPlayerDistanceSq(ServerLevel level, BlockPos centre) {
        Vec3 position = Vec3.atCenterOf(centre);
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            best = Math.min(best, player.position().distanceToSqr(position));
        }
        return best;
    }

    private static int bandSize(ServerLevel level) {
        return level.getChunkSource().getGenerator()
                instanceof dimblend.worldgen.RotatingChunkGenerator rotating ? rotating.bandSize() : 0;
    }
}