package dimblend.spyglass.client;

import dimblend.spyglass.structure.OutlineBox;

/**
 * Near and far planes of the real-shape pass ({@link SpyglassShapeRenderer}).
 *
 * <p>A 24-bit depth buffer with a perspective projection resolves roughly
 * {@code d² / (near · 2²⁴)} blocks at distance {@code d} (for {@code far ≫ near}). With the
 * frame's near plane of 0.05 that is about one block at 1000 blocks, so real meshes would z-fight.
 * The shapes are all far away, so the near plane is pushed out to half the distance of the nearest
 * drawn shape box: at near = 128 the resolution at 1000 blocks is well below a millimetre. Depth
 * precision barely depends on the far plane, so it simply sits past the farthest box corner.
 *
 * <p>Pure math, unit tested.
 */
final class ShapeDepthRange {
    /** Never closer than the frame's own near plane ({@code GameRenderer.PROJECTION_Z_NEAR}). */
    static final float MIN_NEAR = 0.05F;
    /** Upper clamp: beyond this the precision gain is irrelevant and float error starts to matter. */
    static final float MAX_NEAR = 128.0F;
    /** Fraction of the nearest box distance used as the near plane, leaving room for float error. */
    static final double NEAR_FRACTION = 0.5;
    /** Extra room behind the farthest box corner. */
    static final float FAR_MARGIN = 64.0F;

    private ShapeDepthRange() {
    }

    /** Near plane for the nearest drawn box at {@code nearestDistance} blocks (0 = camera inside). */
    static float nearPlane(double nearestDistance) {
        if (!(nearestDistance > 0.0) || Double.isInfinite(nearestDistance)) {
            return nearestDistance == Double.POSITIVE_INFINITY ? MAX_NEAR : MIN_NEAR;
        }
        double near = nearestDistance * NEAR_FRACTION;
        return (float) Math.max(MIN_NEAR, Math.min(MAX_NEAR, near));
    }

    /** Far plane beyond the farthest box corner, never below {@link SpyglassProjection#REQUIRED_FAR_PLANE}. */
    static float farPlane(double farthestDistance) {
        if (!(farthestDistance > 0.0) || Double.isInfinite(farthestDistance)) {
            return SpyglassProjection.REQUIRED_FAR_PLANE;
        }
        return (float) Math.max(SpyglassProjection.REQUIRED_FAR_PLANE, farthestDistance + FAR_MARGIN);
    }

    /**
     * Euclidean distance from the point to the nearest point of an inclusive block box (whose far
     * faces sit at {@code max + 1}); 0 when the point is inside.
     */
    static double nearestDistance(OutlineBox box, double x, double y, double z) {
        double dx = axisGap(x, box.minX(), box.maxX() + 1.0);
        double dy = axisGap(y, box.minY(), box.maxY() + 1.0);
        double dz = axisGap(z, box.minZ(), box.maxZ() + 1.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Euclidean distance from the point to the farthest corner of an inclusive block box. */
    static double farthestDistance(OutlineBox box, double x, double y, double z) {
        double dx = Math.max(Math.abs(x - box.minX()), Math.abs(box.maxX() + 1.0 - x));
        double dy = Math.max(Math.abs(y - box.minY()), Math.abs(box.maxY() + 1.0 - y));
        double dz = Math.max(Math.abs(z - box.minZ()), Math.abs(box.maxZ() + 1.0 - z));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double axisGap(double value, double min, double max) {
        if (value < min) {
            return min - value;
        }
        if (value > max) {
            return value - max;
        }
        return 0.0;
    }
}
