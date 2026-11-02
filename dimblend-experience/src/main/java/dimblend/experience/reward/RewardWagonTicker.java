package dimblend.experience.reward;

import com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity;
import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.DimBlend;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;

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
        if (site.attempts >= MAX_ATTEMPTS) {
            return false;
        }
        if (!site.built) {
            if (!nearPlayer(level, site.centerX, TRIGGER_RADIUS) || !chunksLoaded(level, site.centerX)) {
                return false;
            }
            return build(level, site);
        }
        ServerSubLevel car = existing(level, site.centerX);
        if (car == null) {
            // The sub-level is gone: it dissolved already, or a save restored the region
            // without it. Rebuilding a wagon the player already emptied would be a gift they
            // did not ask for, so a built-but-gone site is retired.
            site.dissolved = true;
            return true;
        }
        if (site.dissolveDeadline > 0) {
            if (level.getGameTime() < site.dissolveDeadline) {
                return false;
            }
            remove(level, site, car);
            site.dissolved = true;
            site.dissolveDeadline = 0;
            return true;
        }
        if (shouldDissolve(level, car)) {
            beginDissolve(level, site, car);
            return true;
        }
        return false;
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
     * <p><b>Every position here comes from the sub-level's pose, not from the planned
     * centre.</b> Once assembled, the wagon's blocks live in the sub-level's plot, and the
     * wagon may have been pushed anywhere since. A planned-centre offset would point at empty
     * rails. {@link #worldPos} is the transform that makes this correct.
     */
    private static void beginDissolve(ServerLevel level, RewardWagonSites.Site site, ServerSubLevel car) {
        for (RewardWagonLayout.Cell cell : RewardWagonLayout.couplers()) {
            BlockPos pos = worldPos(car, cell);
            if (level.getBlockEntity(pos) instanceof AutomaticCouplerBlockEntity coupler) {
                coupler.removePartner();
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        if (!site.effectsPlayed) {
            BlockPos bogey = worldPos(car, RewardWagonLayout.bogey());
            RewardWagonBuilder.playDissolveEffects(level, bogey.below());
            site.effectsPlayed = true;
        }
        site.dissolveDeadline = level.getGameTime() + RewardWagonDissolve.DISSOLVE_DELAY_TICKS;
        DimBlend.LOGGER.info("Reward wagon at X={} emptied; dissolving", site.centerX);
    }

    /**
     * The world position of one deck cell, through the sub-level's pose.
     *
     * <p>{@code transformPosition} maps a <b>local</b> coordinate to the world, which is the
     * direction wanted: the cell offsets are local to the wagon, and the wagon may have been
     * pushed anywhere since it was assembled. Reading them against the planned centre would
     * work only while the wagon has never moved, which is exactly the case a player pushing
     * it breaks.
     */
    private static BlockPos worldPos(ServerSubLevel car, RewardWagonLayout.Cell cell) {
        net.minecraft.world.phys.Vec3 local = new net.minecraft.world.phys.Vec3(cell.dx(), 0, cell.dz());
        net.minecraft.world.phys.Vec3 world = car.logicalPose().transformPosition(local);
        return BlockPos.containing(world);
    }

    private static void remove(ServerLevel level, RewardWagonSites.Site site, ServerSubLevel car) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        container.removeSubLevel(car, dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.REMOVED);
    }

    /** Whether the wagon has nothing left, or has met a warp gate. */
    private static boolean shouldDissolve(ServerLevel level, ServerSubLevel car) {
        if (touchesGate(level, car)) {
            return true;
        }
        return RewardWagonDissolve.isEmpty(occupants(level, car));
    }

    /**
     * Reads what is inside the wagon's box.
     *
     * <p>Only blocks the sub-level actually contains are counted, tested by object identity
     * through {@code Sable.HELPER.getContaining} rather than by the box alone: the box of a
     * wagon is a whole chunk section and covering it would count the corridor walls.
     */
    private static List<RewardWagonDissolve.Occupant> occupants(ServerLevel level, ServerSubLevel car) {
        List<RewardWagonDissolve.Occupant> out = new ArrayList<>();
        var box = car.boundingBox();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = (int) Math.floor(box.minX()); x <= (int) Math.floor(box.maxX()); x++) {
            for (int y = (int) Math.floor(box.minY()); y <= (int) Math.floor(box.maxY()); y++) {
                for (int z = (int) Math.floor(box.minZ()); z <= (int) Math.floor(box.maxZ()); z++) {
                    cursor.set(x, y, z);
                    if (Sable.HELPER.getContaining(level, cursor) != car) {
                        continue;
                    }
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    out.add(classify(level, cursor, state));
                }
            }
        }
        return out;
    }

    private static RewardWagonDissolve.Occupant classify(ServerLevel level, BlockPos pos, BlockState state) {
        if (isStructure(state)) {
            return RewardWagonDissolve.Occupant.STRUCTURE;
        }
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity container) {
            return container.isEmpty()
                    ? RewardWagonDissolve.Occupant.EMPTY_CONTAINER
                    : RewardWagonDissolve.Occupant.FILLED_CONTAINER;
        }
        return RewardWagonDissolve.Occupant.CONTENT;
    }

    /** The three brick kinds plus the coupler. */
    static boolean isStructure(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id.toString().equals(RewardWagonBlocks.COUPLER)) {
            return true;
        }
        return isDeckBrick(state);
    }

    /** The slab and the bogey, which are the bricks the interaction guard protects. */
    static boolean isDeckBrick(BlockState state) {
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return name.equals(RewardWagonBlocks.BOGEY) || name.equals(RewardWagonBlocks.COPYCAT_SLAB);
    }

    /** True when the wagon's world box overlaps any warp gate block in the corridor. */
    private static boolean touchesGate(ServerLevel level, ServerSubLevel car) {
        var box = car.boundingBox();
        int minX = (int) Math.floor(box.minX());
        int maxX = (int) Math.floor(box.maxX());
        int minY = (int) Math.floor(box.minY());
        int maxY = (int) Math.floor(box.maxY());
        int minZ = (int) Math.floor(box.minZ());
        int maxZ = (int) Math.floor(box.maxZ());
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (level.getBlockState(cursor).is(BuiltInRegistries.BLOCK.get(
                            ResourceLocation.parse(RewardWagonBlocks.WARP_GATE)))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** The sub-level whose centre matches this wagon, if it still exists. */
    private static ServerSubLevel existing(ServerLevel level, int centerX) {
        BlockPos center = RewardWagonBuilder.center(centerX);
        SubLevel found = Sable.HELPER.getContaining(level, center);
        return found instanceof ServerSubLevel server ? server : null;
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
