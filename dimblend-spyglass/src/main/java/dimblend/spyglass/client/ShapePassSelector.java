package dimblend.spyglass.client;

import dimblend.spyglass.structure.OutlineBox;

/**
 * Decides which of the two mesh passes a structure belongs to ({@link SpyglassShapeRenderer}).
 *
 * <p>A mesh <b>inside the loaded terrain</b> is drawn after the terrain (overlay pass), where it must
 * win against the fogged real building; a mesh outside it keeps the original after-sky pass. "Inside
 * the loaded terrain" means the mesh's footprint reaches into the chunks whose blocks are loaded,
 * i.e. its nearest horizontal distance from the camera is below {@code loadedRadius + 16}: the
 * 16-block margin covers section borders and block models that stick out of their cell, and the
 * radius is vanilla's own loaded area
 * ({@code Minecraft.options.getEffectiveRenderDistance() * 16}, {@code GameRenderer.java:1232}).
 *
 * <p>The overlay pass uses the frame's projection unmodified, whose far plane is
 * {@code getDepthFar() = 4 × render distance} ({@code GameRenderer.java:982-984}). A mesh at or past
 * that plane would be clipped, so only meshes whose farthest corner is below
 * {@code 0.95 × depthFar} may use the overlay pass; the rest keep the far pass with its own depth
 * range.
 *
 * <p>Pure math, unit tested.
 */
final class ShapePassSelector {
    /** Extra blocks past the loaded radius, covering section borders and block-model overhang. */
    static final double OVERLAY_MARGIN_BLOCKS = 16.0;
    /** Fraction of the frame's far plane the overlay may reach; leaves room for float error. */
    static final double OVERLAY_DEPTH_FRACTION = 0.95;

    private ShapePassSelector() {
    }

    /**
     * @param loadedRadiusBlocks vanilla's loaded terrain radius in blocks
     * @param depthFar           the frame projection's far plane ({@code GameRenderer#getDepthFar()})
     */
    static boolean isOverlay(
            OutlineBox bounds,
            double cameraX,
            double cameraY,
            double cameraZ,
            double loadedRadiusBlocks,
            float depthFar) {
        if (!(depthFar > 0.0F) || !Double.isFinite(loadedRadiusBlocks)) {
            return false;
        }
        if (nearestHorizontalDistance(bounds, cameraX, cameraZ)
                >= loadedRadiusBlocks + OVERLAY_MARGIN_BLOCKS) {
            return false;
        }
        return ShapeDepthRange.farthestDistance(bounds, cameraX, cameraY, cameraZ)
                < OVERLAY_DEPTH_FRACTION * depthFar;
    }

    /**
     * Horizontal distance from the camera to the nearest point of the box's footprint; 0 when the
     * camera is inside it. Max coordinates are inclusive, so the far faces sit at {@code max + 1}.
     */
    static double nearestHorizontalDistance(OutlineBox bounds, double cameraX, double cameraZ) {
        double dx = axisGap(cameraX, bounds.minX(), bounds.maxX() + 1.0);
        double dz = axisGap(cameraZ, bounds.minZ(), bounds.maxZ() + 1.0);
        return Math.sqrt(dx * dx + dz * dz);
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
