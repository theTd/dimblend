package dimblend.blocks.compat.rail.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 列车加压管道的客户端接入点：注册跨车厢线缆渲染与"登出即清连接表"。
 *
 * <p>只在 {@code create && sable && dist.isClient()} 下由 {@link dimblend.blocks.DimBlendBlocks}
 * 调用；本类硬引用 {@code ClientSubLevel} 等客户端类型。</p>
 */
public final class TrainTubeClient {

    private TrainTubeClient() {
    }

    public static void register(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(TrainTubeCableRenderer::onRenderLevelStage);
        NeoForge.EVENT_BUS.addListener(TrainTubeClient::onLoggingOut);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientTrainTubeLinks.clear();
    }
}
