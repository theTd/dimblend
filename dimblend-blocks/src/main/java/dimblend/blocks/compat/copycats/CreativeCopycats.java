package dimblend.blocks.compat.copycats;

import com.copycatsplus.copycats.foundation.copycat.CCCopycatBlockEntity;
import com.copycatsplus.copycats.foundation.copycat.multistate.MultiStateCopycatBlockEntity;
import com.simibubi.create.content.decoration.copycat.CopycatBlockEntity;
import dimblend.blocks.DimBlendBlocks;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * C 板块：创造模式伪装方块（需要 Copycats+ 在场；Copycats+ 硬依赖 Create，
 * 故 Create 类引用一并安全）。
 * 方块类继承 Copycats+ 的伪装半砖/伪装粱与 Create 本体的伪装板，但 BE 类型是
 * 本模组自有的（两家的 BE 合法方块白名单都是注册期固定的，第三方方块进不去）。
 *
 * <p>硬性规则（规格 §3）：无配方、生存完全不可破坏（硬度 -1 + 阻爆）、
 * 仅创造可放置（{@link CreativeOnlyBlockItem}）、扳手只能撕材质不拆本体。
 * 仅在 Copycats+ 已加载时才注册；类内引用了其类型，故外部只允许经由
 * {@link #register} 在 isLoaded 守卫内触达本类。</p>
 */
public final class CreativeCopycats {

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DimBlendBlocks.MODID);

    /**
     * 床岩级参数：生存不可破坏（-1）、防爆（3600000）、无战利品表。
     * {@code noOcclusion} 与 Copycats/Create 伪装方块一致：不按自身碰撞箱剔除邻居面
     * （否则模型没画出来时会透视）。{@code forceSolidOn} 只强制 {@code isSolid}/
     * {@code blocksMotion}，不恢复满格挡光（薄板不该按满格挡 15 级光）。
     */
    private static BlockBehaviour.Properties creativeProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .noOcclusion()
                .forceSolidOn()
                .strength(-1.0F, 3600000.0F)
                .sound(SoundType.METAL)
                .noLootTable();
    }

    public static final DeferredBlock<CreativeCopycatSlabBlock> CREATIVE_COPYCAT_SLAB =
            DimBlendBlocks.BLOCKS.register("creative_copycat_slab",
                    () -> new CreativeCopycatSlabBlock(creativeProps()));

    public static final DeferredBlock<CreativeCopycatBeamBlock> CREATIVE_COPYCAT_BEAM =
            DimBlendBlocks.BLOCKS.register("creative_copycat_beam",
                    () -> new CreativeCopycatBeamBlock(creativeProps()));

    public static final DeferredBlock<CreativeCopycatPanelBlock> CREATIVE_COPYCAT_PANEL =
            DimBlendBlocks.BLOCKS.register("creative_copycat_panel",
                    () -> new CreativeCopycatPanelBlock(creativeProps()));

    public static final DeferredItem<BlockItem> CREATIVE_COPYCAT_SLAB_ITEM =
            DimBlendBlocks.ITEMS.register("creative_copycat_slab",
                    () -> new CreativeOnlyBlockItem(CREATIVE_COPYCAT_SLAB.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> CREATIVE_COPYCAT_BEAM_ITEM =
            DimBlendBlocks.ITEMS.register("creative_copycat_beam",
                    () -> new CreativeOnlyBlockItem(CREATIVE_COPYCAT_BEAM.get(), new Item.Properties()));

    public static final DeferredItem<BlockItem> CREATIVE_COPYCAT_PANEL_ITEM =
            DimBlendBlocks.ITEMS.register("creative_copycat_panel",
                    () -> new CreativeOnlyBlockItem(CREATIVE_COPYCAT_PANEL.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MultiStateCopycatBlockEntity>> CREATIVE_MULTI_STATE_COPYCAT =
            BLOCK_ENTITIES.register("creative_multi_state_copycat", multiStateCopycatType());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CCCopycatBlockEntity>> CREATIVE_COPYCAT =
            BLOCK_ENTITIES.register("creative_copycat", copycatType());

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<CopycatBlockEntity>> CREATIVE_CREATE_COPYCAT =
            BLOCK_ENTITIES.register("creative_create_copycat", createCopycatType());

    /**
     * BlockEntitySupplier 两参签名（1.21.1 无 type 参数）：type 在运行期实体创建时
     * 从 holder 自取（注册期未就绪）；supplier 经辅助方法构造，规避静态初始化器
     * 对字段的直接自引用限制。
     */
    private static java.util.function.Supplier<BlockEntityType<MultiStateCopycatBlockEntity>> multiStateCopycatType() {
        return () -> BlockEntityType.Builder.of(
                (pos, state) -> new MultiStateCopycatBlockEntity(CREATIVE_MULTI_STATE_COPYCAT.get(), pos, state),
                CREATIVE_COPYCAT_SLAB.get()).build(null);
    }

    private static java.util.function.Supplier<BlockEntityType<CCCopycatBlockEntity>> copycatType() {
        return () -> BlockEntityType.Builder.of(
                (pos, state) -> new CCCopycatBlockEntity(CREATIVE_COPYCAT.get(), pos, state),
                CREATIVE_COPYCAT_BEAM.get()).build(null);
    }

    private static java.util.function.Supplier<BlockEntityType<CopycatBlockEntity>> createCopycatType() {
        return () -> BlockEntityType.Builder.of(
                (pos, state) -> new CopycatBlockEntity(CREATIVE_CREATE_COPYCAT.get(), pos, state),
                CREATIVE_COPYCAT_PANEL.get()).build(null);
    }

    /** 仅在 Copycats+ 已加载时由主类调用；直接加载本类会因缺失依赖崩溃。 */
    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
        // 放置兜底（GAME 总线）：拦生存/非玩家来源的一切创造变体放置，
        // 含上游粱 helper 的 instanceof 连带向量
        NeoForge.EVENT_BUS.addListener(CreativeCopycatGuard::onEntityPlace);
    }

    private CreativeCopycats() {
    }
}
