package dimblend.spyglass.client;

import com.mojang.blaze3d.platform.Window;
import dimblend.spyglass.DimBlendSpyglass;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Left-click selection of a structure mark while scoping.
 *
 * <p>The raw mouse event is used instead of {@code InteractionKeyMappingTriggered}: while the
 * spyglass is being used, vanilla eats the attack key mapping, so a key-mapping based listener
 * would never see the press. {@link InputEvent.MouseButton.Pre} fires before vanilla sets
 * {@code isLeftPressed} and before the key mapping is clicked ({@code MouseHandler.java:87,119-135}
 * in the NeoForge-patched client), so cancelling it also stops the punch.
 *
 * <p>Only a left <b>press</b> can select: a release clears the hold latch and is never cancelled,
 * and a second press without a release in between is ignored. The hit test uses the latest marker
 * frame ({@link MarkScreenFrame}) and requires a drawn mark under the crosshair in a frame that is
 * still fresh ({@link MarkScreenFrame#isFresh}); a blank area, a hidden mark or a stale frame does
 * not select. Clicking the selected structure again clears the selection; clicking another one
 * replaces it.
 *
 * <p>Client main thread only (the event is fired from the window's mouse callback).
 */
// InputEvent is a game-bus event; the default subscriber bus is the game bus.
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class StructureMarkInput {
    /** A frame older than this is no longer what the player is looking at. */
    static final long MAX_FRAME_AGE_NANOS = 250_000_000L;
    /** Same box-plus-tolerance rule as the crosshair distance HUD. */
    static final double HIT_TOLERANCE_PX = 4.0;

    /** True while the left button is physically down, so a held button cannot toggle repeatedly. */
    private static boolean leftDown;

    @SubscribeEvent
    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getButton() != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return;
        }
        if (event.getAction() == GLFW.GLFW_RELEASE) {
            leftDown = false;
            return;
        }
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        boolean alreadyDown = leftDown;
        leftDown = true;

        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        double guiScale = window.getGuiScale();
        double guiWidth = window.getWidth() / guiScale;
        double guiHeight = window.getHeight() / guiScale;
        MarkScreenFrame frame = MarkScreenFrame.current();
        long now = System.nanoTime();
        boolean fresh = frame.isFresh(now, guiWidth, guiHeight, MAX_FRAME_AGE_NANOS);
        int hit = fresh ? frame.hit(guiWidth * 0.5, guiHeight * 0.5, HIT_TOLERANCE_PX) : -1;
        boolean selects = MarkClickDecision.selects(
                true,
                true,
                alreadyDown,
                minecraft.screen != null,
                minecraft.getOverlay() != null,
                minecraft.mouseHandler.isMouseGrabbed(),
                SpyglassScopeGate.isScoping(minecraft),
                fresh,
                hit);
        if (!selects) {
            return;
        }
        StructureSelection.toggle(frame.marks().get(hit).entry());
        // Swallow the press: vanilla would otherwise turn it into an attack while scoping.
        event.setCanceled(true);
    }

    /** Forgets a held button, e.g. when the client drops its level mid-press. */
    static void reset() {
        leftDown = false;
    }

    private StructureMarkInput() {
    }
}
