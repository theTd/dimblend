package dimblend.experience.client;

import dimblend.experience.DimBlend;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端入口：z256 进度条挂到原版盔甲层，方向采样挂游戏总线客户端 tick。
 */
@EventBusSubscriber(modid = DimBlend.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class DimBlendClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(ZProgressHud::onClientTick));
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // 包住盔甲层、在盔甲行渲染完立刻画条，
        // render 内读到的 leftHeight 即盔甲行刚用过的值，不跨层漂移。
        event.wrapLayer(VanillaGuiLayers.ARMOR_LEVEL, layer -> (graphics, partialTick) -> {
            layer.render(graphics, partialTick);
            ZProgressHud.render(graphics);
        });
    }

    private DimBlendClient() {
    }
}
