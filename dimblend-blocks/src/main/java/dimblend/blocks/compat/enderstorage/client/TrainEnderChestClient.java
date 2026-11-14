package dimblend.blocks.compat.enderstorage.client;

import dimblend.blocks.compat.enderstorage.TrainEnderChests;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 列车末影物流箱客户端渲染注册（仅客户端 + {@code enderstorage} 在场时触达）。
 */
public final class TrainEnderChestClient {

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(TrainEnderChestClient::onRegisterRenderers);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        BlockEntityRenderers.register(TrainEnderChests.TRAIN_ENDER_CHEST_BE.get(), TrainEnderChestRenderer::new);
    }

    private TrainEnderChestClient() {
    }
}
