package dimblend.blocks.compat.create.funnel;

import com.simibubi.create.content.logistics.funnel.FunnelRenderer;
import com.simibubi.create.content.logistics.funnel.FunnelVisual;

import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizerRegistry;
import dev.engine_room.flywheel.lib.visualization.SimpleBlockEntityVisualizer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 卸料漏斗客户端：直接复用 Create 的翻板 BER 与 Flywheel visual。
 *
 * <p>必须仅由 {@code DimBlendBlocks} 在 {@code create && dist.isClient()} 守卫下经
 * {@link #register(IEventBus)} 挂载；不得使用无条件 {@code @EventBusSubscriber}，
 * 避免无 Create 环境下反射扫描方法签名触发 {@code NoClassDefFoundError}。</p>
 */
public final class DumpFunnelClient {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(DumpFunnelClient::onRegisterRenderers);
        modEventBus.addListener(DumpFunnelClient::onClientSetup);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(DumpFunnels.DUMP_FUNNEL_BE.get(), FunnelRenderer::new);
    }

    private static void onClientSetup(FMLClientSetupEvent event) {
        VisualizerRegistry.setVisualizer(DumpFunnels.DUMP_FUNNEL_BE.get(),
                SimpleBlockEntityVisualizer.builder(DumpFunnels.DUMP_FUNNEL_BE.get())
                        .factory(DumpFunnelClient::createFunnelVisual)
                        .neverSkipVanillaRender()
                        .apply());
    }

    private static FunnelVisual createFunnelVisual(VisualizationContext context, DumpFunnelBlockEntity blockEntity,
            float partialTick) {
        return new FunnelVisual(context, blockEntity, partialTick);
    }

    private DumpFunnelClient() {
    }
}
