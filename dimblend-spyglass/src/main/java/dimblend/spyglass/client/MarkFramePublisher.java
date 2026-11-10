package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
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
 * <p>Every level pass replaces the previous frame's hand-off, also when the player is not scoping, so
 * a HUD layer can never read stale matrices. While the player <b>is</b> scoping with a usable terrain
 * fog, the saved selection is checked against the live position first: it is dropped only when its
 * saved union-bounds centre truly left the {@code [128, 1024]} ring. Turning, zooming, an overlap
 * that hides the mark or an empty snapshot never reaches that check. Lowering the spyglass returns
 * before it, which is what keeps the selection.
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
        if (!SpyglassScopeGate.isScoping(minecraft)) {
            return;
        }
        boolean revealEnabled = SpyglassRevealState.isEnabled();
        StructureSelection.pruneOutOfRange(
                minecraft.player.getX(), minecraft.player.getZ(), revealEnabled);

        Vec3 eye = event.getCamera().getPosition();
        Matrix4f worldToClip =
                new Matrix4f(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        MarkFrame frame = MarkFrame.of(
                SpyglassClientState.currentStructures(),
                worldToClip,
                new Matrix4f(event.getModelViewMatrix()),
                eye.x,
                eye.y,
                eye.z);
        if (revealEnabled) {
            // A selection outlives the snapshot entry it came from: draw it from its saved anchor
            // until it actually leaves the ring.
            frame = frame.withSelection(StructureSelection.current());
        }
        MarkFrame.publish(frame);
    }

    private MarkFramePublisher() {
    }
}
