package dimblend.blocks.compat.deployer.client;

import dimblend.blocks.compat.deployer.DumpPanelBehaviour;
import net.createmod.catnip.gui.ScreenOpener;
import net.neoforged.bus.api.IEventBus;

/**
 * 卸料仪表的客户端接入点。
 *
 * <p>模型烘焙前把暗钢蓝面板的 {@code PartialModel} 登记进 Flywheel，并提供客户端界面打开入口。
 * 必须仅在 {@code create && deployer && dist.isClient()} 守卫下触达
 * （本类硬引用 Flywheel / Screen 类型，专用服务端不可加载）。</p>
 */
public final class DumpGaugeClient {

    private DumpGaugeClient() {}

    public static void register(IEventBus modEventBus) {
        DumpGaugePartialModels.init();
    }

    public static void openScreen(DumpPanelBehaviour behaviour) {
        ScreenOpener.open(new DumpPanelScreen(behaviour));
    }
}
