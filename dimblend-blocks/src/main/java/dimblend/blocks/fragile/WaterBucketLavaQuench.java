package dimblend.blocks.fragile;

import java.util.List;
import javax.annotation.Nullable;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.fragile.WaterBucketLavaQuenchMath.FlowTraceResult;
import dimblend.blocks.fragile.WaterBucketLavaQuenchMath.LavaCellSample;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 水桶 / 蓝冰右键冷却岩浆（全维度）：
 * <ul>
 *   <li>命中岩浆源头：固化 {@code 3x3x3} 范围内的所有岩浆源头为黑曜石；</li>
 *   <li>命中岩浆流：回溯上游源头——16 格内将源头变为黑曜石、回溯路径上的岩浆流变为易碎石头；
 *       16 格以外不固化源头，从右键位置沿途至源头依次播放烟雾粒子与火焰熄灭音效，
 *       并把最先 {@value WaterBucketLavaQuenchMath#OVER_16_CUT_LIMIT} 格纯岩浆流删除（紧急截断列车前方的岩浆）；</li>
 *   <li>生存/冒险下消耗水桶中的水（变回空桶）或消耗 1 个蓝冰；永不放水或放置蓝冰方块。</li>
 * </ul>
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID)
public final class WaterBucketLavaQuench {

    /** 原版 {@code WorldEvents.LAVA_EXTINGUISH}：岩浆冷却滋滋声 + 烟雾。 */
    private static final int LAVA_EXTINGUISH_EVENT = 1501;

    private enum QuenchOutcome {
        NOT_LAVA,
        SOLIDIFIED,
        SMOKE_OVER_16
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        InteractionResult result = quenchOnRaytracedLava(event);
        if (result != null) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        InteractionResult result = quenchOnRaytracedLava(event);
        if (result != null) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    /** 满水桶判定：原版水桶、{@code #c:buckets/water} 标签、或桶内容流体是水（排除生物桶）。 */
    public static boolean isWaterBucket(ItemStack stack) {
        if (!(stack.getItem() instanceof BucketItem bucket) || bucket instanceof MobBucketItem) {
            return false;
        }
        return bucket == Items.WATER_BUCKET
                || stack.is(Tags.Items.BUCKETS_WATER)
                || bucket.content.is(FluidTags.WATER);
    }

    /** 蓝冰判定。 */
    public static boolean isBlueIce(ItemStack stack) {
        return stack.is(Items.BLUE_ICE);
    }

    /** 是否为可右键冷却岩浆的物品（满水桶或蓝冰）。 */
    public static boolean isLavaCoolant(ItemStack stack) {
        return isWaterBucket(stack) || isBlueIce(stack);
    }

    /**
     * 「纯岩浆格」：区块已就位、流体是岩浆且方块本体是液体方块。
     */
    public static boolean isPureLavaCell(Level level, BlockPos pos) {
        if (!chunkReady(level, pos)) {
            return false;
        }
        if (!level.getFluidState(pos).is(FluidTags.LAVA)) {
            return false;
        }
        return level.getBlockState(pos).liquid();
    }

    /** 玩家视线命中的岩浆格（与原版 {@code Item#getPlayerPOVHitResult} 完全同口径）；命中纯岩浆液体格时返回，否则 {@code null}。 */
    @Nullable
    public static BlockHitResult findRaytracedLavaHit(Level level, Player player) {
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.calculateViewVector(player.getXRot(), player.getYRot())
                .scale(player.blockInteractionRange()));
        BlockHitResult hit = level.clip(
                new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK || !isPureLavaCell(level, hit.getBlockPos())) {
            return null;
        }
        return hit;
    }

    /**
     * Mixin 层兜底：{@code BucketItem#emptyContents} 入口（例如点击干燥石块底面递归到下方岩浆格）。
     */
    public static boolean tryInterceptEmptyContents(BucketItem bucket, @Nullable Player player, Level level,
            BlockPos pos) {
        if (player == null || player.isSpectator() || bucket instanceof MobBucketItem
                || !bucket.content.is(FluidTags.WATER)) {
            return false;
        }
        if (!isPureLavaCell(level, pos)) {
            return false;
        }
        if (level instanceof ServerLevel serverLevel) {
            QuenchOutcome outcome = executeLavaQuench(serverLevel, pos);
            if (outcome == QuenchOutcome.SOLIDIFIED) {
                playBucketQuenchEffects(serverLevel, player, pos);
            } else if (outcome == QuenchOutcome.SMOKE_OVER_16) {
                serverLevel.playSound(player, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                serverLevel.gameEvent(player, GameEvent.FLUID_PLACE, pos);
            }
        }
        return true;
    }

    @Nullable
    private static InteractionResult quenchOnRaytracedLava(PlayerInteractEvent event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (player.isSpectator() || !isLavaCoolant(stack)) {
            return null;
        }
        if (player.getCooldowns().isOnCooldown(stack.getItem())) {
            return null;
        }
        Level level = event.getLevel();
        BlockHitResult lavaHit = findRaytracedLavaHit(level, player);
        if (lavaHit == null) {
            return null;
        }
        BlockPos lavaPos = lavaHit.getBlockPos();
        if (!level.mayInteract(player, lavaPos)
                || !player.mayUseItemAt(lavaPos, lavaHit.getDirection(), stack)) {
            syncInventory(player);
            return InteractionResult.FAIL;
        }
        if (level instanceof ServerLevel serverLevel) {
            boolean usedWaterBucket = isWaterBucket(stack);
            Item usedItem = stack.getItem();
            QuenchOutcome outcome = executeLavaQuench(serverLevel, lavaPos);
            if (outcome == QuenchOutcome.NOT_LAVA) {
                return null;
            }
            if (outcome == QuenchOutcome.SOLIDIFIED) {
                if (usedWaterBucket) {
                    playBucketQuenchEffects(serverLevel, player, lavaPos);
                } else {
                    playBlueIceQuenchEffects(serverLevel, player, lavaPos);
                }
            } else if (outcome == QuenchOutcome.SMOKE_OVER_16 && usedWaterBucket) {
                serverLevel.playSound(player, lavaPos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                serverLevel.gameEvent(player, GameEvent.FLUID_PLACE, lavaPos);
            }

            player.awardStat(Stats.ITEM_USED.get(usedItem));
            boolean sourceOver16 = outcome == QuenchOutcome.SMOKE_OVER_16;
            if (WaterBucketLavaQuenchMath.shouldConsumeCoolant(player.hasInfiniteMaterials(), sourceOver16)) {
                consumeCoolant(player, event.getHand(), stack, usedWaterBucket);
            }
            syncInventory(player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    private static void consumeCoolant(Player player, InteractionHand hand, ItemStack stack, boolean usedWaterBucket) {
        if (usedWaterBucket) {
            ItemStack emptied = ItemUtils.createFilledResult(stack, player, emptyBucket(stack));
            player.setItemInHand(hand, emptied);
        } else {
            stack.shrink(1);
            if (stack.isEmpty()) {
                player.setItemInHand(hand, ItemStack.EMPTY);
            }
        }
    }

    /**
     * 按源头 / 岩浆流规则执行冷却。
     */
    private static QuenchOutcome executeLavaQuench(ServerLevel level, BlockPos clickedPos) {
        if (!isPureLavaCell(level, clickedPos)) {
            return QuenchOutcome.NOT_LAVA;
        }
        FluidState clickedFluid = level.getFluidState(clickedPos);
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        if (clickedFluid.isSource()) {
            for (BlockPos cell : WaterBucketLavaQuenchMath.cube3x3x3(clickedPos)) {
                if (!chunkReady(level, cell)) {
                    continue;
                }
                FluidState fs = level.getFluidState(cell);
                BlockState bs = level.getBlockState(cell);
                if (WaterBucketLavaQuenchMath.shouldSolidifyInSourceCube(
                        fs.is(FluidTags.LAVA), fs.isSource(), bs.liquid())) {
                    level.setBlockAndUpdate(cell, obsidian);
                }
            }
            return QuenchOutcome.SOLIDIFIED;
        }

        FlowTraceResult trace = WaterBucketLavaQuenchMath.traceUpstream(clickedPos, pos -> sampleCell(level, pos));
        List<BlockPos> path = trace.pathFromClickedToUpstream();
        return switch (trace.kind()) {
            case SOURCE_WITHIN_16 -> {
                BlockState fragileStone = FragileStones.FRAGILE_STONE.get().defaultBlockState();
                BlockPos sourcePos = path.get(path.size() - 1);
                if (chunkReady(level, sourcePos) && isPureLavaCell(level, sourcePos)) {
                    level.setBlockAndUpdate(sourcePos, obsidian);
                }
                for (int i = 0; i < path.size() - 1; i++) {
                    BlockPos flowPos = path.get(i);
                    if (chunkReady(level, flowPos) && isPureLavaCell(level, flowPos)) {
                        level.setBlockAndUpdate(flowPos, fragileStone);
                    }
                }
                if (!sourcePos.equals(clickedPos) && chunkReady(level, sourcePos)) {
                    level.levelEvent(LAVA_EXTINGUISH_EVENT, sourcePos, 0);
                }
                yield QuenchOutcome.SOLIDIFIED;
            }
            case ORPHAN_FLOW -> {
                BlockState fragileStone = FragileStones.FRAGILE_STONE.get().defaultBlockState();
                for (BlockPos flowPos : path) {
                    if (chunkReady(level, flowPos) && isPureLavaCell(level, flowPos)) {
                        level.setBlockAndUpdate(flowPos, fragileStone);
                    }
                }
                yield QuenchOutcome.SOLIDIFIED;
            }
            case SOURCE_OVER_16 -> {
                LavaQuenchSmokeTrail.schedule(level, path, WaterBucketLavaQuenchMath.OVER_16_CUT_LIMIT);
                yield QuenchOutcome.SMOKE_OVER_16;
            }
        };
    }

    private static LavaCellSample sampleCell(ServerLevel level, BlockPos pos) {
        if (!chunkReady(level, pos)) {
            return LavaCellSample.NON_LAVA;
        }
        FluidState fluidState = level.getFluidState(pos);
        if (!fluidState.is(FluidTags.LAVA) || !level.getBlockState(pos).liquid()) {
            return LavaCellSample.NON_LAVA;
        }
        if (fluidState.isSource()) {
            return LavaCellSample.source();
        }
        boolean falling = fluidState.hasProperty(FlowingFluid.FALLING) && fluidState.getValue(FlowingFluid.FALLING);
        if (falling) {
            return LavaCellSample.falling();
        }
        return LavaCellSample.flowing(fluidState.getAmount());
    }

    private static void playBucketQuenchEffects(ServerLevel level, Player player, BlockPos pos) {
        level.playSound(player, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.levelEvent(LAVA_EXTINGUISH_EVENT, pos, 0);
        level.gameEvent(player, GameEvent.FLUID_PLACE, pos);
    }

    private static void playBlueIceQuenchEffects(ServerLevel level, Player player, BlockPos pos) {
        level.playSound(player, pos, SoundEvents.GLASS_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
        level.levelEvent(LAVA_EXTINGUISH_EVENT, pos, 0);
        level.gameEvent(player, GameEvent.BLOCK_CHANGE, pos);
    }

    private static ItemStack emptyBucket(ItemStack stack) {
        Item craftingRemainder = stack.getItem().getCraftingRemainingItem();
        return craftingRemainder == null ? new ItemStack(Items.BUCKET) : new ItemStack(craftingRemainder);
    }

    private static void syncInventory(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.sendAllDataToRemote();
        }
    }

    private static boolean chunkReady(Level level, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        if (level instanceof ServerLevel serverLevel) {
            return serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
        }
        return level.hasChunk(chunkX, chunkZ);
    }

    private WaterBucketLavaQuench() {
    }
}
