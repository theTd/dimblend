package dimblend.spyglass.client;

import javax.annotation.Nullable;
import org.joml.Vector3f;

/**
 * Pure geometry for the edge arrow: given the target's camera-space direction, find the point on the
 * inset screen rectangle the arrow should sit on and the direction it points.
 *
 * <p>Screen space has +X right and +Y down; the camera looks down -Z. A target in front uses its
 * perspective direction; a target behind the camera has no screen direction, so the arrow points
 * left or right by the sign of {@code camX} (straight behind is fixed to the right).
 *
 * <p>If the intersection lands inside the crosshair radius, the rectangle is recomputed with a
 * reduced inset once; if that still overlaps, the arrow goes to the nearest corner. The function
 * never throws: a degenerate rectangle returns {@code null}.
 */
final class EdgeArrowGeometry {
    record Arrow(float x, float y, float dirX, float dirY) {
    }

    static @Nullable Arrow compute(
            @Nullable Vector3f view,
            double guiWidth,
            double guiHeight,
            double insetPx,
            double crosshairRadiusPx) {
        if (view == null) {
            return null;
        }
        double left = insetPx;
        double right = guiWidth - insetPx;
        double top = insetPx;
        double bottom = guiHeight - insetPx;
        if (right - left < 1.0 || bottom - top < 1.0) {
            return null;
        }

        double dirX;
        double dirY;
        if (view.z < 0.0F) {
            double inverseDepth = 1.0 / -view.z;
            dirX = view.x * inverseDepth;
            dirY = -view.y * inverseDepth;
        } else {
            dirX = view.x > 0.0F ? 1.0 : (view.x < 0.0F ? -1.0 : 1.0);
            dirY = 0.0;
        }
        double length = Math.hypot(dirX, dirY);
        if (length < 1.0E-6) {
            dirX = 1.0;
            dirY = 0.0;
        } else {
            dirX /= length;
            dirY /= length;
        }

        double centerX = guiWidth * 0.5;
        double centerY = guiHeight * 0.5;
        double[] point = intersect(centerX, centerY, dirX, dirY, left, right, top, bottom);
        if (point == null) {
            return null;
        }
        if (Math.hypot(point[0] - centerX, point[1] - centerY) < crosshairRadiusPx) {
            double inner = Math.max(0.0, insetPx - (crosshairRadiusPx - Math.hypot(point[0] - centerX, point[1] - centerY)));
            double[] retry = inner == insetPx
                    ? null
                    : intersect(centerX, centerY, dirX, dirY, inner, guiWidth - inner, inner, guiHeight - inner);
            if (retry != null && Math.hypot(retry[0] - centerX, retry[1] - centerY) >= crosshairRadiusPx) {
                point = retry;
            } else {
                // The inset rectangle is too small to clear the crosshair: use the screen corner
                // the ray points at (not the nearest corner, which would sit even closer).
                point = directionalCorner(
                        centerY, dirX, dirY, 0.0, guiWidth, 0.0, guiHeight);
            }
        }
        return new Arrow((float) point[0], (float) point[1], (float) dirX, (float) dirY);
    }

    /** First intersection of the ray from the center with the rectangle; null when it cannot hit. */
    private static double[] intersect(
            double centerX,
            double centerY,
            double dirX,
            double dirY,
            double left,
            double right,
            double top,
            double bottom) {
        double tx = Double.POSITIVE_INFINITY;
        double ty = Double.POSITIVE_INFINITY;
        if (dirX > 1.0E-9) {
            tx = (right - centerX) / dirX;
        } else if (dirX < -1.0E-9) {
            tx = (left - centerX) / dirX;
        }
        if (dirY > 1.0E-9) {
            ty = (bottom - centerY) / dirY;
        } else if (dirY < -1.0E-9) {
            ty = (top - centerY) / dirY;
        }
        double t = Math.min(tx, ty);
        if (!Double.isFinite(t) || t <= 0.0) {
            return null;
        }
        return new double[] {centerX + dirX * t, centerY + dirY * t};
    }

    /** The rectangle corner the ray points at, i.e. the farthest one from the center in that direction. */
    private static double[] directionalCorner(
            double centerY,
            double dirX,
            double dirY,
            double left,
            double right,
            double top,
            double bottom) {
        double x = dirX >= 0.0 ? right : left;
        double y;
        if (dirY > 0.0) {
            y = bottom;
        } else if (dirY < 0.0) {
            y = top;
        } else {
            // Straight horizontal ray: both vertical corners are equally far; pick top for stability.
            y = centerY - top >= bottom - centerY ? top : bottom;
        }
        return new double[] {x, y};
    }

    private EdgeArrowGeometry() {
    }
}
