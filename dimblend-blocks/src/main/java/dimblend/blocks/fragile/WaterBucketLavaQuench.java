package dimblend.blocks.fragile;

import javax.annotation.Nullable;

import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.fragile.WaterBucketLavaQuenchMath.LavaQuenchOutcome;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
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
 * 水桶浇岩浆（全维度）：手持满水桶右键岩浆，把命中格与同 Y 层水平 5x5 范围的岩浆
 * 固化（源→黑曜石，流动→易碎石头），并且<strong>永不放水</strong>。
 *
 * <p>两层拦截，因为原版放水分两跳：</p>
 * <ol>
 *   <li><b>事件层</b>（本类 {@link SubscribeEvent}）：{@code RightClickBlock} 与
 *     {@code RightClickItem} 在 HIGH 优先级先于物品逻辑触发，按玩家视线射线判定岩浆命中，
 *     取消事件并直接完成固化/音效/统计/扣桶——原版 {@code BucketItem#use} 根本不会跑，
 *     所以「直接点岩浆」「岩浆背后有可替换方块」「点相邻干燥面且目标格是岩浆」三种放水路径
 *     一并封死。</li>
 *   <li><b>Mixin 层</b>（{@code BucketItemLavaQuenchMixin}）：{@code BucketItem#emptyContents}
 *     的 HEAD 兜底，覆盖不经过玩家交互事件的调用方（其它 mod 直接调 {@code emptyContents}、
 *     原版内部对相邻格的一次递归）。见 {@link #tryInterceptEmptyContents}。</li>
 * </ol>
 *
 * <p>判定岩浆必须先在服务端确认区块已就位（{@code getChunkNow}）：未加载的区块上
 * {@code getFluidState}/{@code getBlockState} 会同步加载区块，等于在交互路径里做磁盘 IO。</p>
 */
@EventBusSubscriber(modid = DimBlendBlocks.MODID)
public final class WaterBucketLavaQuench {

    /** 原版 {@code WorldEvents.LAVA_EXTINGUISH}：岩浆被浇灭的滋滋声 + 烟。 */
    private static final int LAVA_EXTINGUISH_EVENT = 1501;

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

    /** 满水桶判定：原版水桶、{@code #c:buckets/water} 标签（如 aether 天空木水桶）、或内容流体是水。 */
    public static boolean isWaterBucket(ItemStack stack) {
        if (!(stack.getItem() instanceof BucketItem bucket) || bucket instanceof MobBucketItem) {
            return false;
        }
        // NeoForge 的 AT 把 BucketItem#content 开放为 public，可直接读，不必反射。
        return bucket == Items.WATER_BUCKET
                || stack.is(Tags.Items.BUCKETS_WATER)
                || bucket.content.is(FluidTags.WATER);
    }

    /**
     * 「纯岩浆格」：流体是岩浆且方块本体是液体方块。
     * 服务端必须先确认区块就位，客户端走 {@code hasChunk}。
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

    /** 玩家视线命中的岩浆格；命中岩浆流体且方块是液体方块时返回，否则 {@code null}。 */
    @Nullable
    public static BlockHitResult findRaytracedLavaHit(Level level, Player player) {
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.getViewVector(1.0F).scale(player.blockInteractionRange()));
        BlockHitResult hit = level.clip(
                new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK || !isPureLavaCell(level, hit.getBlockPos())) {
            return null;
        }
        return hit;
    }

    /**
     * Mixin 层兜底：{@code BucketItem#emptyContents} 入口。目标格是岩浆时当场固化并吞掉这次放水。
     *
     * <p>注意原版 {@code emptyContents} 在自己判定「不可替换」时会递归一次到
     * {@code hit.getBlockPos().relative(hit.getDirection())}——即「点干燥面、目标格在岩浆里」
     * 的真实路径，那次递归调用同样进到本方法，被这里接住。</p>
     *
     * @return 是否已经接管（true 表示不放水，固化已完成）
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
            quenchLavaArea(serverLevel, pos);
            playQuenchEffects(serverLevel, player, pos);
        }
        return true;
    }

    /**
     * 射线命中岩浆后的处理。返回 {@code null} 表示不接管（交给原版），否则是取消事件时回给原版的结果。
     */
    @Nullable
    private static InteractionResult quenchOnRaytracedLava(PlayerInteractEvent event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (player.isSpectator() || !isWaterBucket(stack)) {
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
        // 权限口径与 BucketItem#use 一致：无权交互/无权用物品时不浇，回 FAIL 并强制同步背包。
        if (!level.mayInteract(player, lavaPos)
                || !player.mayUseItemAt(lavaPos, lavaHit.getDirection(), stack)) {
            syncInventory(player);
            return InteractionResult.FAIL;
        }
        if (level instanceof ServerLevel serverLevel) {
            quenchLavaArea(serverLevel, lavaPos);
            playQuenchEffects(serverLevel, player, lavaPos);
            player.awardStat(Stats.ITEM_USED.get(stack.getItem()));
            if (WaterBucketLavaQuenchMath.shouldConsumeBucket(player.hasInfiniteMaterials())) {
                ItemStack emptied = ItemUtils.createFilledResult(stack, player, emptyBucket(stack));
                player.setItemInHand(event.getHand(), emptied);
            }
            // 客户端本地已先跑过一帧并把水桶当成「已用掉」时，服务端必须以整包回推纠正显示。
            syncInventory(player);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** 命中格与同 Y 层水平 5x5：源岩浆→黑曜石，流动岩浆→易碎石头，其余不动。 */
    private static void quenchLavaArea(ServerLevel level, BlockPos center) {
        BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
        BlockState fragileStone = FragileStones.FRAGILE_STONE.get().defaultBlockState();
        for (BlockPos pos : WaterBucketLavaQuenchMath.horizontalSquare5x5(center)) {
            if (!chunkReady(level, pos)) {
                continue;
            }
            FluidState fluidState = level.getFluidState(pos);
            BlockState blockState = level.getBlockState(pos);
            LavaQuenchOutcome outcome = WaterBucketLavaQuenchMath.classifyCell(
                    fluidState.is(FluidTags.LAVA), fluidState.isSource(), blockState.liquid());
            switch (outcome) {
                case OBSIDIAN -> level.setBlockAndUpdate(pos, obsidian);
                case FRAGILE_STONE -> level.setBlockAndUpdate(pos, fragileStone);
                case NONE -> {
                }
            }
        }
    }

    /** 空桶音效 + 岩浆滋滋/冒烟 + 流体放置游戏事件（与原版 {@code playEmptySound} 同口径）。 */
    private static void playQuenchEffects(ServerLevel level, Player player, BlockPos pos) {
        level.playSound(player, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.levelEvent(LAVA_EXTINGUISH_EVENT, pos, 0);
        level.gameEvent(player, GameEvent.FLUID_PLACE, pos);
    }

    /** 生存/冒险回空桶（优先物品自定义的合成剩余物，如 mod 桶回自己的空桶）。 */
    private static ItemStack emptyBucket(ItemStack stack) {
        Item craftingRemainder = stack.getItem().getCraftingRemainingItem();
        return craftingRemainder == null ? new ItemStack(Items.BUCKET) : new ItemStack(craftingRemainder);
    }

    private static void syncInventory(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.sendAllDataToRemote();
        }
    }

    /**
     * 区块就位检查：服务端只用 {@code getChunkNow}（绝不触发同步加载），客户端用 {@code hasChunk}。
     */
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
