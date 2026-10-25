package dimblend.spyglass.client;

import dimblend.spyglass.structure.OutlineBox;

/**
 * Share of the real terrain fog mixed into a structure mesh ({@link SpyglassShapeRenderer}).
 *
 * <p>The chunk shaders mix towards the fog color by {@code fogValue * FogColor.a}
 * ({@code assets/minecraft/shaders/include/fog.glsl}), so this share is exactly the alpha of
 * {@code FogColor}: zero reproduces the pre-fog crisp look, one is the terrain's own fog.
 *
 * <p>The overlay pass keeps the near share for the whole mesh, so a structure that starts before the
 * fog and reaches into it fades like the terrain it covers: the per-vertex fog factor stays zero
 * before the fog start and reaches at most the near share inside and past the band. The far pass has
 * no terrain behind it as a reference, so it ramps from the near share at the fog end to the far
 * share at {@link #MAX_FOG_DISTANCE_BLOCKS}, the outer reveal limit. The ramp uses the horizontal
 * distance to the mesh bounds' horizontal center; the per-vertex fog factor still uses the real
 * per-vertex distance, so the ramp only chooses how much of that fog the mesh receives.
 *
 * <p>Pure math, unit tested. Invalid input falls back to the near share instead of producing NaN: a
 * negative or non-finite fog end, a non-finite or negative strength, a non-finite distance.
 */
final class ShapeFogStrength {
    /** Outer reveal distance; also the end of the far-pass ramp. */
    static final double MAX_FOG_DISTANCE_BLOCKS = 1024.0;

    private ShapeFogStrength() {
    }

    /** Share for an overlay mesh: the near share everywhere, clamped to {@code [0, 1]}. */
    static double overlay(double nearStrength) {
        return clamp01(nearStrength);
    }

    /**
     * Share for a far mesh.
     *
     * @param nearStrength  share at and inside the fog end
     * @param farStrength   share at {@link #MAX_FOG_DISTANCE_BLOCKS}
     * @param fogEndBlocks  end of the last terrain fog band, in blocks
     * @param centerDistance horizontal distance from the camera to the mesh bounds' horizontal center
     */
    static double far(
            double nearStrength,
            double farStrength,
            double fogEndBlocks,
            double centerDistance) {
        double near = clamp01(nearStrength);
        double far = clamp01(farStrength);
        // A fog end outside [0, 1024) cannot describe the reveal band (plain terrain fog ends at the
        // effective render distance, 32..1024 blocks); keep the near share instead of dividing by an
        // odd range. This also catches NaN. A fog end of exactly 1024 would divide by zero.
        if (!(fogEndBlocks >= 0.0 && fogEndBlocks < MAX_FOG_DISTANCE_BLOCKS)) {
            return near;
        }
        // A negative or NaN distance means "at the camera"; +Infinity still means "beyond 1024".
        double distance = centerDistance > 0.0 ? centerDistance : 0.0;
        double progress = (distance - fogEndBlocks) / (MAX_FOG_DISTANCE_BLOCKS - fogEndBlocks);
        if (!(progress > 0.0)) {
            return near;
        }
        if (progress >= 1.0) {
            return far;
        }
        return near + (far - near) * progress;
    }

    /** Horizontal distance from the camera to the center of the mesh's covered block volume. */
    static double centerDistance(OutlineBox bounds, double cameraX, double cameraZ) {
        return Math.hypot(bounds.centerX() - cameraX, bounds.centerZ() - cameraZ);
    }

    /** Unreadable strengths draw no extra fog, the pre-fog behaviour. */
    private static double clamp01(double strength) {
        if (!(strength > 0.0)) {
            // Also catches NaN; a negative or unset share must not brighten or darken the fog.
            return 0.0;
        }
        return Math.min(strength, 1.0);
    }
}
