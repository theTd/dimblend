package dimblend.experience.client;

import dimblend.experience.DimBlend;
import dimblend.experience.compat.simurail.ClientDeadmanLatch;
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
        if (net.neoforged.fml.ModList.get().isLoaded("create")) {
            // 思索条目按各自 mod 在场性逐条判断（simurail/create_connected/dndecor），
            // 所以这里只需要 Create 在场即可注册插件。
            event.enqueueWork(() -> net.createmod.ponder.foundation.PonderIndex.addPlugin(
                    new dimblend.experience.client.ponder.SupplementPonderPlugin()));
        }
        if (net.neoforged.fml.ModList.get().isLoaded("sable")
                && net.neoforged.fml.ModList.get().isLoaded("veil")
                && !net.neoforged.fml.ModList.get().isLoaded("iris")) {
            event.enqueueWork(dimblend.experience.client.headlight.HeadlightRenderer::initialize);
        }
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(LinkedTransmitterDebugClient::tick));
        // G8/G11 探照灯与观察者烈焰人的物品提示（纯客户端，按注册名判定，不解析第三方类型）
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(SearchlightTooltips::onItemTooltip));
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(SupplementTooltips::onItemTooltip));
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut logout) -> {
                    ClientFarCurseTier.reset();
                    ClientDeadmanLatch.reset();
                    TrainHornClient.reset();
                    dimblend.experience.compat.connected.LinkedTransmitterDiagnostics.reset();
                }));
    }

    @SubscribeEvent
    public static void onRegisterDollRenderers(
            net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        if (net.neoforged.fml.ModList.get().isLoaded("create")) {
            event.registerBlockEntityRenderer(
                    dimblend.experience.compat.dolls.DollKeeperSeats.BLOCK_ENTITY.get(),
                    dimblend.experience.client.dolls.DollKeeperSeatRenderer::new);
        }
    }

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // 睡眠变黑遮罩交还原版（2026-10-31 用户口径）：休息维度不再跳过 SLEEP_OVERLAY，
        // 闭眼时照常渐暗；8 秒后服务端自动起身，遮罩随原版起床退场。
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
        // hardcore_nvg 低电量渐褪：图层类引用其 PlayerVariables，仅在场时注册防类加载失败；
        // 挂在全部 HUD 层之下——只压暗世界画面，准星/快捷栏等 HUD 保持原亮度
        if (net.neoforged.fml.ModList.get().isLoaded("hardcore_nvg")) {
            event.registerBelowAll(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(DimBlend.MODID, "nvg_battery_fade"),
                    NvgBatteryFadeLayer::render);
        }
    }

    private DimBlendClient() {
    }
}
