package dimblend.blocks.compat.deployer.client;

import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedClientHandler;

import dimblend.blocks.compat.deployer.DumpPanelBehaviour;
import net.createmod.catnip.gui.ScreenOpener;
import net.neoforged.bus.api.IEventBus;

/**
 * 卸料仪表的客户端接入点。
 *
 * <p>模型烘焙前把暗钢蓝面板的 {@code PartialModel} 登记进 Flywheel，并提供客户端界面与每 tick 描边入口。
 * 必须仅在 {@code create && deployer && dist.isClient()} 守卫下触达
 * （本类硬引用 Flywheel / Screen 类型，专用服务端不可加载）；两个方法本身也只在客户端分支里被调用。</p>
 */
public final class DumpGaugeClient {

    private DumpGaugeClient() {}

    public static void register(IEventBus modEventBus) {
        DumpGaugePartialModels.init();
    }

    public static void openScreen(DumpPanelBehaviour behaviour) {
        ScreenOpener.open(new DumpPanelScreen(behaviour));
    }

    /**
     * 客户端每 tick 的面板描边（手里拿着同频率物流物品时高亮这块面板）。
     *
     * <p>Create 自己是在 {@code FactoryPanelBehaviour#tick} 的客户端分支里调
     * {@code LogisticallyLinkedClientHandler.tickPanel(this)}，而 Deployer 把那条分支整段取消了
     * （{@code FactoryPanelBehaviourMixin} 在 {@code isClientSide()} 处 cancel），
     * 所以改由 {@link DumpPanelBehaviour#tick} 的客户端分支显式调进来。</p>
     */
    public static void tickPanel(DumpPanelBehaviour behaviour) {
        LogisticallyLinkedClientHandler.tickPanel(behaviour);
    }
}
