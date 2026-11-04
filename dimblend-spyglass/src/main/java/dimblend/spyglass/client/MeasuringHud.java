package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassBusyHandlers;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Shows the {@code 测算中 / Measuring...} line near the bottom of the screen while the player is
 * scoping and the server has not finished the structure scan yet.
 *
 * <p>Without it the first scan of a scoping session leaves the lens empty for as long as the server
 * takes to read the region files, which reads like "nothing here". The line is registered above
 * {@link VanillaGuiLayers#CAMERA_OVERLAYS}, so it stays visible on the scope overlay's black frame.
 */
// RegisterGuiLayersEvent is an IModBusEvent: FML registers this listener on the mod bus by itself.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class MeasuringHud {
    static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "measuring");

    static final String TEXT_KEY = "dimblend_spyglass.label.measuring";

    /** Baseline of the line, measured up from the bottom of the GUI. */
    private static final int BOTTOM_MARGIN = 30;
    private static final int COLOR = 0xFFFFFFFF;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, LAYER_ID, MeasuringHud::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!SpyglassScopeGate.isScoping(minecraft) || !SpyglassBusyHandlers.isBusy()) {
            return;
        }
        Component text = Component.translatable(TEXT_KEY);
        int x = (graphics.guiWidth() - minecraft.font.width(text)) / 2;
        int y = graphics.guiHeight() - BOTTOM_MARGIN;
        graphics.drawString(minecraft.font, text, x, y, COLOR, true);
    }

    private MeasuringHud() {
    }
}
