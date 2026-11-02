package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * Shows the approximate distance of the mark under the crosshair at the top of the screen.
 *
 * <p>Reads {@link MarkScreenFrame} from {@link StructureMarkLayer}, which runs earlier in the same
 * GUI pass and only publishes marks that were actually drawn. The hit test is the 16×16 box plus a
 * 4-pixel tolerance; when two marks both contain the crosshair the one nearer the screen center wins.
 * The text disappears on the next frame the crosshair leaves the box — no fade.
 */
// RegisterGuiLayersEvent is an IModBusEvent: FML registers this listener on the mod bus by itself.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class CrosshairDistanceLayer {
    static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "crosshair_distance");

    private static final double TOLERANCE_PX = 4.0;
    private static final int TOP_Y = 4;
    private static final int COLOR = 0xFFFFFFFF;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // Above the scope overlay, so the text stays readable on the black frame.
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, LAYER_ID, CrosshairDistanceLayer::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!SpyglassScopeGate.isScoping(minecraft)) {
            return;
        }
        MarkScreenFrame frame = MarkScreenFrame.current();
        if (frame.marks().isEmpty()) {
            return;
        }

        List<MarkScreenFrame.Mark> drawn = new ArrayList<>(frame.marks().size());
        double[] screenX = new double[frame.marks().size()];
        double[] screenY = new double[frame.marks().size()];
        for (MarkScreenFrame.Mark mark : frame.marks()) {
            if (!mark.drawn()) {
                continue;
            }
            screenX[drawn.size()] = mark.x();
            screenY[drawn.size()] = mark.y();
            drawn.add(mark);
        }
        if (drawn.isEmpty()) {
            return;
        }

        int hit = CrosshairPicking.hit(
                screenX,
                screenY,
                drawn.size(),
                frame.guiWidth() * 0.5,
                frame.guiHeight() * 0.5,
                TOLERANCE_PX);
        if (hit < 0) {
            return;
        }

        String text = DistanceText.format(drawn.get(hit).distance());
        int x = (int) Math.round((frame.guiWidth() - minecraft.font.width(text)) * 0.5);
        graphics.drawString(minecraft.font, text, x, TOP_Y, COLOR, true);
    }

    private CrosshairDistanceLayer() {
    }
}
