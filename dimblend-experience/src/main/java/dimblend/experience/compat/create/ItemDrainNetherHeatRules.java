package dimblend.experience.compat.create;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.NetherWartBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.UseItemOnBlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

/**
 * 「下界太热」②③：下界内、分液池覆盖范围内的作物不长（自然生长 + 池子的骨粉），
 * 也种不下去（右键播种被取消并提示）。① 耕地不干由 N1 保湿票据保证，不在此实现；
 * ④ 管道注水见 {@link ItemDrainPipeRefill}。判定口径见 {@link ItemDrainNetherHeat}。
 *
 * <p><b>生效条件</b>：规则挂在分液池身上，所以 N1 保湿或 N2 催熟<b>任一开着</b>才生效
 * （两个都关时池子对周围没有任何作用，也就没有「太热」可言）。不新增配置键。</p>
 *
 * <p><b>为什么找池子而不是反查</b>：事件只带来目标格坐标，规则的主体是分液池。
 * 半径最大 8 = 半个区块，池子与目标格最多隔一个区块，故以目标格所在区块为中心枚举
 * 3x3 个<b>已加载</b>区块的分液池（{@code LevelChunk#getBlockEntities()} 是内存态，
 * 不触发区块加载；{@code getChunkSource().getChunkNow} 未加载即返回 null）。</p>
 *
 * <p><b>② 自然生长</b>：注入点 {@code CropGrowEvent.Pre}——原版
 * {@code CropBlock}/{@code StemBlock}/{@code NetherWartBlock} 的 {@code randomTick}
 * 都经 {@code CommonHooks.canCropGrow} 发这个事件，置 {@code DO_NOT_GROW} 即把生长
 * 判定强制为 false（{@code CommonHooks#canCropGrow} 只认 {@code GROW} 与原值）。
 * 只对这三类作物族方块生效，<b>不</b>用「取消所有 randomTick」的粗口径：仙人掌、甘蔗、
 * 竹子、紫颂花也走同一事件（{@code CommonHooks} 共 11 处调用），必须排除。</p>
 *
 * <p><b>③ 种植</b>：注入点 {@code UseItemOnBlockEvent} 的 {@code ITEM_AFTER_BLOCK}
 * 相位。原因（Create 6.0.10 源码核验）：{@code ItemDrainBlock#useItemOn} 见手持
 * {@code BlockItem} 且无流体物品 capability 时直接
 * {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}，种子实际由随后的 {@code ItemStack#useOn}
 * （= {@code ITEM_AFTER_BLOCK} 相位）放下；只拦 {@code BLOCK} 相位拦不到种子。
 * 双端都取消（客户端也要，否则本地先放下再被服务端回滚，会闪一下），提示只在服务端发。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class ItemDrainNetherHeatRules {

    /** ③ 提示文案的 lang key（本任务不改 lang 文件，中英文见任务报告）。 */
    public static final String TOO_HOT_KEY = "message.dimblend_experience.item_drain.nether_too_hot";

    /** 作物族：原版作物（小麦/胡萝卜/马铃薯/甜菜根及模组子类）、茎、下界疣。 */
    private static boolean isCropFamily(Block block) {
        return block instanceof CropBlock || block instanceof StemBlock || block instanceof NetherWartBlock;
    }

    /** N1 保湿或 N2 催熟任一开着（配置未加载时按「关」处理，调用方已保证先判 isLoaded）。 */
    private static boolean heatRuleApplies() {
        return Config.ITEM_DRAIN_IRRIGATION.get() || Config.ITEM_DRAIN_GROWTH.get();
    }

    /**
     * ② 自然生长封禁。
     *
     * <p>事件文档写明只在逻辑服务端触发，但 {@code getLevel()} 类型是
     * {@code LevelAccessor}，故显式判 {@code ServerLevel} 后再读配置
     * （{@code Value#get()} 在未加载窗口抛 ISE）。</p>
     */
    @SubscribeEvent
    public static void onCropGrow(CropGrowEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Config.isLoaded()) {
            return;
        }
        boolean cropFamily = isCropFamily(event.getState().getBlock());
        if (!heatRuleApplies() || !cropFamily) {
            return;
        }
        BlockPos pos = event.getPos();
        for (BlockPos drainPos : coveringDrains(level, pos)) {
            boolean nether = ItemDrainNetherEvaporation.isNetherBiome(level, drainPos);
            if (ItemDrainNetherHeat.suppressesGrowth(nether, true, cropFamily)) {
                event.setResult(CropGrowEvent.Pre.Result.DO_NOT_GROW);
                return;
            }
        }
    }

    /**
     * ③ 种植封禁。
     *
     * <p>目标格按原版口径算，避免另立一套规则：直接问
     * {@code new BlockPlaceContext(event.getUseOnContext()).getClickedPos()}（点击格不可
     * 替换时取相邻格——点耕地顶面即耕地上方；可替换时取点击格本身）；能否种下直接问
     * 植物自身 {@code canSurvive}（与原版 {@code BlockItem#place} 同一判据，含各地块
     * 自己的 {@code mayPlaceOn} 与光照要求）。</p>
     */
    @SubscribeEvent
    public static void onUseItemOnBlock(UseItemOnBlockEvent event) {
        if (event.getUsePhase() != UseItemOnBlockEvent.UsePhase.ITEM_AFTER_BLOCK) {
            return;
        }
        if (!Config.isLoaded() || !heatRuleApplies()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        if (!isCropFamily(blockItem.getBlock())) {
            return;
        }
        Level level = event.getLevel();
        BlockPlaceContext placeContext = new BlockPlaceContext(event.getUseOnContext());
        if (!placeContext.canPlace()) {
            // 目标格放不下：原版本来就种不上，不重复拦截
            return;
        }
        BlockPos target = placeContext.getClickedPos();
        if (!level.isLoaded(target)) {
            return;
        }
        // 能否种下问植物自身：与原版 BlockItem#place 同一判据。不自造土壤规则——
        // canSustainPlant 默认返回 DEFAULT，用「!= FALSE」会把任何方块都当土壤
        boolean plantable = blockItem.getBlock().defaultBlockState().canSurvive(level, target);
        if (!plantable) {
            return;
        }
        boolean tooHot = false;
        for (BlockPos drainPos : coveringDrains(level, target)) {
            boolean nether = ItemDrainNetherEvaporation.isNetherBiome(level, drainPos);
            if (ItemDrainNetherHeat.suppressesPlanting(nether, true, plantable)) {
                tooHot = true;
                break;
            }
        }
        if (!tooHot) {
            return;
        }
        event.cancelWithResult(ItemInteractionResult.FAIL);
        Player player = event.getPlayer();
        if (level instanceof ServerLevel && player != null) {
            player.displayClientMessage(Component.translatable(TOO_HOT_KEY), true);
        }
    }

    /**
     * 覆盖目标格的分液池位置（只判覆盖，群系由调用方按 {@link ItemDrainNetherHeat}
     * 的口径再判）。枚举目标格所在区块为中心 3x3 个<b>已加载</b>区块里的
     * {@code ItemDrainBlockEntity}；未加载区块直接跳过
     * （{@code getChunkSource().getChunkNow} 不触发加载）。
     *
     * <p>返回 {@code List<BlockPos>} 而不是 Create 的类型：本类挂
     * {@code @EventBusSubscriber}，类扫描会解析全部方法描述符，返回类型写成
     * {@code ItemDrainBlockEntity} 会让无 Create 的整合包在扫描阶段就
     * {@code NoClassDefFoundError}。方法体内的 {@code instanceof} 由
     * {@link ModList} 守卫挡在其解析之前。</p>
     */
    private static List<BlockPos> coveringDrains(Level level, BlockPos target) {
        if (!ModList.get().isLoaded("create")) {
            return List.of();
        }
        int radius = Config.ITEM_DRAIN_COVERAGE_RADIUS.get();
        int centerChunkX = target.getX() >> 4;
        int centerChunkZ = target.getZ() >> 4;
        List<BlockPos> result = null;
        for (int chunkX = centerChunkX - 1; chunkX <= centerChunkX + 1; chunkX++) {
            for (int chunkZ = centerChunkZ - 1; chunkZ <= centerChunkZ + 1; chunkZ++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                Map<BlockPos, BlockEntity> blockEntities = chunk.getBlockEntities();
                for (BlockEntity be : blockEntities.values()) {
                    if (!(be instanceof ItemDrainBlockEntity drain)) {
                        continue;
                    }
                    BlockPos drainPos = drain.getBlockPos();
                    if (!ItemDrainNetherHeat.covers(drainPos.getX(), drainPos.getY(), drainPos.getZ(), radius,
                            target.getX(), target.getY(), target.getZ())) {
                        continue;
                    }
                    if (result == null) {
                        result = new ArrayList<>(2);
                    }
                    result.add(drainPos);
                }
            }
        }
        return result == null ? List.of() : result;
    }

    private ItemDrainNetherHeatRules() {
    }
}