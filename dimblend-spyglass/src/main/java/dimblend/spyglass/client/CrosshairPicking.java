package dimblend.spyglass.client;

/**
 * Picks the mark under the crosshair.
 *
 * <p>A mark is a box in GUI pixels: {@link #HALF_BOX} pixels half height, and a per-mark half width
 * because the selected {@code [?]}/{@code [!]} is wider than a single glyph. The box plus a
 * tolerance is the hit area. Multiple hits go to the box whose center is nearest the screen
 * center; equal distances keep the earlier input (the arrays are in the mark frame's order, so the
 * result is stable).
 */
final class CrosshairPicking {
    /** Half the fixed mark height (the box is at least 32x32 GUI pixels). */
    static final double HALF_BOX = 16.0;

    /**
     * @param halfWidth  half width of each mark's drawn box, at least {@link #HALF_BOX} for the
     *                   selected bracketed glyph
     * @return the index of the hit mark, or {@code -1}
     */
    static int hit(
            double[] screenX,
            double[] screenY,
            double[] halfWidth,
            int count,
            double crosshairX,
            double crosshairY,
            double tolerancePx) {
        if (count < 0
                || count > screenX.length
                || count > screenY.length
                || count > halfWidth.length) {
            throw new IllegalArgumentException("Inconsistent mark array lengths for count " + count);
        }
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            double dx = Math.abs(screenX[i] - crosshairX);
            double dy = Math.abs(screenY[i] - crosshairY);
            if (dx > halfWidth[i] + tolerancePx || dy > HALF_BOX + tolerancePx) {
                continue;
            }
            double distance = Math.hypot(dx, dy);
            if (distance < bestDistance) {
                best = i;
                bestDistance = distance;
            }
        }
        return best;
    }

    private CrosshairPicking() {
    }
}
