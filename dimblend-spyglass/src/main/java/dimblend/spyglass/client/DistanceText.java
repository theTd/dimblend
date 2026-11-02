package dimblend.spyglass.client;

import dimblend.spyglass.structure.SpyglassDistance;

/**
 * The top-of-screen distance text: {@code ~Nm}, rounded to 10 blocks like the old label's second
 * line. The unit is appended so the line reads as a distance on its own.
 */
final class DistanceText {
    static String format(double distance) {
        return "~" + SpyglassDistance.roundToTen(distance) + "m";
    }

    private DistanceText() {
    }
}
