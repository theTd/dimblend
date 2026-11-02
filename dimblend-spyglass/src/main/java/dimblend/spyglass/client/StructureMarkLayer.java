package dimblend.spyglass.client;

import com.mojang.blaze3d.platform.Window;
import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.structure.OutlineBox;
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
 * from the seed (no blocks exist yet), {@code !} for a structure read from a saved chunk.
 *
 * <p>The layer sits directly <b>below</b> {@link VanillaGuiLayers#CAMERA_OVERLAYS}, the layer that
 * draws the spyglass scope, so the vanilla lens texture and its four black fills mask these marks
 * exactly like they mask the world: a mark is visible only through the lens, including while the
 * lens is opening and with resource packs that change the scope texture. No mask geometry is
 * duplicated here.
 *
 * <p>Anchor: the union bounds' top-center, {@code (centerX, maxY + 1, centerZ)}. A 16×16 GUI-pixel
 * box is centered on the projected point; overlapping boxes are skipped nearest-first, so the
 * priority stays stable while panning. The projected result is published as {@link MarkScreenFrame}
 * for the HUD layers of the same frame.
 */
// RegisterGuiLayersEvent is an IModBusEvent: FML registers this listener on the mod bus by itself.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class StructureMarkLayer {
    static final ResourceLocation LAYER_ID =
            ResourceLocation.fromNamespaceAndPath(DimBlendSpyglass.MODID, "structure_marks");

    /** Fixed box size, in GUI pixels: the glyph is centered inside it. */
    static final int MARK_SIZE = 16;
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

        List<LabelPlacement.Candidate<MarkFrame.Mark>> candidates = new ArrayList<>(frame.marks().size());
        List<Projected> projected = new ArrayList<>(frame.marks().size());
        Vector2f anchor = new Vector2f();
        for (MarkFrame.Mark mark : frame.marks()) {
            OutlineBox bounds = mark.outline().bounds();
            double relX = bounds.centerX() - frame.cameraX();
            double relY = bounds.maxY() + 1.0 - frame.cameraY();
            double relZ = bounds.centerZ() - frame.cameraZ();

            Vector3f view = new Vector3f((float) relX, (float) relY, (float) relZ);
            frame.modelView().transformPosition(view);

            if (!PROJECTION.project(
                    frame.worldToClip(), relX, relY, relZ, guiWidth, guiHeight, anchor)) {
                // Behind the camera or off screen: no glyph, but the arrow layer still needs the
                // direction, so the mark stays in the frame with NaN screen coordinates.
                projected.add(new Projected(mark, Float.NaN, Float.NaN, false, view.x, view.y, view.z));
                continue;
            }
            boolean inLens = anchor.x >= LENS_INSET && anchor.x <= guiWidth - LENS_INSET
                    && anchor.y >= LENS_INSET && anchor.y <= guiHeight - LENS_INSET;
            candidates.add(LabelPlacement.Candidate.above(
                    mark,
                    mark.centerDistance(),
                    anchor.x,
                    anchor.y + MARK_SIZE * 0.5F,
                    MARK_SIZE,
                    MARK_SIZE));
            projected.add(new Projected(mark, anchor.x, anchor.y, inLens, view.x, view.y, view.z));
        }

        List<LabelPlacement.Candidate<MarkFrame.Mark>> placed = LabelPlacement.place(candidates);
        Set<MarkFrame.Mark> drawn = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LabelPlacement.Candidate<MarkFrame.Mark> candidate : placed) {
            drawn.add(candidate.label());
            String glyph = MarkerGlyph.of(candidate.label().predicted());
            int x = Math.round(candidate.left() + (MARK_SIZE - font.width(glyph)) * 0.5F);
            int y = Math.round(candidate.top() + (MARK_SIZE - font.lineHeight) * 0.5F);
            graphics.drawString(font, glyph, x, y, COLOR, true);
        }

        List<MarkScreenFrame.Mark> marks = new ArrayList<>(projected.size());
        for (Projected mark : projected) {
            marks.add(new MarkScreenFrame.Mark(
                    mark.x(),
                    mark.y(),
                    mark.mark().predicted(),
                    mark.mark().centerDistance(),
                    drawn.contains(mark.mark()),
                    mark.inLens(),
                    mark.viewX(),
                    mark.viewY(),
                    mark.viewZ()));
        }
        MarkScreenFrame.publish(new MarkScreenFrame(marks, guiWidth, guiHeight));
    }

    private record Projected(
            MarkFrame.Mark mark, float x, float y, boolean inLens, float viewX, float viewY, float viewZ) {
    }

    private StructureMarkLayer() {
    }
}
