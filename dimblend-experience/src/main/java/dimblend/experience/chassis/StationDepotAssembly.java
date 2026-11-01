package dimblend.experience.chassis;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicketType;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dimblend.api.StationDepotSites;
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
        retireStaleMachines(level, data, machine);
        if (data.switchFitted(machine)) {
            return;
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
     */
    private static void retireStaleMachines(ServerLevel level, StationDepotAssemblyData data,
            BlockPos current) {
        var switchBlock = BuiltInRegistries.BLOCK.getOptional(StationDepotSwitch.SWITCH_ID).orElse(null);
        if (switchBlock == null) {
            return;
        }
        for (BlockPos stale : data.switchMachines()) {
            if (stale.equals(current)) {
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
        if (!ChassisSite.clear(level, site.center, Map.of(), false)) {
            return failed(site);
        }
        Map<BlockPos, BlockState> placed = new HashMap<>();
        for (var cell : site.blueprint.cells()) {
            BlockPos pos = site.center.offset(cell.x(), 0, cell.z());
            BlockState state = ChassisBlocks.state(cell);
            level.setBlock(pos, state, Block.UPDATE_ALL);
            ChassisBlocks.configureBogey(level, pos, site.blueprint, cell);
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
        if (!ChassisSite.clear(level, site.center, placed, false)) {
            return failed(site);
        }
        var centre = site.center;
        var car = SubLevelAssemblyHelper.assembleBlocks(level, centre, placed.keySet(),
                new BoundingBox3i(centre.getX() - 22, centre.getY(), centre.getZ() - 2,
                        centre.getX() + 22, centre.getY(), centre.getZ() + 2));
        if (car == null || car.isRemoved()) {
            return failed(site);
        }
        SubLevelContainer.getContainer(level)
                .addForceLoadTicket(car, SubLevelLoadingTicketType.COMMAND_FORCED, Unit.INSTANCE);
        site.assembled = true;
        site.placed.clear();
        return true;
    }

    private static boolean failed(StationDepotAssemblyData.Site site) {
        site.attempts++;
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
            addIfNear(data, sites, position, radiusSq,
                    StationDepotAssemblyData.powerCenter(), StationDepotAssemblyData.powerBlueprint());
        }
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