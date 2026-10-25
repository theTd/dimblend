package dimblend.spyglass.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.spyglass.structure.StructureOutline;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Vector2f;

/**
 * Draws a name + {@code ~N} label just above each shown outline: horizontally centered on the
 * projection of the union bounds' top-center, bottom edge {@link #LABEL_GAP} above the top of the
 * bounds' on-screen silhouette, so the label never covers the outline it names.
 *
 * <p>The layer sits directly <b>below</b> {@link VanillaGuiLayers#CAMERA_OVERLAYS}, the layer that
 * draws the spyglass scope ({@code Gui.java:208-217,1030-1046}). Its texture blit and the four black
 * fills are drawn after this layer and on top of it, so they mask labels exactly like they mask the
 * world: a label is visible only where the player can see the world through the lens, including
 * while the lens is still opening ({@code scopeScale} animation, {@code Gui.java:213-214}) and with
 * resource packs that change the scope texture. No overlay geometry is duplicated here.
 */
// RegisterGuiLayersEvent is an IModBusEvent: FML registers this listener on the mod bus by itself.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class SpyglassLabelLayer {
    static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "structure_labels");

    private static final int PADDING_X = 3;
    private static final int PADDING_Y = 2;
    /** Free GUI pixels between the outline's on-screen top and the label's bottom edge. */
    private static final float LABEL_GAP = 2.0F;
    private static final int NAME_COLOR = 0xFFFFFFFF;
    /** Vanilla {@code ChatFormatting.YELLOW}; separates the distance from the name at a glance. */
    private static final int DISTANCE_COLOR = 0xFFFFFF55;
    /**
     * Plate opacity by default; the vanilla "Text Background Opacity" accessibility setting overrides
     * it like for name tags ({@code Options#getBackgroundColor(float)}). 60% black keeps white text
     * above a 5:1 contrast ratio even against a white noon sky or white fog.
     */
    private static final float BACKGROUND_OPACITY = 0.6F;

    private static final WorldToGuiProjection PROJECTION = new WorldToGuiProjection();

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CAMERA_OVERLAYS, LAYER_ID, SpyglassLabelLayer::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        ShownOutlineFrame frame = ShownOutlineFrame.take();
        Minecraft minecraft = Minecraft.getInstance();
        if (frame == null || !SpyglassScopeGate.isScoping(minecraft)) {
            return;
        }

        Window window = minecraft.getWindow();
        double guiScale = window.getGuiScale();
        double guiWidth = window.getWidth() / guiScale;
        double guiHeight = window.getHeight() / guiScale;
        Font font = minecraft.font;
        Language language = Language.getInstance();
        // The same live position SpyglassClientState#currentStructures filtered with this frame.
        double playerX = minecraft.player.getX();
        double playerZ = minecraft.player.getZ();
        int height = PADDING_Y * 2 + font.lineHeight * 2 - 1;

        Vector2f anchor = new Vector2f();
        List<LabelPlacement.Candidate<StructureLabel>> candidates = new ArrayList<>(frame.structures().size());
        for (StructureOutline structure : frame.structures()) {
            OutlineBox bounds = structure.bounds();
            // Inclusive max coordinates: the box's far faces sit at max + 1.
            double minRelX = bounds.minX() - frame.cameraX();
            double minRelY = bounds.minY() - frame.cameraY();
            double minRelZ = bounds.minZ() - frame.cameraZ();
            double maxRelX = bounds.maxX() + 1.0 - frame.cameraX();
            double maxRelY = bounds.maxY() + 1.0 - frame.cameraY();
            double maxRelZ = bounds.maxZ() + 1.0 - frame.cameraZ();
            // Anchor: the top-center of the bounds, so the label stays horizontally centered on the
            // structure and is only drawn while that point is in front of the camera and on screen.
            if (!PROJECTION.project(
                    frame.worldToClip(),
                    bounds.centerX() - frame.cameraX(),
                    maxRelY,
                    bounds.centerZ() - frame.cameraZ(),
                    guiWidth,
                    guiHeight,
                    anchor)) {
                continue;
            }
            // Sit above the whole silhouette, not just above the top-center: seen from below, the near
            // top edge projects higher than the top-center and the label would cover it.
            float silhouetteTop = PROJECTION.silhouetteTop(
                    frame.worldToClip(), minRelX, minRelY, minRelZ, maxRelX, maxRelY, maxRelZ, guiHeight);
            float bottom = (Float.isNaN(silhouetteTop) ? anchor.y : Math.min(anchor.y, silhouetteTop))
                    - LABEL_GAP;
            double distance = SpyglassDistance.horizontal(playerX, playerZ, structure);
            StructureLabel label = StructureLabel.of(
                    font,
                    StructureLabelNames.resolve(structure.id(), language::has, language::getOrDefault),
                    SpyglassDistance.format(distance));
            candidates.add(LabelPlacement.Candidate.above(
                    label, distance, anchor.x, bottom, label.textWidth() + PADDING_X * 2, height));
        }
        if (candidates.isEmpty()) {
            return;
        }

        int background = minecraft.options.getBackgroundColor(BACKGROUND_OPACITY);
        for (LabelPlacement.Candidate<StructureLabel> placed : LabelPlacement.place(candidates)) {
            draw(graphics, font, placed, background, guiScale);
        }
    }

    private static void draw(
            GuiGraphics graphics,
            Font font,
            LabelPlacement.Candidate<StructureLabel> placed,
            int background,
            double guiScale) {
        StructureLabel label = placed.label();
        int width = placed.width();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        // Snap to whole framebuffer pixels: text stays crisp and still moves smoothly while panning.
        pose.translate(snapToPixel(placed.left(), guiScale), snapToPixel(placed.top(), guiScale), 0.0F);
        graphics.fill(0, 0, width, placed.height(), background);
        graphics.drawString(font, label.name(), (width - label.nameWidth()) / 2, PADDING_Y, NAME_COLOR, true);
        graphics.drawString(font, label.distance(), (width - label.distanceWidth()) / 2,
                PADDING_Y + font.lineHeight, DISTANCE_COLOR, true);
        pose.popPose();
    }

    private static float snapToPixel(float guiCoordinate, double guiScale) {
        return (float) (Math.round(guiCoordinate * guiScale) / guiScale);
    }

    /** Name on the first line, {@code ~N} centered under it. */
    private record StructureLabel(String name, int nameWidth, String distance, int distanceWidth) {
        static StructureLabel of(Font font, String name, String distance) {
            return new StructureLabel(name, font.width(name), distance, font.width(distance));
        }

        int textWidth() {
            return Math.max(this.nameWidth, this.distanceWidth);
        }
    }

    private SpyglassLabelLayer() {
    }
}
