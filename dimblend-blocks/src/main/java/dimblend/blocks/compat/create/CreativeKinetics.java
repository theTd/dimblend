package dimblend.blocks.compat.create;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.encasing.EncasingRegistry;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.BracketedKineticBlockEntity;
import com.simibubi.create.content.kinetics.simpleRelays.SimpleKineticBlockEntity;
import dimblend.blocks.DimBlendBlocks;
import dimblend.blocks.compat.copycats.CreativeOnlyBlockItem;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * K 板块：Create 动力传动件的创造模式变体（传动杆/小齿轮及各自的安山/黄铜套壳变体，
 * 需要 Create 在场）。方块类继承 Create 本体的轴/齿轮/套壳块，但 BE 类型是本模组
 * 自有的（Create 的 BE 合法方块白名单注册期固定，第三方方块进不去——与 C 板块同理）。
 *
 * <p>硬性规则（与 C 板块同一口径）：无配方、生存完全不可破坏（硬度 -1 + 阻爆 +
 * 无战利品表）、仅创造可放置（{@link CreativeOnlyBlockItem} 与
 * {@link CreativeKineticGuard} 双重封堵）、扳手不能拆不能旋转（裸块扳手右键在
 * 轴↔齿轮间互换，套壳块潜行扳手拆壳）。</p>
 *
 * <p>应力结论（javap 核实 {@code BlockStressValues.getImpact/getCapacity}：注册表查不到
 * 时返回常量 0.0）：未注册的方块默认应力影响/容量即为 0，轴本就无应力负担
 * （Create 自己也是 setNoImpact），故无需为这 6 个方块注册应力值。</p>
 *
 * <p>仅在 Create 已加载时才注册；类内引用了其类型，故外部只允许经由
 * {@link #register} 在 isLoaded 守卫内触达本类。</p>
 */
public final class CreativeKinetics {

    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DimBlendBlocks.MODID);

    /**
     * 床岩级参数：生存不可破坏（-1）、防爆（3600000）、无战利品表。
     * {@code mapColor(METAL) + forceSolidOff()} 对齐 Create 轴注册链；Create 轴/齿轮
     * 未设 {@code noOcclusion}（靠 forceSolidOff 挡住邻居面剔除），此处显式补上——
     * 与 C 板块 creativeProps 同形，对全方块套壳变体也只是放宽实心剔除，无负作用。
     */
    private static BlockBehaviour.Properties creativeProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .forceSolidOff()
                .noOcclusion()
                .strength(-1.0F, 3600000.0F)
                .sound(SoundType.METAL)
                .noLootTable();
    }

    public static final DeferredBlock<CreativeShaftBlock> CREATIVE_SHAFT =
            DimBlendBlocks.BLOCKS.register("creative_shaft",
                    () -> new CreativeShaftBlock(creativeProps(), bracketedKineticBlockEntityType()));

    public static final DeferredBlock<CreativeCogwheelBlock> CREATIVE_COGWHEEL =
            DimBlendBlocks.BLOCKS.register("creative_cogwheel",
                    () -> new CreativeCogwheelBlock(creativeProps(), bracketedKineticBlockEntityType()));

    public static final DeferredBlock<CreativeEncasedShaftBlock> CREATIVE_ANDESITE_ENCASED_SHAFT =
            DimBlendBlocks.BLOCKS.register("creative_andesite_encased_shaft",
                    () -> new CreativeEncasedShaftBlock(creativeProps(), AllBlocks.ANDESITE_CASING::get,
                            encasedShaftBlockEntityType()));

    public static final DeferredBlock<CreativeEncasedShaftBlock> CREATIVE_BRASS_ENCASED_SHAFT =
            DimBlendBlocks.BLOCKS.register("creative_brass_encased_shaft",
                    () -> new CreativeEncasedShaftBlock(creativeProps(), AllBlocks.BRASS_CASING::get,
                            encasedShaftBlockEntityType()));

    public static final DeferredBlock<CreativeEncasedCogwheelBlock> CREATIVE_ANDESITE_ENCASED_COGWHEEL =
            DimBlendBlocks.BLOCKS.register("creative_andesite_encased_cogwheel",
                    () -> new CreativeEncasedCogwheelBlock(creativeProps(), AllBlocks.ANDESITE_CASING::get,
                            encasedCogwheelBlockEntityType()));

    public static final DeferredBlock<CreativeEncasedCogwheelBlock> CREATIVE_BRASS_ENCASED_COGWHEEL =
            DimBlendBlocks.BLOCKS.register("creative_brass_encased_cogwheel",
                    () -> new CreativeEncasedCogwheelBlock(creativeProps(), AllBlocks.BRASS_CASING::get,
                            encasedCogwheelBlockEntityType()));

    public static final DeferredItem<BlockItem> CREATIVE_SHAFT_ITEM =
            DimBlendBlocks.ITEMS.register("creative_shaft",
                    () -> new CreativeOnlyBlockItem(CREATIVE_SHAFT.get(), new Item.Properties()));

    public static final DeferredItem<CreativeCogwheelBlockItem> CREATIVE_COGWHEEL_ITEM =
            DimBlendBlocks.ITEMS.register("creative_cogwheel",
                    () -> new CreativeCogwheelBlockItem(CREATIVE_COGWHEEL.get(), new Item.Properties()));

    // 套壳变体与 Create 一致：无物品形态（世界里由套壳/拆壳转换得到）。

    /** 轴+齿轮共用：BE 直接用 Create 的 BracketedKineticBlockEntity，支架行为谓词
     * 是 instanceof AbstractSimpleShaftBlock，本板块方块继承即满足。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BracketedKineticBlockEntity>> CREATIVE_BRACKETED_KINETIC =
            BLOCK_ENTITIES.register("creative_bracketed_kinetic", bracketedKineticType());

    /** 套壳轴：BE 用 Create 的 KineticBlockEntity（对齐上游 ENCASED_SHAFT）。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<KineticBlockEntity>> CREATIVE_ENCASED_SHAFT_BE =
            BLOCK_ENTITIES.register("creative_encased_shaft", encasedShaftType());

    /** 套壳齿轮：BE 用 Create 的 SimpleKineticBlockEntity（对齐上游 ENCASED_COGWHEEL）。 */
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SimpleKineticBlockEntity>> CREATIVE_ENCASED_COGWHEEL_BE =
            BLOCK_ENTITIES.register("creative_encased_cogwheel", encasedCogwheelType());

    /**
     * BlockEntitySupplier 两参签名（1.21.1 无 type 参数）：type 在运行期实体创建时
     * 从 holder 自取（注册期未就绪）；supplier 经辅助方法构造，规避静态初始化器
     * 对字段的直接自引用限制（与 C 板块同源约束）。
     */
    private static java.util.function.Supplier<BlockEntityType<BracketedKineticBlockEntity>> bracketedKineticType() {
        return () -> BlockEntityType.Builder.of(
                (pos, state) -> new BracketedKineticBlockEntity(CREATIVE_BRACKETED_KINETIC.get(), pos, state),
                CREATIVE_SHAFT.get(), CREATIVE_COGWHEEL.get()).build(null);
    }

    private static java.util.function.Supplier<BlockEntityType<KineticBlockEntity>> encasedShaftType() {
        return () -> BlockEntityType.Builder.of(
                (pos, state) -> new KineticBlockEntity(CREATIVE_ENCASED_SHAFT_BE.get(), pos, state),
                CREATIVE_ANDESITE_ENCASED_SHAFT.get(), CREATIVE_BRASS_ENCASED_SHAFT.get()).build(null);
    }

    private static java.util.function.Supplier<BlockEntityType<SimpleKineticBlockEntity>> encasedCogwheelType() {
        return () -> BlockEntityType.Builder.of(
                (pos, state) -> new SimpleKineticBlockEntity(CREATIVE_ENCASED_COGWHEEL_BE.get(), pos, state),
                CREATIVE_ANDESITE_ENCASED_COGWHEEL.get(), CREATIVE_BRASS_ENCASED_COGWHEEL.get()).build(null);
    }

    /*
     * 方块构造器取 BE 类型必须经静态方法间接：BLOCKS 的注册 lambda 在 BE holder 字段
     * 赋值之前就会创建（但执行更晚），直接写 `() -> CREATIVE_X.get()` 会撞上静态
     * 初始化器的前向引用限制（与 C 板块同款约束）。
     */
    static java.util.function.Supplier<BlockEntityType<BracketedKineticBlockEntity>> bracketedKineticBlockEntityType() {
        return CREATIVE_BRACKETED_KINETIC::get;
    }

    static java.util.function.Supplier<BlockEntityType<KineticBlockEntity>> encasedShaftBlockEntityType() {
        return CREATIVE_ENCASED_SHAFT_BE::get;
    }

    static java.util.function.Supplier<BlockEntityType<SimpleKineticBlockEntity>> encasedCogwheelBlockEntityType() {
        return CREATIVE_ENCASED_COGWHEEL_BE::get;
    }

    /** 仅在 Create 已加载时由主类调用；直接加载本类会因缺失依赖崩溃。 */
    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
        // 套壳映射：方块实例要等注册事件后才存在，入队到 common setup 再登记
        modEventBus.addListener(CreativeKinetics::onCommonSetup);
        // 放置兜底（GAME 总线）：拦生存/非玩家来源的一切创造变体放置，
        // 含齿轮物品对角/嵌入 helper 与轴极柱 helper 的 placeInWorld 旁路
        NeoForge.EVENT_BUS.addListener(CreativeKineticGuard::onEntityPlace);
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(CreativeKinetics::registerEncasingVariants);
    }

    /** 套壳变体映射：EncasableBlock.tryEncase 据此找到源方块可用的套壳目标。 */
    private static void registerEncasingVariants() {
        EncasingRegistry.addVariant(CREATIVE_SHAFT.get(), CREATIVE_ANDESITE_ENCASED_SHAFT.get());
        EncasingRegistry.addVariant(CREATIVE_SHAFT.get(), CREATIVE_BRASS_ENCASED_SHAFT.get());
        EncasingRegistry.addVariant(CREATIVE_COGWHEEL.get(), CREATIVE_ANDESITE_ENCASED_COGWHEEL.get());
        EncasingRegistry.addVariant(CREATIVE_COGWHEEL.get(), CREATIVE_BRASS_ENCASED_COGWHEEL.get());
    }

    private CreativeKinetics() {
    }
}
