package dimblend.spyglass.client;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.vertex.PoseStack;
import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.api.HudWorldMarkers;
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
 * <p>External HUD markers from {@link HudWorldMarkers} go through the same projection and box
 * rules, always before ordinary structure marks. They are not structure marks: the published frame
 * keeps them in a separate list, so they can never be selected or pruned by the display ring.
 *
 * <p>With the spyglass lowered the publisher hands over <b>only</b> the selected mark, and this
 * layer draws it like any other HUD element: the marks are no longer gated on scoping, only on
 * {@code hideGui} and a non-empty frame. Vanilla HUD layers still run behind an open screen there,
 * so this layer does the same and lets the screen cover the glyph; the scope-only layers
 * ({@link EdgeArrowLayer}, {@link CrosshairDistanceLayer}) keep their own scoping gate except for
 * markers that ask for guidance.
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
        if (frame == null
                || minecraft.options.hideGui
                || (frame.marks().isEmpty() && frame.markers().isEmpty())) {
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

        List<LabelPlacement.Candidate<Label>> candidates =
                new ArrayList<>(frame.marks().size() + frame.markers().size());
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
            Label label = new Label(mark, null);
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
                        label, Float.NaN, Float.NaN, false, boxWidth, view.x, view.y, view.z));
                continue;
            }
            boolean inLens = anchor.x >= LENS_INSET && anchor.x <= guiWidth - LENS_INSET
                    && anchor.y >= LENS_INSET && anchor.y <= guiHeight - LENS_INSET;
            candidates.add(LabelPlacement.Candidate.above(
                    label,
                    mark.centerDistance(),
                    anchor.x,
                    anchor.y + boxHeight * 0.5F,
                    boxWidth,
                    boxHeight,
                    selected));
            projected.add(new Projected(
                    label, anchor.x, anchor.y, inLens, boxWidth, view.x, view.y, view.z));
        }

        List<ProjectedMarker> projectedMarkers = new ArrayList<>(frame.markers().size());
        for (HudWorldMarkers.Marker marker : frame.markers()) {
            String glyph = marker.glyph();
            Label label = new Label(null, marker);
            int boxWidth = MarkLayout.boxWidth(MarkLayout.scaledGlyphWidth(font.width(glyph)));

            double relX = marker.x() - frame.cameraX();
            double relY = marker.y() - frame.cameraY();
            double relZ = marker.z() - frame.cameraZ();
            double distance = Math.sqrt(relX * relX + relY * relY + relZ * relZ);
            Vector3f view = new Vector3f((float) relX, (float) relY, (float) relZ);
            frame.modelView().transformPosition(view);

            if (!PROJECTION.project(
                    frame.worldToClip(), relX, relY, relZ, guiWidth, guiHeight, anchor)) {
                projectedMarkers.add(new ProjectedMarker(
                        label, glyph, Float.NaN, Float.NaN, false, boxWidth, distance,
                        view.x, view.y, view.z));
                continue;
            }
            boolean inLens = anchor.x >= LENS_INSET && anchor.x <= guiWidth - LENS_INSET
                    && anchor.y >= LENS_INSET && anchor.y <= guiHeight - LENS_INSET;
            // A caller-requested HUD marker is a navigation aid: place it before ordinary marks.
            candidates.add(LabelPlacement.Candidate.above(
                    label,
                    distance,
                    anchor.x,
                    anchor.y + boxHeight * 0.5F,
                    boxWidth,
                    boxHeight,
                    true));
            projectedMarkers.add(new ProjectedMarker(
                    label, glyph, anchor.x, anchor.y, inLens, boxWidth, distance,
                    view.x, view.y, view.z));
        }

        List<LabelPlacement.Candidate<Label>> placed = LabelPlacement.place(candidates);
        Set<MarkFrame.Mark> drawnMarks = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<HudWorldMarkers.Marker> drawnMarkers =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (LabelPlacement.Candidate<Label> candidate : placed) {
            Label label = candidate.label();
            if (label.structure() != null) {
                drawnMarks.add(label.structure());
            } else {
                drawnMarkers.add(label.marker());
            }
            String glyph = glyph(label);
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
                    mark.label().structure().entry(),
                    mark.x(),
                    mark.y(),
                    mark.label().structure().centerDistance(),
                    drawnMarks.contains(mark.label().structure()),
                    mark.inLens(),
                    mark.boxWidth() * 0.5F,
                    mark.viewX(),
                    mark.viewY(),
                    mark.viewZ()));
        }
        List<MarkScreenFrame.HudMarker> hudMarkers = new ArrayList<>(projectedMarkers.size());
        for (ProjectedMarker marker : projectedMarkers) {
            hudMarkers.add(new MarkScreenFrame.HudMarker(
                    marker.label().marker().id(),
                    marker.glyph(),
                    marker.x(),
                    marker.y(),
                    marker.distance(),
                    drawnMarkers.contains(marker.label().marker()),
                    marker.inLens(),
                    marker.boxWidth() * 0.5F,
                    marker.viewX(),
                    marker.viewY(),
                    marker.viewZ(),
                    marker.label().marker().guideWithArrowAndDistance()));
        }
        MarkScreenFrame.publish(
                new MarkScreenFrame(marks, guiWidth, guiHeight, System.nanoTime(), hudMarkers));
    }

    /** The text drawn for a placed label: an external glyph or a structure's {@code ?}/{@code !}. */
    private static String glyph(Label label) {
        if (label.marker() != null) {
            return label.marker().glyph();
        }
        StructureSelection.Entry entry = label.structure().entry();
        return MarkerGlyph.of(entry.predicted(), StructureSelection.isSelected(entry.key()));
    }

    /** One projected label: exactly one of the two sources is non-null. */
    private record Label(MarkFrame.Mark structure, HudWorldMarkers.Marker marker) {
        Label {
            if ((structure == null) == (marker == null)) {
                throw new IllegalArgumentException("A label carries exactly one mark");
            }
        }
    }

    private record Projected(
            Label label,
            float x,
            float y,
            boolean inLens,
            int boxWidth,
            float viewX,
            float viewY,
            float viewZ) {
    }

    private record ProjectedMarker(
            Label label,
            String glyph,
            float x,
            float y,
            boolean inLens,
            int boxWidth,
            double distance,
            float viewX,
            float viewY,
            float viewZ) {
    }

    private StructureMarkLayer() {
    }
}
