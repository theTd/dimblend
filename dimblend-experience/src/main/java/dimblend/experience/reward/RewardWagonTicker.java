package dimblend.experience.reward;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.DimBlend;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The server-thread half of the reward wagon: discovering sites near players, building
 * them, watching their contents, and dissolving them.
 *
 * <p><b>Why the tick and not worldgen.</b> A wagon needs block entities with configured
 * data and a Sable sub-level, and neither can exist on a worldgen worker thread. This is
 * the same split the station depot chassis use.
 *
 * <p><b>What one tick costs when nothing is near.</b> One distance comparison per known
 * site. Discovery scans the region boundaries within reach of a player, which is at most a
 * couple of integers per player.
 */
public final class RewardWagonTicker {
    /** How close a player must be before a wagon is built. */
    public static final int TRIGGER_RADIUS = 96;
    /** Failed builds allowed per site before it is left alone. */
    public static final int MAX_ATTEMPTS = 8;
    /** Sites examined per tick, so a long save never pays for all of them at once. */
    private static final int SITES_PER_TICK = 2;

    private static int cursor;

    /** Sites whose wagon was missing on the previous visit, so the diagnostic logs once per streak. */
    private static final Set<Integer> REPORTED_MISSING = ConcurrentHashMap.newKeySet();

    private RewardWagonTicker() {
    }

    /** One server tick for the rotating dimension. */
    public static void tick(ServerLevel level) {
        if (!RewardWagonBuilder.inDimension(level)) {
            return;
        }
        if (!RewardWagonBlocks.available()) {
            return;
        }
        RewardWagonSites data = RewardWagonSites.get(level);
        discover(level, data);
        List<RewardWagonSites.Site> sites = data.snapshot();
        if (sites.isEmpty()) {
            return;
        }
        int examined = 0;
        for (int i = 0; i < sites.size() && examined < SITES_PER_TICK; i++) {
            RewardWagonSites.Site site = sites.get(Math.floorMod(cursor + i, sites.size()));
            if (site.dissolved) {
                continue;
            }
            examined++;
            try {
                if (advance(level, site)) {
                    data.setDirty();
                }
            } catch (RuntimeException failure) {
                DimBlend.LOGGER.warn("Reward wagon at X={} failed (attempt {}): {}",
                        site.centerX, site.attempts, failure.toString());
                site.attempts++;
                data.setDirty();
            }
        }
        cursor += SITES_PER_TICK;
    }

    /**
     * Records every wagon any player is close enough to.
     *
     * <p>Discovery is the generator's answer, not this class's: the plan for a region is a
     * pure function of the seed and the region index, so the two halves cannot disagree
     * about where a wagon belongs.
     */
    private static void discover(ServerLevel level, RewardWagonSites data) {
        if (level.players().isEmpty()) {
            return;
        }
        long seed = level.getSeed();
        int bandSize = bandSize(level);
        if (bandSize <= 0) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            int playerX = player.getBlockX();
            int reach = TRIGGER_RADIUS;
            int first = Math.floorDiv(playerX - reach, bandSize);
            int last = Math.floorDiv(playerX + reach, bandSize);
            for (int band = first; band <= last; band++) {
                String lane = laneName(level, band);
                if (lane == null) {
                    continue;
                }
                for (RewardWagonPlan.Wagon wagon : RewardWagonPlan.forBand(
                        seed, band, bandSize, lane, x -> reservedX(level, x))) {
                    if (Math.abs(wagon.centerX() - playerX) <= reach) {
                        data.site(wagon.centerX());
                    }
                }
            }
        }
    }

    /**
     * Advances one site by one step. Returns true when persisted state changed.
     *
     * <p><b>The countdown is a deadline, not a counter.</b> Sites are visited in rotation,
     * two per tick, so counting visits down would stretch the requirement's half second to
     * several seconds on a save with many wagons. The record holds the game time at which the
     * removal is due instead, and this compares it against the clock.
     */
    private static boolean advance(ServerLevel level, RewardWagonSites.Site site) {
        if (!site.built) {
            // The attempt cap is a <i>build</i> cap. A built wagon has nothing left to attempt,
            // and stopping here used to freeze one that had thrown eight times: it was never
            // looked at again, so it could never dissolve.
            if (site.attempts >= MAX_ATTEMPTS) {
                return false;
            }
            if (!nearPlayer(level, site.centerX, TRIGGER_RADIUS) || !chunksLoaded(level, site.centerX)) {
                return false;
            }
            return build(level, site);
        }
        ServerSubLevel car = existing(level, site);
        if (car == null) {
            // "Not found" is not "gone" (2026-11-04). A reward wagon can be coupled to a train and
            // towed away from the X it spawned at. Its sub-level then leaves the loaded set,
            // getSubLevel(uuid) answers null, and the old code wrote dissolved — which is
            // permanent, and which is exactly why a wagon that was towed away could be emptied and
            // never dissolve while a wagon emptied where it spawned vanished fine. A site is now
            // retired only by a dissolve that really ran; a miss is retried on the next visit.
            if (REPORTED_MISSING.add(site.centerX)) {
                // One line per missing streak, not per visit: an unloaded wagon is visited many
                // times a second and the diagnostic must not flood debug.log.
                DimBlend.LOGGER.debug("Reward wagon at X={} is not loaded right now; site kept",
                        site.centerX);
            }
            return false;
        }
        REPORTED_MISSING.remove(site.centerX);
        boolean changed = false;
        if (site.subLevelId == null) {
            // A record written before the identity was stored, found by its world box. Remember
            // the identity now, so a wagon that gets pushed never depends on geometry again.
            site.subLevelId = car.getUniqueId();
            changed = true;
        }
        if (site.dissolveDeadline > 0) {
            if (level.getGameTime() < site.dissolveDeadline) {
                return changed;
            }
            remove(level, site, car);
            site.dissolved = true;
            site.dissolveDeadline = 0;
            return true;
        }
        RewardWagonPlotScan.Result scan = scan(level, car);
        if (scan == null) {
            // The plot reports no usable box, so "nothing is left" cannot be told apart from
            // "nothing was read". Never dissolve on an unread wagon.
            return changed;
        }
        if (touchesGate(level, car) || RewardWagonDissolve.isEmpty(scan.occupants())) {
            beginDissolve(level, site, car, scan);
            return true;
        }
        return changed;
    }

    private static boolean build(ServerLevel level, RewardWagonSites.Site site) {
        String lane = laneName(level, Math.floorDiv(site.centerX, bandSize(level)));
        RewardWagonPlan.Wagon wagon = planFor(level, site.centerX, lane);
        if (wagon == null) {
            // Nothing belongs here any more: the plan moved on. Stop tracking the site.
            site.dissolved = true;
            return true;
        }
        ServerSubLevel car = RewardWagonBuilder.build(level, wagon);
        if (car == null) {
            site.attempts++;
            return true;
        }
        site.built = true;
        site.attempts = 0;
        site.subLevelId = car.getUniqueId();
        DimBlend.LOGGER.info("Reward wagon ({}) built at X={}", wagon.content(), site.centerX);
        return true;
    }

    /** Rebuilds the plan for one centre so the built wagon matches its content. */
    private static RewardWagonPlan.Wagon planFor(ServerLevel level, int centerX, String lane) {
        if (lane == null) {
            return null;
        }
        int bandSize = bandSize(level);
        int band = Math.floorDiv(centerX, bandSize);
        for (RewardWagonPlan.Wagon wagon : RewardWagonPlan.forBand(
                level.getSeed(), band, bandSize, lane, x -> reservedX(level, x))) {
            if (wagon.centerX() == centerX) {
                return wagon;
            }
        }
        return null;
    }

    /**
     * Starts the dissolve: break the couplers, play the effects, set the deadline.
     *
     * <p><b>The two halves of this method live in different coordinate spaces.</b> Breaking a
     * coupler is a block write, so it happens at the coupler's <i>plot</i> position. The burst is
     * something the player watches, so it happens at the bogey's <i>world</i> position. The
     * previous version derived both from {@code pose.transformPosition(Vec3(dx, 0, dz))}, i.e. a
     * world projection of a deck offset: that is tens of millions of blocks away, near the plot
     * origin. So the couplers were never broken and the effects played where nobody was. The
     * positions now come from the plot scan, and the world position is the forward transform of a
     * plot position, which is the direction Sable itself uses.
     */
    private static void beginDissolve(ServerLevel level, RewardWagonSites.Site site, ServerSubLevel car,
            RewardWagonPlotScan.Result scan) {
        for (RewardWagonPlotScan.Pos cell : scan.couplers()) {
            BlockPos plotPos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (level.getBlockEntity(plotPos) instanceof AutomaticCouplerBlockEntity coupler) {
                coupler.removePartner();
            }
            level.setBlock(plotPos, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        if (!site.effectsPlayed) {
            BlockPos worldBogey = scan.bogey() == null ? null : worldOf(car, scan.bogey());
            if (worldBogey != null) {
                RewardWagonBuilder.playDissolveEffects(level, worldBogey.below());
            }
            site.effectsPlayed = true;
        }
        site.dissolveDeadline = level.getGameTime() + RewardWagonDissolve.DISSOLVE_DELAY_TICKS;
        DimBlend.LOGGER.info("Reward wagon at X={} emptied; dissolving", site.centerX);
    }

    /**
     * The world position of one plot block, through the sub-level's pose.
     *
     * <p>This is the only pose transform the dissolve needs, and it runs plot to world, which is
     * what {@code logicalPose} is for. Reading or writing a block must never go this way: those
     * take the plot position the scan already produced.
     */
    private static BlockPos worldOf(ServerSubLevel car, RewardWagonPlotScan.Pos pos) {
        net.minecraft.world.phys.Vec3 world = car.logicalPose().transformPosition(
                net.minecraft.world.phys.Vec3.atCenterOf(new BlockPos(pos.x(), pos.y(), pos.z())));
        return BlockPos.containing(world);
    }

    /**
     * Deletes the sub-level.
     *
     * <p>{@code removeSubLevel} throws when the grid no longer holds that sub-level, so the two
     * ways that can be true are checked first: {@link #existing} already refuses a removed one,
     * and the container is the same one the lookup came from. A retry that threw every tick would
     * be worse than a missed removal, because it would log every tick as well.
     */
    private static void remove(ServerLevel level, RewardWagonSites.Site site, ServerSubLevel car) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || car.isRemoved()) {
            return;
        }
        container.removeSubLevel(car, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
    }

    /**
     * Reads the wagon's blocks out of its plot.
     *
     * <p><b>The plot's own box, not the world box.</b> {@code car.boundingBox()} is where the
     * wagon appears; its blocks are stored elsewhere, and only {@code car.getPlot().getBoundingBox()}
     * says where. The previous version walked the world box, mapped each cell into the plot, and
     * then tested ownership against the <i>world</i> box a second time — a comparison between two
     * different coordinate spaces, false for every cell, so every wagon read as empty.
     *
     * <p>Reading the plot box needs neither transform nor ownership test: that box already holds
     * this sub-level's blocks and nothing else.
     */
    private static RewardWagonPlotScan.Result scan(ServerLevel level, ServerSubLevel car) {
        var box = car.getPlot().getBoundingBox();
        // The plot's chunks travel with the sub-level, so this only bites if the plot itself is
        // mid-load — and reading blocks there would make the level generate them, which is far
        // worse than skipping a turn. Never conclude anything from a plot that cannot be read.
        if (!level.isLoaded(new BlockPos(box.minX(), box.minY(), box.minZ()))
                || !level.isLoaded(new BlockPos(box.maxX(), box.maxY(), box.maxZ()))) {
            return null;
        }
        return RewardWagonPlotScan.scan(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ(), (x, y, z) -> fillAt(level, x, y, z));
    }

    /** What one plot block is, as the dissolve rules see it. */
    private static RewardWagonPlotScan.Fill fillAt(ServerLevel level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return RewardWagonPlotScan.Fill.AIR;
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (id.equals(RewardWagonBlocks.COUPLER)) {
            return RewardWagonPlotScan.Fill.COUPLER;
        }
        if (id.equals(RewardWagonBlocks.BOGEY)) {
            return RewardWagonPlotScan.Fill.BOGEY;
        }
        if (id.equals(RewardWagonBlocks.COPYCAT_SLAB)) {
            return RewardWagonPlotScan.Fill.SLAB;
        }
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container) {
            return container.isEmpty()
                    ? RewardWagonPlotScan.Fill.EMPTY_CONTAINER
                    : RewardWagonPlotScan.Fill.FILLED_CONTAINER;
        }
        return RewardWagonPlotScan.Fill.CONTENT;
    }

    /**
     * True when the wagon's world box overlaps any warp gate block in the corridor.
     *
     * <p><b>A gate is a block of the world, so this is the one place a world read is correct.</b>
     * The wagon meets it by its world position, not by its plot position: the plot is parked
     * millions of blocks away and carries no gate. The block reads below are therefore meant to
     * be world reads, and the wagon's own blocks are deliberately not consulted.
     */
    private static boolean touchesGate(ServerLevel level, ServerSubLevel car) {
        var box = car.boundingBox();
        int minX = (int) Math.floor(box.minX());
        int maxX = (int) Math.floor(box.maxX());
        int minY = (int) Math.floor(box.minY());
        int maxY = (int) Math.floor(box.maxY());
        int minZ = (int) Math.floor(box.minZ());
        int maxZ = (int) Math.floor(box.maxZ());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        var gate = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(RewardWagonBlocks.WARP_GATE));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    // Only already-loaded world columns: a wagon towed far away must not make the
                    // server load (and generate) the chunks under it just to ask about a gate.
                    if (!level.isLoaded(cursor)) {
                        continue;
                    }
                    if (level.getBlockState(cursor).is(gate)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * The sub-level this site assembled into, if it is loaded.
     *
     * <p><b>Identity, not geometry — and never a world position.</b> {@code Sable.HELPER.getContaining}
     * is a <i>plot</i> lookup, not a world one: {@code SubLevelContainer.getPlot} divides its
     * argument by the chunk size, then by the plot size, then subtracts the plot-grid origin
     * ({@code DEFAULT_ORIGIN = 10000} plots), so only a plot coordinate can ever name a plot.
     * Sable's own callers hand it a point that has already been through
     * {@code pose.transformPositionInverse} — {@code BlockGetterMixin} compares
     * {@code getContaining(level, localFrom) == subLevel}. The old code passed the wagon's
     * <i>world</i> centre, so the answer was "no sub-level" for every wagon in every save, on
     * every tick: each site was retired as already gone a tick after it was built, and a retired
     * site is never visited again. That is why a reward wagon could not dissolve.
     *
     * <p>The UUID needs no such care. The box fallback below is only for records written before
     * the UUID was stored: it recognises a wagon by standing where its centre is.
     */
    private static ServerSubLevel existing(ServerLevel level, RewardWagonSites.Site site) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        if (site.subLevelId != null) {
            SubLevel byId = container.getSubLevel(site.subLevelId);
            return byId instanceof ServerSubLevel server && !server.isRemoved() ? server : null;
        }
        // Legacy record without an identity: geometry is the only clue, so it may only be read
        // where the geometry can actually be seen. Without this guard a merely unloaded home area
        // reads as "not there".
        if (!chunksLoaded(level, site.centerX)) {
            return null;
        }
        return coveringCenter(container, RewardWagonBuilder.center(site.centerX));
    }

    /** The loaded sub-level whose world box covers {@code center}, or null. */
    private static ServerSubLevel coveringCenter(SubLevelContainer container, BlockPos center) {
        double x = center.getX() + 0.5;
        double y = center.getY() + 0.5;
        double z = center.getZ() + 0.5;
        for (SubLevel candidate : container.getAllSubLevels()) {
            if (!(candidate instanceof ServerSubLevel server) || server.isRemoved()) {
                continue;
            }
            var box = server.boundingBox();
            if (x >= box.minX() && x <= box.maxX()
                    && y >= box.minY() && y <= box.maxY()
                    && z >= box.minZ() && z <= box.maxZ()) {
                return server;
            }
        }
        return null;
    }

    private static boolean nearPlayer(ServerLevel level, int centerX, int radius) {
        BlockPos center = RewardWagonBuilder.center(centerX);
        for (ServerPlayer player : level.players()) {
            if (player.blockPosition().distSqr(center) <= (double) radius * radius) {
                return true;
            }
        }
        return false;
    }

    private static boolean chunksLoaded(ServerLevel level, int centerX) {
        for (int dx = -RewardWagonLayout.HALF_LENGTH; dx <= RewardWagonLayout.HALF_LENGTH; dx += 8) {
            if (!level.hasChunkAt(new BlockPos(centerX + dx, RewardWagonBuilder.TRACK_Y, 0))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A wagon must not stand on a station hall, the starter depot or the free power chassis.
     *
     * <p><b>The depot's real extent is asymmetric and wider than it looks.</b> Its cavern runs
     * from {@code CAVERN_MIN_DX = -26} to {@code CAVERN_MAX_DX = 47} relative to a
     * surface/surface boundary {@code B}, plus the two merge curves out to
     * {@code NEGATIVE_MERGE_DX = 44} and {@code POSITIVE_MERGE_DX = 43}. So the occupied span is
     * {@code B - 26 .. B + 47}, and the margin has to cover 47, not 38: a wagon centred at
     * {@code B + 45} puts its west end at {@code B + 41}, well inside the hall. This was found
     * on a live world, where a wagon at X=2093 overlapped the depot whose boundary is X=2048.
     *
     * <p><b>The margin applies only where a depot really is.</b> Only surface/surface
     * boundaries carry one, so asking {@link StationDepotSites#buildsDepot} keeps the full
     * 128-block window for every other lane instead of shrinking it for a hall that is not
     * there.
     */
    private static boolean reservedX(ServerLevel level, int blockX) {
        // The starter depot occupies -256..-16 and the free power chassis sits at X=32.
        if (blockX >= -260 && blockX <= -12) {
            return true;
        }
        if (blockX >= 24 && blockX <= 40) {
            return true;
        }
        int bandSize = bandSize(level);
        if (bandSize <= 0) {
            return false;
        }
        int band = Math.floorDiv(blockX, bandSize);
        for (int boundary : new int[] {band * bandSize, (band + 1) * bandSize}) {
            if (Math.abs(blockX - boundary) <= STATION_DEPOT_MARGIN && carriesDepot(level, boundary, bandSize)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this boundary column carries a station depot, which occupies a wide span. */
    private static boolean carriesDepot(ServerLevel level, int boundaryX, int bandSize) {
        if (!(level.getChunkSource().getGenerator()
                instanceof dimblend.worldgen.RotatingChunkGenerator rotating)) {
            return false;
        }
        var layout = rotating.layoutOrNull();
        if (layout == null) {
            return false;
        }
        return dimblend.api.StationDepotSites.buildsDepot(boundaryX, bandSize,
                region -> layout.laneName(layout.delegate(region)));
    }

    /**
     * How far a wagon must stay from a boundary column that carries a station depot: the depot
     * reaches 47 blocks east of the boundary and 26 west, and a wagon reaches
     * {@code HALF_LENGTH} further, so 48 covers both sides.
     */
    static final int STATION_DEPOT_MARGIN = 48;

    private static int bandSize(ServerLevel level) {
        return level.getChunkSource().getGenerator()
                instanceof dimblend.worldgen.RotatingChunkGenerator rotating ? rotating.bandSize() : 0;
    }

    private static String laneName(ServerLevel level, int band) {
        if (!(level.getChunkSource().getGenerator()
                instanceof dimblend.worldgen.RotatingChunkGenerator rotating)) {
            return null;
        }
        var layout = rotating.layoutOrNull();
        if (layout == null) {
            return null;
        }
        return layout.laneName(layout.delegate(band));
    }
}
