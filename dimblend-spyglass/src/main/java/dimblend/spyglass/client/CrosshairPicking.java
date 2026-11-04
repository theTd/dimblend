package dimblend.spyglass.client;

/**
 * Picks the mark under the crosshair: the mark box is 32×32 GUI pixels (half-size 16), plus a
 * tolerance. Multiple hits go to the box whose center is nearest the screen center; equal distances
 * keep the earlier input (the arrays are in the mark frame's order, so the result is stable).
 */
final class CrosshairPicking {
    static final double HALF_BOX = 16.0;

    /**
     * @return the index of the hit mark, or {@code -1}
     */
    static int hit(
            double[] screenX,
            double[] screenY,
            int count,
            double crosshairX,
            double crosshairY,
            double tolerancePx) {
        double limit = HALF_BOX + tolerancePx;
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double dx = screenX[i] - crosshairX;
            double dy = screenY[i] - crosshairY;
            double distance = Math.hypot(dx, dy);
            if (distance <= limit && distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private CrosshairPicking() {
    }
}
