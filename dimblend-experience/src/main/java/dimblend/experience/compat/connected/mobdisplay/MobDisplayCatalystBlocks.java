package dimblend.experience.compat.connected.mobdisplay;

import dimblend.experience.DimBlend;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 生物展示催化剂方块与方块实体的注册（Audit Default #12）。
 *
 * <p><b>没有 BlockItem</b>：这个方块永远由 {@code create_connected:empty_fan_catalyst} 就地换过来，
 * 挖下来只会掉 {@code empty_fan_catalyst}（外加里面的抓捕盒），所以不需要自己的物品形态，
 * 也不需要创造模式物品栏条目。</p>
 *
 * <p>整组注册只在 {@code create} 与 {@code create_connected} 都在场时发生
 * （贴图用 Create 的黄铜漏斗框，行为依赖 create_connected 的风扇催化剂方块身份）。</p>
 */
public final class MobDisplayCatalystBlocks {

    /** 方块 id（同时是贴图/模型/loot 的目录名）。 */
    public static final String ID = "mob_display_catalyst";

    /** create_connected 的空风扇催化剂（本方块的前身与本方块的掉落物）。 */
    public static final String EMPTY_FAN_CATALYST_ID = "create_connected:empty_fan_catalyst";

    /** creatorsword 的抓捕盒（外壳 + 里面的生物数据都从这里读）。 */
    public static final String CAPTURE_BOX_ID = "creatorsword:capture_box";

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DimBlend.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DimBlend.MODID);

    /**
     * 方块属性：整块铁块外观（硬度和抗爆照铁块）+ 无遮挡（内圈是玻璃，要能透过去看生物）
     * + 不当红石导体 + 不挡视线/不窒息（生物在方块内部转，玩家要看得见）。
     */
    public static final DeferredBlock<MobDisplayCatalystBlock> MOB_DISPLAY_CATALYST = BLOCKS.registerBlock(ID,
            MobDisplayCatalystBlock::new,
            BlockBehaviour.Properties.ofFullCopy(Blocks.IRON_BLOCK)
                    .noOcclusion()
                    .isViewBlocking((state, level, pos) -> false)
                    .isSuffocating((state, level, pos) -> false));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MobDisplayCatalystBlockEntity>>
            MOB_DISPLAY_CATALYST_ENTITY = BLOCK_ENTITIES.register(ID,
                    () -> BlockEntityType.Builder.of(MobDisplayCatalystBlockEntity::new,
                            MOB_DISPLAY_CATALYST.get()).build(null));

    /** 两个前置模组是否都在场（注册 / 交互 / 渲染三处共用）。 */
    public static boolean isAvailable() {
        return ModList.get().isLoaded("create") && ModList.get().isLoaded("create_connected");
    }

    /**
     * 由 {@code DimBlend} 构造期调用一次（manifest 里有调用行）。
     * 前置缺席时直接返回，连 DeferredRegister 都不挂到总线上。
     */
    public static void register(IEventBus modBus) {
        if (!isAvailable()) {
            return;
        }
        BLOCKS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }

    private MobDisplayCatalystBlocks() {
    }
}
