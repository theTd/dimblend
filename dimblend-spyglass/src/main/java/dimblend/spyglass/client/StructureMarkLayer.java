package dimblend.spyglass.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.spyglass.DimBlendSpyglass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * Draws one {@code ?} or {@code !} above every structure in range: {@code ?} for an outline replayed
 * from the seed (no blocks exist yet), {@code !} for a structure read from a saved chunk. The
 * selected structure is drawn in brackets, {@code [?]} or {@code [!]}.
 *
 * <p>The layer sits directly <b>below</b> {@link VanillaGuiLayers#CAMERA_OVERLAYS}, the layer that
 * draws the spyglass scope, so the vanilla lens texture and its four black fills mask these marks
 * exactly like they mask the world: a mark is visible only through the lens, including while the
 * lens is opening and with resource packs that change the scope texture. No mask geometry is
 * duplicated here.
 *
 * <p>Anchor: the union bounds' top-center, {@code (centerX, maxY + 1, centerZ)}. A box is centered
 * on the projected point and the glyph is drawn at twice the font size inside it; the selection's
 * brackets widen the box (and therefore its click target). Overlapping boxes are skipped
 * nearest-first, except that the selected mark is placed before all others, so it is never hidden
 * while it is selected.
 *
 * <p>The layer also refreshes the saved selection from the live outline (so a {@code ?} selected
 * outline that becomes {@code !} keeps its selection under the same key) and publishes the
 * projected result as {@link MarkScreenFrame} for the HUD layers and the click handler of the same
 * frame.
 */
// RegisterGuiLayersEvent is an IModBusEvent: FML registers this listener on the mod bus by itself.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class StructureMarkLayer {
    static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "structure_marks");

    /** Marks whose center is inside this inset rectangle count as "in the lens" for the arrow layer. */
    static final double LENS_INSET = 24.0;
    private static final int COLOR = 0xFFFFFFFF;

    private static final WorldToGuiProjection PROJECTION = new WorldToGuiProjection();

    @SubscribeEvent
    public static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerBelow(VanillaGuiLayers.CAMERA_OVERLAYS, LAYER_ID, StructureMarkLayer::render);
    }

    private static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        MarkFrame frame = MarkFrame.take();
        Minecraft minecraft = Minecraft.getInstance();
        if (frame == null || !SpyglassScopeGate.isScoping(minecraft)) {
            // Never let the HUD layers read the previous frame's marks.
            MarkScreenFrame.publish(MarkScreenFrame.EMPTY);
            return;
        }

        Window window = minecraft.getWindow();
        double guiScale = window.getGuiScale();
        double guiWidth = window.getWidth() / guiScale;
        double guiHeight = window.getHeight() / guiScale;
        Font font = minecraft.font;
        int boxHeight = MarkLayout.boxHeight();

        List<LabelPlacement.Candidate<MarkFrame.Mark>> candidates = new ArrayList<>(frame.marks().size());
        List<Projected> projected = new ArrayList<>(frame.marks().size());
        Vector2f anchor = new Vector2f();
        for (MarkFrame.Mark mark : frame.marks()) {
            StructureSelection.Entry entry = mark.entry();
            // Keep the saved identity and anchor current: a structure that turns from predicted
            // into saved (? -> !) keeps its key, so the selection survives that transition.
            StructureSelection.observe(
                    entry.key(), entry.predicted(), entry.centerX(), entry.anchorY(), entry.centerZ());
            boolean selected = StructureSelection.isSelected(entry.key());
            String glyph = MarkerGlyph.of(entry.predicted(), selected);
            int boxWidth = MarkLayout.boxWidth(MarkLayout.scaledGlyphWidth(font.width(glyph)));

            double relX = entry.centerX() - frame.cameraX();
            double relY = entry.anchorY() - frame.cameraY();
            double relZ = entry.centerZ() - frame.cameraZ();

            Vector3f view = new Vector3f((float) relX, (float) relY, (float) relZ);
            frame.modelView().transformPosition(view);

            if (!PROJECTION.project(
                    frame.worldToClip(), relX, relY, relZ, guiWidth, guiHeight, anchor)) {
                // Behind the camera or off screen: no glyph, but the arrow layer still needs the
                // direction, so the mark stays in the frame with NaN screen coordinates.
                projected.add(new Projected(
                        mark, Float.NaN, Float.NaN, false, boxWidth, view.x, view.y, view.z));
                continue;
            }
            boolean inLens = anchor.x >= LENS_INSET && anchor.x <= guiWidth - LENS_INSET
                    && anchor.y >= LENS_INSET && anchor.y <= guiHeight - LENS_INSET;
            candidates.add(LabelPlacement.Candidate.above(
                    mark,
                    mark.centerDistance(),
                    anchor.x,
                    anchor.y + boxHeight * 0.5F,
                    boxWidth,
                    boxHeight,
                    selected));
            projected.add(new Projected(
                    mark, anchor.x, anchor.y, inLens, boxWidth, view.x, view.y, view.z));
        }

        List<LabelPlacement.Candidate<MarkFrame.Mark>> placed = LabelPlacement.place(candidates);
        Set<MarkFrame.Mark> drawn = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LabelPlacement.Candidate<MarkFrame.Mark> candidate : placed) {
            MarkFrame.Mark mark = candidate.label();
            drawn.add(mark);
            boolean selected = StructureSelection.isSelected(mark.entry().key());
            String glyph = MarkerGlyph.of(mark.entry().predicted(), selected);
            int scaledWidth = MarkLayout.scaledGlyphWidth(font.width(glyph));
            // Center the scaled glyph inside the box, then draw it through a 2x pose so the font
            // itself is untouched. Pose transforms scale the translation too, hence the /2.
            int x = Math.round(candidate.left() + (candidate.width() - scaledWidth) * 0.5F);
            int y = Math.round(
                    candidate.top() + (candidate.height() - font.lineHeight * MarkLayout.GLYPH_SCALE) * 0.5F);
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.scale(MarkLayout.GLYPH_SCALE, MarkLayout.GLYPH_SCALE, 1.0F);
            pose.translate(x / MarkLayout.GLYPH_SCALE, y / MarkLayout.GLYPH_SCALE, 0.0F);
            graphics.drawString(font, glyph, 0, 0, COLOR, true);
            pose.popPose();
        }

        List<MarkScreenFrame.Mark> marks = new ArrayList<>(projected.size());
        for (Projected mark : projected) {
            marks.add(new MarkScreenFrame.Mark(
                    mark.mark().entry(),
                    mark.x(),
                    mark.y(),
                    mark.mark().centerDistance(),
                    drawn.contains(mark.mark()),
                    mark.inLens(),
                    mark.boxWidth() * 0.5F,
                    mark.viewX(),
                    mark.viewY(),
                    mark.viewZ()));
        }
        MarkScreenFrame.publish(new MarkScreenFrame(marks, guiWidth, guiHeight, System.nanoTime()));
    }

    private record Projected(
            MarkFrame.Mark mark,
            float x,
            float y,
            boolean inLens,
            int boxWidth,
            float viewX,
            float viewY,
            float viewZ) {
    }

    private StructureMarkLayer() {
    }
}
