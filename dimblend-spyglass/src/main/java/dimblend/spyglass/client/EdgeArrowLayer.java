package dimblend.spyglass.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dimblend.spyglass.DimBlendSpyglass;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Vector3f;

/**
 * One edge arrow pointing at the nearest {@code ?} that is not inside the lens. {@code !} never
 * participates, and no {@code ?} means no arrow.
 *
 * <p>Reads the same {@link MarkScreenFrame} the marker layer published this frame, so the target and
 * the glyphs are projected identically. The arrow is drawn above the scope overlay, on the black
 * frame, as a small filled triangle rotated to the target direction.
 */
// RegisterGuiLayersEvent is an IModBusEvent: FML registers this listener on the mod bus by itself.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class EdgeArrowLayer {
    static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "edge_arrow");

    private static final double INSET = 24.0;
    private static final double CROSSHAIR_RADIUS = 12.0;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CAMERA_OVERLAYS, LAYER_ID, EdgeArrowLayer::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!SpyglassScopeGate.isScoping(minecraft)) {
            return;
        }
        MarkScreenFrame frame = MarkScreenFrame.current();
        MarkScreenFrame.Mark target = nearestUnknownOutsideTheLens(frame);
        if (target == null) {
            return;
        }
        EdgeArrowGeometry.Arrow arrow = EdgeArrowGeometry.compute(
                new Vector3f(target.viewX(), target.viewY(), target.viewZ()),
                frame.guiWidth(),
                frame.guiHeight(),
                INSET,
                CROSSHAIR_RADIUS);
        if (arrow == null) {
            return;
        }
        draw(graphics, arrow);
    }

    /** Nearest predicted mark whose center is outside the inset lens rectangle. */
    private static MarkScreenFrame.Mark nearestUnknownOutsideTheLens(MarkScreenFrame frame) {
        MarkScreenFrame.Mark best = null;
        for (MarkScreenFrame.Mark mark : frame.marks()) {
            if (!mark.predicted() || mark.inLens()) {
                continue;
            }
            if (best == null || mark.distance() < best.distance()) {
                best = mark;
            }
        }
        return best;
    }

    private static void draw(GuiGraphics graphics, EdgeArrowGeometry.Arrow arrow) {
        float degrees = (float) Math.toDegrees(Math.atan2(arrow.dirY(), arrow.dirX()));
        PoseStack pose = graphics.pose();
        pose.pushPose();
        // The local shape spans x -7..4, so pull it back to put the tip on the intersection.
        pose.translate(arrow.x() - arrow.dirX() * 4.0F, arrow.y() - arrow.dirY() * 4.0F, 0.0F);
        pose.mulPose(Axis.ZP.rotationDegrees(degrees));
        triangle(graphics, 1, 1, BLACK);
        triangle(graphics, 0, 0, WHITE);
        pose.popPose();
    }

    /** A filled triangle pointing +X: a short shaft plus six narrowing vertical slices. */
    private static void triangle(GuiGraphics graphics, int offsetX, int offsetY, int color) {
        graphics.fill(-7 + offsetX, -1 + offsetY, -2 + offsetX, 2 + offsetY, color);
        for (int i = 0; i < 6; i++) {
            int halfHeight = 5 - i;
            graphics.fill(
                    -2 + i + offsetX,
                    -halfHeight + offsetY,
                    -1 + i + offsetX,
                    halfHeight + 1 + offsetY,
                    color);
        }
    }

    private EdgeArrowLayer() {
    }
}
