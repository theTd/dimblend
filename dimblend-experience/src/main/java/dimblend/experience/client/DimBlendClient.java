package dimblend.experience.client;

import dimblend.experience.DimBlend;
import dimblend.experience.exploration.ClientFarCurseTier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * 客户端入口：z256 进度条挂到原版盔甲层；断线复位同步下来的诅咒层级（游戏总线）。
 */
@EventBusSubscriber(modid = DimBlend.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class DimBlendClient {

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut logout) -> ClientFarCurseTier.reset()));
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // 包住盔甲层、在盔甲行渲染完立刻画条，
        // render 内读到的 leftHeight 即盔甲行刚用过的值，不跨层漂移。
        event.wrapLayer(VanillaGuiLayers.ARMOR_LEVEL, layer -> (graphics, partialTick) -> {
            layer.render(graphics, partialTick);
            ZProgressHud.render(graphics);
        });
        // 创造下血量/盔甲/食物整组层被原版跳过、上面那个包裹永不触发，
        // 改包 HOTBAR（创造也渲染）回退绘制；生存/冒险由回退入口自行跳过防重画。
        event.wrapLayer(VanillaGuiLayers.HOTBAR, layer -> (graphics, partialTick) -> {
            layer.render(graphics, partialTick);
            ZProgressHud.renderCreativeFallback(graphics);
        });
    }

    private DimBlendClient() {
    }
}
