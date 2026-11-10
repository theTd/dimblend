package dimblend.spyglass.client;

/**
 * Pure decision for the left-button press that selects a structure mark.
 *
 * <p>Every condition is explicit so the rules can be tested without a game: the left button only,
 * on press only (a release must never toggle and must never be swallowed), only the first press of
 * a hold (no repeat while the button stays down), only while the player is really scoping with no
 * screen or loading overlay and the mouse grabbed, and only on a fresh frame with an actually drawn
 * mark under the crosshair.
 *
 * <p>The event is cancelled only when this returns {@code true}. That matters: the raw mouse event
 * is handled before vanilla turns the press into an attack, so swallowing it keeps the spyglass
 * from punching whatever is in front of the player.
 */
final class MarkClickDecision {
    static boolean selects(
            boolean leftButton,
            boolean press,
            boolean alreadyDown,
            boolean screenOpen,
            boolean overlayOpen,
            boolean mouseGrabbed,
            boolean scoping,
            boolean freshFrame,
            int hitIndex) {
        return leftButton
                && press
                && !alreadyDown
                && !screenOpen
                && !overlayOpen
                && mouseGrabbed
                && scoping
                && freshFrame
                && hitIndex >= 0;
    }

    private MarkClickDecision() {
    }
}
