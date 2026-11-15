package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.api.HudWorldMarkers;
import dimblend.spyglass.structure.StructureOutline;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * Hands this frame's structures and camera matrices from the level pass to the HUD mark layer.
 *
 * <p>The spyglass renders no structure geometry: the {@code ?}/{@code !} marks, the distance text
 * and the edge arrow are the whole feature. They still need the frame's camera position,
 * {@code projection × modelView} and {@code modelView}, and those are only available while the level
 * is rendered. This subscriber publishes them at {@code AFTER_PARTICLES}, after every world pass, and
 * {@link StructureMarkLayer} consumes the hand-off in the GUI pass of the same frame.
 *
 * <p>Every level pass replaces the previous frame's hand-off, so a HUD layer can never read stale
 * matrices. While the player <b>is</b> scoping with a usable terrain fog, the saved selection is
 * checked against the live position first: it is dropped only when its saved union-bounds centre
 * truly left the {@code [64, 1024]} ring. Turning, zooming, an overlap that hides the mark or an
 * empty snapshot never reaches that check.
 *
 * <p>With the spyglass lowered the hand-off carries <b>only the saved selection</b>
 * ({@link MarkFrame#selectedOnly}): the player's marked structure stays on the normal HUD, and no
 * unmarked structure ever appears there. The selection is still pruned against the same ring, on
 * distance alone, so walking closer than 64 blocks or farther than 1024 blocks drops the mark.
 *
 * <p>External HUD markers from {@link HudWorldMarkers} are attached to every frame, scoping or not,
 * through {@link MarkFrame#attach}. They never pass through the structure selection or its display
 * ring; a marker-only frame is published when no structure mark survived.
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class MarkFramePublisher {
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        MarkFrame.publish(null);
        Minecraft minecraft = Minecraft.getInstance();
        boolean scoping = SpyglassScopeGate.isScoping(minecraft);
        if (minecraft.level == null || minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        boolean revealEnabled = SpyglassRevealState.isEnabled();
        // Scoped, the reveal fog decides whether a distance means anything; unscoped, only the ring
        // can drop a mark the player already picked, so the distance check always runs.
        StructureSelection.pruneOutOfRange(
                minecraft.player.getX(), minecraft.player.getZ(), scoping ? revealEnabled : true);
        List<StructureOutline> structures =
                scoping ? SpyglassClientState.currentStructures() : List.of();

        Vec3 eye = event.getCamera().getPosition();
        Matrix4f worldToClip =
                new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        Matrix4f modelView = new Matrix4f(event.getModelViewMatrix());
        MarkFrame frame = frameToPublish(
                scoping,
                revealEnabled,
                StructureSelection.current(),
                structures,
                worldToClip,
                modelView,
                eye.x,
                eye.y,
                eye.z);
        MarkFrame.publish(MarkFrame.attach(
                frame,
                HudWorldMarkers.current(),
                worldToClip,
                modelView,
                eye.x,
                eye.y,
                eye.z));
    }

    /**
     * Which marks the frame carries. Scoped: every in-range structure, plus the saved selection
     * when the snapshot lost it. Unscoped: only the saved selection, or {@code null} when nothing
     * is selected. Pure so the filtering is testable without a client.
     */
    static @Nullable MarkFrame frameToPublish(
            boolean scoping,
            boolean revealEnabled,
            @Nullable StructureSelection.Entry selected,
            List<StructureOutline> structures,
            Matrix4f worldToClip,
            Matrix4f modelView,
            double cameraX,
            double cameraY,
            double cameraZ) {
        if (!scoping) {
            return selected == null
                    ? null
                    : MarkFrame.selectedOnly(
                            selected, worldToClip, modelView, cameraX, cameraY, cameraZ);
        }
        MarkFrame frame = MarkFrame.of(structures, worldToClip, modelView, cameraX, cameraY, cameraZ);
        return revealEnabled ? frame.withSelection(selected) : frame;
    }

    private MarkFramePublisher() {
    }
}
