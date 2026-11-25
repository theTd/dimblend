package dimblend.experience.compat.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

/**
 * 大容量储存的熔岩免疫（LOCKED DECISION #16，2026-11-25 用户原话"方案 1 + 抽屉"）。
 *
 * <p>普通方块被 {@link SableLavaMelt} 按硬度慢慢烧掉；但一台"装了几百上千格物品"的仓储
 * 如果被岩浆慢慢蚀空，损失与"整台机器消失"等价，且玩家没有任何预警手段。所以：</p>
 *
 * <ul>
 *   <li><b>抽屉</b>：{@code create_item_drawers} 命名空间下的全部方块（等价于
 *       {@code #create_item_drawers:drawers}）一律免疫——抽屉天生是"一物一格"，单抽屉也可能
 *       只装了 1 格货，但它的"格数"语义是 1 万起步。</li>
 *   <li><b>保险库 / 筒仓 / 集装箱</b>：BE 是 {@code ItemVaultBlockEntity} 即免疫（
 *       {@code create:item_vault}、{@code create_connected:item_silo}、
 *       全部 {@code dndecor:*container} 都继承它，1×1×1 的最小结构也算）。</li>
 *   <li><b>多格容器</b>：解析 {@code IMultiBlockEntityContainer#getController()} 到主方块，
 *       在<b>主方块</b>上取 {@code Capabilities.ItemHandler.BLOCK}，{@code getSlots() > 32} 即免疫。
 *       取主方块而不是当前格：Create 的整台结构只在主方块上挂库存能力，
 *       贴在任意一段读到的都是整台。</li>
 *   <li><b>兜底</b>：{@code Container#getContainerSize() > 32}（模组自带的多格箱子/柜子）。</li>
 * </ul>
 *
 * <p><b>只用 {@code getChunkNow}</b>（{@code dimblend-dev} §8.28）：本方法在
 * {@code touchesLava == true} 之后才被调用（非熔岩方块零开销），但仍在扫描循环里，
 * 一旦这里同步加载区块就会卡主线程。区块不在内存时直接视为"不是大容量储存"——
 * 它确实熔不掉，因为那条链只在已加载区块里推进。</p>
 */
public final class LargeStorageLavaImmunity {

    /** "大容量"的门槛：严格大于 32 格（32 格的单箱不算，Create 保险库最小 1 台也有 32 格）。 */
    public static final int MIN_LARGE_SLOTS_EXCLUSIVE = 32;

    /** 抽屉模组命名空间。 */
    public static final String DRAWERS_NAMESPACE = "create_item_drawers";

    /** 抽屉标签（模组自己声明的）。 */
    public static final TagKey<Block> DRAWERS_TAG = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DRAWERS_NAMESPACE, "drawers"));

    /** 只用命名空间判定：单抽屉/双抽屉/四抽屉以及抽屉控制器都在这个命名空间下。 */
    public static boolean isDrawerNamespace(@Nullable String namespace) {
        return DRAWERS_NAMESPACE.equals(namespace);
    }

    /** 格数口径：<b>严格大于</b> 32。 */
    public static boolean isLargeSlotCount(int slots) {
        return slots > MIN_LARGE_SLOTS_EXCLUSIVE;
    }

    public static boolean isDrawerBlock(BlockState state) {
        if (state == null) {
            return false;
        }
        if (state.is(DRAWERS_TAG)) {
            return true;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id != null && isDrawerNamespace(id.getNamespace());
    }

    /**
     * 该方块是否免疫熔岩蚀刻（只在"已经碰到熔岩"时调用）。
     */
    public static boolean isImmune(Level level, BlockPos pos, BlockState state) {
        if (level == null || pos == null || state == null) {
            return false;
        }
        if (isDrawerBlock(state)) {
            return true;
        }
        BlockEntity blockEntity = loadedBlockEntity(level, pos);
        if (blockEntity == null) {
            return false;
        }
        if (blockEntity instanceof Container container && isLargeSlotCount(container.getContainerSize())) {
            return true;
        }
        return ModList.get().isLoaded("create") && Create.isCreateLargeStorage(level, pos, blockEntity);
    }

    /**
     * Create 相关的两种口径。整类只在 {@code create} 在场时被加载——absent 环境里
     * 方法体中的 Create 类型永远不会解析。
     */
    private static final class Create {

        private static boolean isCreateLargeStorage(Level level, BlockPos pos, BlockEntity blockEntity) {
            if (blockEntity instanceof com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity) {
                return true;
            }
            if (!(blockEntity instanceof com.simibubi.create.foundation.blockEntity.IMultiBlockEntityContainer multi)) {
                return false;
            }
            BlockPos controller = multi.getController();
            BlockPos capabilityPos = controller == null ? pos : controller;
            // 非阻塞守卫：跨区块多结构的主方块所在区块未加载时绝不触发同步加载
            if (loadedBlockEntity(level, capabilityPos) == null) {
                return false;
            }
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, capabilityPos, null);
            return handler != null && isLargeSlotCount(handler.getSlots());
        }
    }

    /** 非阻塞区块内 BE 查找（{@code getChunkNow} + {@code getBlockEntities}）。 */
    @Nullable
    public static BlockEntity loadedBlockEntity(Level level, BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return chunk == null ? null : chunk.getBlockEntities().get(pos);
    }

    private LargeStorageLavaImmunity() {
    }
}
