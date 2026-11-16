package dimblend.experience.compat.starlight;

import cn.leolezury.eternalstarlight.common.block.entity.CrystalbornCatalystBlockEntity;
import cn.leolezury.eternalstarlight.common.config.ESConfig;
import cn.leolezury.eternalstarlight.common.registry.ESBlocks;
import cn.leolezury.eternalstarlight.common.util.ESTags;
import dimblend.experience.mixin.compat.starlight.CrystalbornCatalystBlockEntityAccessor;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * Eternal Starlight crystalborn catalyst rules.
 *
 * <p>With an adjacent redstone block touching the catalyst, this mode treats the block
 * <em>below</em> the catalyst as a template and mines matching peripheral blocks within a
 * bounded radius ({@value #MAX_PERIPHERAL_RANGE} blocks), feeding the drops into the catalyst's
 * inventory. The block directly below is the template anchor and is never mined, nor is the
 * catalyst itself. All server block reads go through {@code getChunkNow} so unloaded/generating
 * neighbour chunks are never synchronously loaded.</p>
 *
 * <p>Placement of the catalyst on a Sable sub-level is banned in every game mode, and an existing
 * catalyst that ends up on one (e.g. assembled into a structure) removes itself on tick.</p>
 */
public final class CrystalbornCatalystRules {

    public enum ReplaceMode {
        LEAVE_AIR,
        REPLACE_WITH_CRYSTAL_SAND
    }

    public static volatile ReplaceMode ACTIVE_REPLACE_MODE = ReplaceMode.REPLACE_WITH_CRYSTAL_SAND;

    public static final ResourceLocation CRYSTALBORN_CATALYST_ID =
            ResourceLocation.fromNamespaceAndPath("eternal_starlight", "crystalborn_catalyst");

    /** Bounded peripheral mining radius ("挖掘其周边") even when ES config maxRange is 128. */
    public static final int MAX_PERIPHERAL_RANGE = 16;

    private static final int MAX_CURSORS = 8;
    private static final int IDLE_LIMIT = 24;
    private static final int CHARGE_LIMIT = 30;
    private static final int MIN_CURSOR_ENERGY = 10;
    private static final int MAX_CURSOR_ENERGY = 20;
    private static final int MAX_FALLBACK_RADIUS = 12;

    /** Mutable cursor used only by this mode; intentionally not persisted in the block entity. */
    public static final class RedstoneCursor {

        public BlockPos pos;
        public Direction dir;
        public int energyLeft;
        public int idleSteps;

        RedstoneCursor(BlockPos pos, Direction dir, int energyLeft) {
            this.pos = pos;
            this.dir = dir;
            this.energyLeft = energyLeft;
        }
    }

    public static boolean hasNeighborRedstoneBlock(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockState neighbour = loadedBlockState(level, pos.relative(direction));
            if (neighbour != null && neighbour.is(Blocks.REDSTONE_BLOCK)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pure predicate behind {@link #shouldMineTarget}. Exposed so the decision table can be
     * tested without constructing block states.
     */
    public static boolean isMineCandidate(boolean targetIsBelow, boolean targetIsCatalyst,
            boolean air, boolean hasFluid, float destroySpeed, boolean matchesTemplate) {
        if (targetIsBelow || targetIsCatalyst) {
            return false;
        }
        if (air || hasFluid || destroySpeed < 0.0F) {
            return false;
        }
        return matchesTemplate;
    }

    public static boolean shouldMineTarget(BlockPos catalystPos, BlockPos belowPos, Block templateBlock,
            BlockPos targetPos, @Nullable BlockState targetState, BlockGetter chunk) {
        if (targetState == null) {
            return false;
        }
        return isMineCandidate(
                targetPos.equals(belowPos),
                targetPos.equals(catalystPos),
                targetState.isAir(),
                !targetState.getFluidState().isEmpty(),
                targetState.getDestroySpeed(chunk, targetPos),
                targetState.is(templateBlock));
    }

    public static boolean shouldBlockPlacement(BlockItem item, BlockPlaceContext context) {
        if (!CRYSTALBORN_CATALYST_ID.equals(BuiltInRegistries.BLOCK.getKey(item.getBlock()))) {
            return false;
        }
        return isOnSableStructure(context.getLevel(), context.getClickedPos());
    }

    /**
     * Sable lookup isolated in a nested holder so {@code BlockItem#place} never resolves Sable
     * classes when the mod is absent.
     */
    public static boolean isOnSableStructure(Level level, BlockPos pos) {
        return ModList.get().isLoaded("sable") && SableAccess.contains(level, pos);
    }

    public static void tickRedstoneBlockMode(ServerLevel level, BlockPos pos, BlockState state,
            CrystalbornCatalystBlockEntity entity, CrystalbornCatalystBlockEntityAccessor accessor,
            List<RedstoneCursor> cursors) {
        int configuredRange = Math.max(1, ESConfig.INSTANCE.itemsConfig.crystalbornCatalyst.maxRange());
        int maxRange = Math.min(configuredRange, MAX_PERIPHERAL_RANGE);
        int rangeSquared = maxRange * maxRange;
        BlockPos belowPos = pos.below();
        LevelChunk belowChunk = loadedChunk(level, belowPos);
        BlockState belowState = belowChunk == null ? null : belowChunk.getBlockState(belowPos);

        if (belowState == null || !isValidTemplateState(belowChunk, belowPos, belowState)) {
            accessor.dimblend$setCharge(0);
            refundAll(accessor, cursors);
            return;
        }

        cursors.removeIf(cursor -> cursor.energyLeft <= 0 || cursor.pos.distSqr(pos) > rangeSquared);

        RandomSource random = accessor.dimblend$getRandom();
        int energyLeft = accessor.dimblend$getEnergyLeft();
        if (energyLeft > 0 && cursors.size() < MAX_CURSORS) {
            int energy = Math.min(random.nextInt(MIN_CURSOR_ENERGY, MAX_CURSOR_ENERGY), energyLeft);
            cursors.add(new RedstoneCursor(pos, Direction.getRandom(random), energy));
            accessor.dimblend$setEnergyLeft(energyLeft - energy);
        }

        Block template = belowState.getBlock();
        for (RedstoneCursor cursor : cursors) {
            tickCursor(level, pos, belowPos, template, maxRange, rangeSquared, cursor, entity, accessor, random);
        }

        chargeFuel(level, pos, entity, accessor, random);
    }

    @Nullable
    private static LevelChunk loadedChunk(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
    }

    @Nullable
    private static BlockState loadedBlockState(ServerLevel level, BlockPos pos) {
        LevelChunk chunk = loadedChunk(level, pos);
        return chunk == null ? null : chunk.getBlockState(pos);
    }

    private static boolean isValidTemplateState(BlockGetter chunk, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.getFluidState().isEmpty()
                || state.getDestroySpeed(chunk, pos) < 0.0F) {
            return false;
        }
        if (ACTIVE_REPLACE_MODE == ReplaceMode.REPLACE_WITH_CRYSTAL_SAND
                && (state.is(ESBlocks.CRYSTALLIZED_SAND.get())
                || state.is(ESBlocks.RED_CRYSTAL_MOSS_BLOCK.get()))) {
            return false;
        }
        return !state.is(ESBlocks.CRYSTALBORN_CATALYST.get());
    }

    private static void tickCursor(ServerLevel level, BlockPos catalystPos, BlockPos belowPos, Block template,
            int maxRange, int rangeSquared, RedstoneCursor cursor, CrystalbornCatalystBlockEntity entity,
            CrystalbornCatalystBlockEntityAccessor accessor, RandomSource random) {
        if (cursor.energyLeft <= 0) {
            return;
        }
        BlockPos targetPos = cursor.pos.relative(cursor.dir);
        LevelChunk targetChunk = loadedChunk(level, targetPos);
        BlockState targetState = targetChunk == null ? null : targetChunk.getBlockState(targetPos);

        if (targetState != null && targetPos.distSqr(catalystPos) <= rangeSquared
                && shouldMineTarget(catalystPos, belowPos, template, targetPos, targetState, targetChunk)) {
            mineTarget(level, catalystPos, belowPos, template, rangeSquared, targetPos, targetChunk, targetState,
                    cursor, entity, random);
            return;
        }

        if (targetState != null && isTraversable(catalystPos, belowPos, targetPos, rangeSquared, targetState)) {
            cursor.pos = targetPos;
            cursor.idleSteps++;
            Direction steer = findTargetDirection(level, catalystPos, belowPos, template, rangeSquared, cursor.pos, random);
            if (steer != null) {
                cursor.dir = steer;
            } else if (random.nextInt(3) == 0) {
                cursor.dir = Direction.getRandom(random);
            }
        } else {
            cursor.dir = Direction.getRandom(random);
            cursor.idleSteps++;
        }

        if (cursor.idleSteps >= IDLE_LIMIT) {
            refocus(level, catalystPos, belowPos, template, maxRange, rangeSquared, cursor, entity, accessor, random);
        }
    }

    private static boolean isTraversable(BlockPos catalystPos, BlockPos belowPos,
            BlockPos pos, int rangeSquared, BlockState state) {
        if (pos.distSqr(catalystPos) > rangeSquared) {
            return false;
        }
        return pos.equals(belowPos)
                || state.isAir()
                || !state.blocksMotion()
                || state.is(ESBlocks.CRYSTALLIZED_SAND.get())
                || state.is(ESBlocks.RED_CRYSTAL_MOSS_BLOCK.get())
                || state.is(ESBlocks.BLUE_CRYSTAL_MOSS_BLOCK.get());
    }

    private static boolean mineTarget(ServerLevel level, BlockPos catalystPos, BlockPos belowPos, Block template,
            int rangeSquared, BlockPos targetPos, LevelChunk targetChunk, BlockState targetState,
            RedstoneCursor cursor, CrystalbornCatalystBlockEntity entity, RandomSource random) {
        LootParams.Builder builder = new LootParams.Builder(level)
                .withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(targetPos))
                .withParameter(LootContextParams.TOOL, Items.DIAMOND_PICKAXE.getDefaultInstance())
                .withOptionalParameter(LootContextParams.BLOCK_ENTITY,
                        targetState.hasBlockEntity() ? targetChunk.getBlockEntity(targetPos) : null);
        List<ItemStack> drops = targetState.getDrops(builder);

        if (!level.destroyBlock(targetPos, false)) {
            cursor.dir = Direction.getRandom(random);
            cursor.idleSteps++;
            return false;
        }

        if (ACTIVE_REPLACE_MODE == ReplaceMode.REPLACE_WITH_CRYSTAL_SAND) {
            BlockState replacement = targetState.is(ESTags.Blocks.CRYSTALBORN_CATALYST_MOSS_REPLACEABLES)
                    ? ESBlocks.RED_CRYSTAL_MOSS_BLOCK.get().defaultBlockState()
                    : ESBlocks.CRYSTALLIZED_SAND.get().defaultBlockState();
            level.setBlockAndUpdate(targetPos, replacement);
        }

        for (ItemStack drop : drops) {
            if (drop.isEmpty()) {
                continue;
            }
            ItemStack remaining = insertIntoStorage(entity, drop);
            if (!remaining.isEmpty()) {
                Block.popResource(level, catalystPos, remaining);
            }
        }

        cursor.pos = targetPos;
        cursor.energyLeft--;
        cursor.idleSteps = 0;
        entity.setChanged();

        Direction mined = findTargetDirection(level, catalystPos, belowPos, template, rangeSquared, cursor.pos, random);
        if (mined == null) {
            List<Direction> traversable = new ArrayList<>(6);
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = cursor.pos.relative(direction);
                BlockState neighbourState = loadedBlockState(level, neighbour);
                if (neighbourState != null
                        && isTraversable(catalystPos, belowPos, neighbour, rangeSquared, neighbourState)) {
                    traversable.add(direction);
                }
            }
            mined = traversable.isEmpty()
                    ? Direction.getRandom(random)
                    : traversable.get(random.nextInt(traversable.size()));
        }
        cursor.dir = mined;
        return true;
    }

    @Nullable
    private static Direction findTargetDirection(ServerLevel level, BlockPos catalystPos, BlockPos belowPos,
            Block template, int rangeSquared, BlockPos from, RandomSource random) {
        List<Direction> matches = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = from.relative(direction);
            if (neighbour.distSqr(catalystPos) > rangeSquared) {
                continue;
            }
            LevelChunk chunk = loadedChunk(level, neighbour);
            if (chunk == null) {
                continue;
            }
            if (shouldMineTarget(catalystPos, belowPos, template, neighbour,
                    chunk.getBlockState(neighbour), chunk)) {
                matches.add(direction);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        return matches.get(random.nextInt(matches.size()));
    }

    /**
     * Expanding Manhattan-shell search (shells 1..cap) used after a cursor wanders without finding
     * a target. Stops on the nearest matching shell, never touches unloaded chunks, and refunds
     * remaining cursor energy when no matching block exists.
     */
    private static void refocus(ServerLevel level, BlockPos catalystPos, BlockPos belowPos, Block template,
            int maxRange, int rangeSquared, RedstoneCursor cursor, CrystalbornCatalystBlockEntity entity,
            CrystalbornCatalystBlockEntityAccessor accessor, RandomSource random) {
        int cap = Math.min(maxRange, MAX_FALLBACK_RADIUS);
        for (int dist = 1; dist <= cap; dist++) {
            for (int dx = -dist; dx <= dist; dx++) {
                int remY = dist - Math.abs(dx);
                for (int dy = -remY; dy <= remY; dy++) {
                    int absZ = remY - Math.abs(dy);
                    int zStep = absZ == 0 ? 1 : (absZ * 2);
                    for (int dz = -absZ; dz <= absZ; dz += zStep) {
                        BlockPos candidate = catalystPos.offset(dx, dy, dz);
                        if (candidate.distSqr(catalystPos) > rangeSquared) {
                            continue;
                        }
                        LevelChunk chunk = loadedChunk(level, candidate);
                        if (chunk == null) {
                            continue;
                        }
                        BlockState candidateState = chunk.getBlockState(candidate);
                        if (!shouldMineTarget(catalystPos, belowPos, template, candidate, candidateState, chunk)) {
                            continue;
                        }
                        if (mineTarget(level, catalystPos, belowPos, template, rangeSquared, candidate, chunk,
                                candidateState, cursor, entity, random)) {
                            return;
                        }
                    }
                }
            }
        }

        accessor.dimblend$setEnergyLeft(accessor.dimblend$getEnergyLeft() + cursor.energyLeft);
        cursor.energyLeft = 0;
        cursor.idleSteps = 0;
    }

    private static ItemStack insertIntoStorage(CrystalbornCatalystBlockEntity entity, ItemStack stack) {
        ItemStack remaining = stack;
        for (int slot = 1; slot < entity.getContainerSize() && !remaining.isEmpty(); slot++) {
            ItemStack existing = entity.getItem(slot);
            if (existing.isEmpty()) {
                entity.setItem(slot, remaining);
                return ItemStack.EMPTY;
            }
            if (!ItemStack.isSameItemSameComponents(existing, remaining)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getCount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining.getCount());
            existing.grow(moved);
            remaining.shrink(moved);
            entity.setItem(slot, existing);
        }
        return remaining;
    }

    private static void refundAll(CrystalbornCatalystBlockEntityAccessor accessor,
            List<RedstoneCursor> cursors) {
        int refund = 0;
        for (RedstoneCursor cursor : cursors) {
            refund += cursor.energyLeft;
        }
        if (refund > 0) {
            accessor.dimblend$setEnergyLeft(accessor.dimblend$getEnergyLeft() + refund);
        }
        cursors.clear();
    }

    private static void chargeFuel(ServerLevel level, BlockPos pos,
            CrystalbornCatalystBlockEntity entity,
            CrystalbornCatalystBlockEntityAccessor accessor,
            RandomSource random) {
        int energyPerShard = Math.max(1, ESConfig.INSTANCE.itemsConfig.crystalbornCatalyst.energyPerShard());
        int energyLeft = accessor.dimblend$getEnergyLeft();
        ItemStack fuel = entity.getItem(0);
        boolean burning = energyLeft < energyPerShard && !fuel.isEmpty()
                && CrystalbornCatalystBlockEntity.isFuel(fuel);
        if (!burning) {
            accessor.dimblend$setCharge(0);
            return;
        }
        int charge = Math.min(accessor.dimblend$getCharge() + 1, CHARGE_LIMIT);
        accessor.dimblend$setCharge(charge);
        if (charge >= CHARGE_LIMIT) {
            fuel.shrink(1);
            accessor.dimblend$setEnergyLeft(energyLeft + energyPerShard);
            for (int i = 0; i <= 10; i++) {
                cn.leolezury.eternalstarlight.common.platform.ESPlatform.INSTANCE.sendToAllClients(
                        level,
                        new cn.leolezury.eternalstarlight.common.network.ParticlePacket(
                                cn.leolezury.eternalstarlight.common.particle.ExplosionShockParticleOptions.CRYSTAL,
                                pos.getCenter().x + (random.nextFloat() - 0.5F) * 4.0F,
                                pos.getY(),
                                pos.getCenter().z + (random.nextFloat() - 0.5F) * 4.0F,
                                0.0, 1.0, 0.0));
            }
            accessor.dimblend$setCharge(0);
            entity.setChanged();
        }
    }

    private static final class SableAccess {

        private static boolean contains(Level level, BlockPos pos) {
            return dev.ryanhcode.sable.Sable.HELPER.getContaining(level, (Vec3i) pos) != null;
        }

        private SableAccess() {
        }
    }

    private CrystalbornCatalystRules() {
    }
}
