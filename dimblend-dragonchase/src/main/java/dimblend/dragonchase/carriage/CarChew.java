package dimblend.dragonchase.carriage;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.dragonchase.DimBlendDragonChase;
import dimblend.dragonchase.structure.CarKind;
import dimblend.dragonchase.structure.CarMotion;
import dimblend.dragonchase.structure.CarPlans;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The chewing rule of requirements.md §3.5 and the plan's assumption Q2: a car moving faster than
 * 2 m/s that is not coupled to anything on a force-loaded sub-level eats the blocks of whatever
 * pushes it, one at a time, nearest to the contact face first — but only blocks a survival player
 * could break.
 *
 * <p><b>Coordinates.</b> The contact is a world-space fact (two boxes overlapping), so the contact
 * box is measured in the world and then transformed into the pusher's <i>plot</i> with
 * {@code BoundingBox3dc#transformInverse}, which transforms all eight corners and expands — a
 * rotated sub-level therefore widens the search box instead of losing blocks. Every candidate's
 * world position is computed back out of the pose, so a corner artefact of the expansion is rejected
 * by testing it against the contact box again.
 *
 * <p><b>Q2: no breakable block, no chewing.</b> When the contact zone holds nothing a survival
 * player could break, this does nothing at all and the car is simply pushed — the requirement's
 * fallback after the plan's question 2.
 *
 * <p><b>Drops land in the world.</b> A chewed block's loot is resolved through the level's own loot
 * tables (so a tool-requiring block drops nothing bare-handed, like a player's fist) and is popped at
 * the block's <i>world</i> position, because the plot is millions of blocks away.
 */
public final class CarChew {

    /** Widest search box, per axis, so a skewed contact can never walk a whole plot. */
    private static final int MAX_SCAN_SPAN = 16;
    /**
     * Rapier's contact solver separates flush rigid bodies by its contact skin (~0.00-0.05 m)
     * before game-bus ticks inspect {@code SubLevel#boundingBox()}, where Mojang's strict
     * {@code AABB#intersects} ({@code minX < maxX}) would miss a flush push. Keep strictly smaller
     * than Simurail's {@code sqrt(0.02) = 0.1414 m} auto-coupler capture radius so a 1-block cover
     * whose coupler tip is about to engage is never chewed across an air gap before coupling.
     */
    private static final double CONTACT_SKIN = 0.10D;

    private CarChew() {
    }

    /**
     * True when one of the car's couplers has a partner that lives on a force-loaded sub-level.
     *
     * <p>{@code AutomaticCouplerBlockEntity#sable$getConnectionDependencies} is Simurail's public
     * answer to "who is on the other end": it resolves the stored partner sub-level id (a partner
     * whose chunk is not loaded resolves to null and simply does not count). Force-loaded means the
     * partner is in {@code ServerSubLevelContainer#collectForceLoadedSubLevels}, which already
     * includes sub-levels pulled in through the dependency chain — a car coupled to a train is
     * force-loaded by that same chain, so this answers "attached to the player's train".
     */
    public static boolean coupledToForceLoaded(ServerLevel level, ServerSubLevel car, CarKind kind,
            Set<UUID> forceLoaded) {
        if (forceLoaded.isEmpty()) {
            return false;
        }
        for (CarPlans.Cell cell : CarPlans.of(kind).couplers()) {
            BlockPos pos = CarMotion.plotPos(car, cell);
            if (!(level.getBlockEntity(pos) instanceof AutomaticCouplerBlockEntity coupler)) {
                continue;
            }
            for (SubLevel partner : coupler.sable$getConnectionDependencies()) {
                if (partner != null && forceLoaded.contains(partner.getUniqueId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The force-loaded sub-level currently pushing this car, or {@code null}.
     *
     * <p>The largest overlap wins, so a car wedged between the train and the tunnel wall chews the
     * train rather than a wall it merely grazes.
     */
    @Nullable
    public static ServerSubLevel pusher(ServerSubLevelContainer container, ServerSubLevel car) {
        AABB carBox = car.boundingBox().toMojang().inflate(CONTACT_SKIN);
        ServerSubLevel best = null;
        double bestVolume = 0.0;
        for (ServerSubLevel candidate : container.collectForceLoadedSubLevels()) {
            if (candidate == car || candidate.isRemoved()) {
                continue;
            }
            AABB box = candidate.boundingBox().toMojang();
            if (!box.intersects(carBox)) {
                continue;
            }
            double volume = overlapVolume(box, carBox);
            if (volume > bestVolume) {
                bestVolume = volume;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Breaks at most one block off {@code pusher}, the one nearest the contact face.
     *
     * @return true when a block was removed (the caller marks the registry dirty)
     */
    public static boolean chew(ServerLevel level, ServerSubLevel car, ServerSubLevel pusher) {
        AABB carBox = car.boundingBox().toMojang().inflate(CONTACT_SKIN);
        AABB pusherBox = pusher.boundingBox().toMojang();
        double minX = Math.max(carBox.minX, pusherBox.minX);
        double minY = Math.max(carBox.minY, pusherBox.minY);
        double minZ = Math.max(carBox.minZ, pusherBox.minZ);
        double maxX = Math.min(carBox.maxX, pusherBox.maxX);
        double maxY = Math.min(carBox.maxY, pusherBox.maxY);
        double maxZ = Math.min(carBox.maxZ, pusherBox.maxZ);
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            return false;
        }
        Vec3 contact = new Vec3((minX + maxX) / 2.0, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0);
        AABB contactBox = new AABB(minX, minY, minZ, maxX, maxY, maxZ).inflate(1.0);

        // One block of slack around the contact, in plot space.
        BoundingBox3d scan = new BoundingBox3d(minX - 1.0, minY - 1.0, minZ - 1.0, maxX + 1.0, maxY + 1.0, maxZ + 1.0)
                .transformInverse(pusher.logicalPose(), new BoundingBox3d());
        int ix0 = (int) Math.floor(scan.minX());
        int iy0 = (int) Math.floor(scan.minY());
        int iz0 = (int) Math.floor(scan.minZ());
        int ix1 = (int) Math.floor(scan.maxX());
        int iy1 = (int) Math.floor(scan.maxY());
        int iz1 = (int) Math.floor(scan.maxZ());
        if (ix1 - ix0 > MAX_SCAN_SPAN || iy1 - iy0 > MAX_SCAN_SPAN || iz1 - iz0 > MAX_SCAN_SPAN) {
            // A degenerately skewed pose: keep the scan centred on the contact instead of walking it.
            Vec3 local = pusher.logicalPose().transformPositionInverse(contact);
            BlockPos pivot = BlockPos.containing(local.x, local.y, local.z);
            int half = MAX_SCAN_SPAN / 2;
            ix0 = pivot.getX() - half;
            iy0 = pivot.getY() - half;
            iz0 = pivot.getZ() - half;
            ix1 = pivot.getX() + half;
            iy1 = pivot.getY() + half;
            iz1 = pivot.getZ() + half;
        }

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos chosen = null;
        BlockState chosenState = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = ix0; x <= ix1; x++) {
            for (int y = iy0; y <= iy1; y++) {
                for (int z = iz0; z <= iz1; z++) {
                    cursor.set(x, y, z);
                    BlockState state = level.getBlockState(cursor);
                    if (state.isAir()) {
                        continue;
                    }
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if (!CarRules.chewable(state.getDestroySpeed(level, cursor), id)) {
                        continue;
                    }
                    Vec3 world = CarMotion.worldOf(pusher, cursor);
                    if (!contactBox.contains(world)) {
                        continue;
                    }
                    double distance = world.distanceToSqr(contact);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        chosen = cursor.immutable();
                        chosenState = state;
                    }
                }
            }
        }
        if (chosen == null) {
            return false; // Q2: nothing breakable in the contact zone; the car is just pushed.
        }
        breakBlock(level, pusher, chosen, chosenState);
        return true;
    }

    /** Resolves the block's drops, clears it, and pops the drops at its world position. */
    private static void breakBlock(ServerLevel level, ServerSubLevel pusher, BlockPos plotPos, BlockState state) {
        BlockEntity blockEntity = level.getBlockEntity(plotPos);
        Vec3 world = CarMotion.worldOf(pusher, plotPos);
        List<ItemStack> drops = state.getDrops(new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(plotPos))
                .withParameter(LootContextParams.TOOL, ItemStack.EMPTY)
                .withParameter(LootContextParams.BLOCK_STATE, state)
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY, blockEntity));
        // A plot write is a plain level write on the plot chunk, which is what Sable's own
        // LevelChunkMixin expects (it updates the plot's bounds and mass from it).
        level.setBlock(plotPos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        BlockPos at = BlockPos.containing(world);
        for (ItemStack stack : drops) {
            if (!stack.isEmpty()) {
                Block.popResource(level, at, stack);
            }
        }
        DimBlendDragonChase.LOGGER.debug("Dragon Chase chewing: {} broke {} at world {}",
                pusher.getUniqueId(), BuiltInRegistries.BLOCK.getKey(state.getBlock()), at);
    }

    private static double overlapVolume(AABB a, AABB b) {
        double x = Math.min(a.maxX, b.maxX) - Math.max(a.minX, b.minX);
        double y = Math.min(a.maxY, b.maxY) - Math.max(a.minY, b.minY);
        double z = Math.min(a.maxZ, b.maxZ) - Math.max(a.minZ, b.minZ);
        if (x <= 0.0 || y <= 0.0 || z <= 0.0) {
            return 0.0;
        }
        return x * y * z;
    }
}
