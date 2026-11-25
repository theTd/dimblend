package dimblend.blocks.compat.deployer;

import java.util.function.Supplier;

import dimblend.blocks.DimBlendBlocks;
import net.liukrast.deployer.lib.logistics.board.PanelType;
import net.liukrast.deployer.lib.registry.DeployerRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.world.item.Item;

/**
 * 卸料仪表的注册入口（仅 Create + Deployer 双在场时触达，见 {@code DimBlendBlocks} 的守卫）。
 *
 * <p>本模组自己不注册行为面板的方块：Deployer 的面板物品都套在 Create 的
 * {@code factory_gauge} 方块上，我们只往 Deployer 的自定义 {@code PanelType} 注册表
 * 里塞一个类型，再注册一个"带类型的面板物品"。</p>
 */
public final class DumpGauges {

    public static final ResourceLocation DUMP_GAUGE_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendBlocks.MODID, "dump_gauge");

    private static final DeferredRegister<PanelType<?>> PANELS =
            DeferredRegister.create(DeployerRegistries.PANEL_KEY, DimBlendBlocks.MODID);

    public static final DeferredHolder<PanelType<?>, PanelType<DumpPanelBehaviour>> DUMP_PANEL =
            PANELS.register("dump_gauge",
                    () -> new PanelType<>(DumpPanelBehaviour::new, DumpPanelBehaviour.class));

    public static final DeferredItem<Item> DUMP_GAUGE_ITEM =
            DimBlendBlocks.ITEMS.register("dump_gauge",
                    () -> new DumpGaugeItem(panelType(), new Item.Properties()));

    /** 面板物品要一个 {@code Supplier<PanelType<?>>}：用方法而不是直接传 holder，避免通配符逆变。 */
    static Supplier<PanelType<?>> panelType() {
        return () -> DUMP_PANEL.get();
    }

    public static void register(IEventBus modEventBus) {
        PANELS.register(modEventBus);
    }

    private DumpGauges() {
    }
}
