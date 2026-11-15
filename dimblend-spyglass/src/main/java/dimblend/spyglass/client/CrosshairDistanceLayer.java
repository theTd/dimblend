package dimblend.spyglass.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.spyglass.DimBlendSpyglass;
import javax.annotation.Nullable;
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
 * GUI pass. The hit test is the box that was actually drawn plus a 4-pixel tolerance (wider for the
 * selected {@code [?]}/{@code [!]}); only marks that were drawn count, and when two marks both
 * contain the crosshair the one nearer the screen center wins. The text is drawn at twice the font
 * size, matching the doubled marks. It disappears on the next frame the crosshair leaves the box —
 * no fade.
 *
 * <p>An external marker that asked for guidance ({@code guideWithArrowAndDistance}) also answers
 * the readout, even with the spyglass lowered; structure marks keep the scoping gate and external
 * markers that did not ask never show a distance.
 */
// RegisterGuiLayersEvent is an IModBusEvent: FML registers this listener on the mod bus by itself.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class CrosshairDistanceLayer {
    static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "crosshair_distance");

    /** Same box-plus-tolerance rule as the click handler, so hover and click agree. */
    static final double TOLERANCE_PX = 4.0;
    private static final float TEXT_SCALE = 2.0F;
    /** Top-of-screen row of the distance readout; the measuring line sits at the crosshair instead. */
    static final int TOP_Y = 4;
    private static final int COLOR = 0xFFFFFFFF;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        // Above the scope overlay, so the text stays readable on the black frame.
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, LAYER_ID, CrosshairDistanceLayer::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        MarkScreenFrame frame = MarkScreenFrame.current();
        double crosshairX = frame.guiWidth() * 0.5;
        double crosshairY = frame.guiHeight() * 0.5;
        String text = guideDistance(frame, crosshairX, crosshairY);
        if (text == null && SpyglassScopeGate.isScoping(minecraft)) {
            int hit = frame.hit(crosshairX, crosshairY, TOLERANCE_PX);
            if (hit >= 0) {
                text = DistanceText.format(frame.marks().get(hit).distance());
            }
        }
        if (text == null) {
            return;
        }

        // Center the scaled text with its real (doubled) width, then draw it through a 2x pose. Pose
        // transforms scale the translation too, hence the division.
        int width = Math.round(minecraft.font.width(text) * TEXT_SCALE);
        int x = (int) Math.round((frame.guiWidth() - width) * 0.5);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
        pose.translate(x / TEXT_SCALE, TOP_Y / TEXT_SCALE, 0.0F);
        graphics.drawString(minecraft.font, text, 0, 0, COLOR, true);
        pose.popPose();
    }

    /** Distance of the guide marker under the crosshair, or {@code null} for none. */
    private static @Nullable String guideDistance(
            MarkScreenFrame frame, double crosshairX, double crosshairY) {
        int hit = frame.hitHudMarker(crosshairX, crosshairY, TOLERANCE_PX);
        if (hit < 0) {
            return null;
        }
        MarkScreenFrame.HudMarker marker = frame.markers().get(hit);
        return marker.guideWithArrowAndDistance()
                ? DistanceText.format(marker.distance())
                : null;
    }

    private CrosshairDistanceLayer() {
    }
}
