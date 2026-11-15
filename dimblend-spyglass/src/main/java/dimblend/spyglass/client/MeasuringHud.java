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
 * Shows the {@code 测算中 / Measuring...} line directly below the crosshair while the player is
 * scoping and the server has not finished the structure scan yet.
 *
 * <p>Without it the first scan of a scoping session leaves the lens empty for as long as the server
 * takes to read the region files, which reads like "nothing here". The line is registered above
 * {@link VanillaGuiLayers#CAMERA_OVERLAYS}, so it stays visible on the scope overlay's black frame.
 *
 * <p>{@link CrosshairDistanceLayer} prints its readout at the <b>top</b> of the screen
 * ({@code TOP_Y = 4}), not under the crosshair, so this line cannot collide with it and needs no
 * second offset.
 */
// RegisterGuiLayersEvent is an IModBusEvent: FML registers this listener on the mod bus by itself.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class MeasuringHud {
    static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "measuring");

    static final String TEXT_KEY = "dimblend_spyglass.label.measuring";

    /** Baseline offset below the screen's vertical center; the vanilla crosshair ends 7 GUI px out. */
    static final int CROSSHAIR_OFFSET_Y = 10;
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
        int y = baselineY(graphics.guiHeight());
        graphics.drawString(minecraft.font, text, x, y, COLOR, true);
    }

    /** Baseline of the line: one short gap under the centered crosshair. */
    static int baselineY(int guiHeight) {
        return guiHeight / 2 + CROSSHAIR_OFFSET_Y;
    }

    private MeasuringHud() {
    }
}
