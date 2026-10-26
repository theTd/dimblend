package dimblend.experience.headlight;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Pure parts of the lit-lava highlight: the beam lit test, the top-face perimeter of a connected lit region,
 * the highlight cap and the radius query the server answers with. Photometry mirrors {@code beam.glsl:20-31}
 * through {@link HeadlightMath}, the same functions the surface shader's {@code coneFactor * attenuation(distance)}
 * uses ({@code surface.fsh:23-30}); the server calls {@link #lit} unchanged, so "marked lava" is exactly what the
 * client outlines.
 */
public final class HeadlightLavaMath {
    /** Warm highlight color (0xFFB040). */
    public static final float RED = 1.0F;
    public static final float GREEN = 176.0F / 255.0F;
    public static final float BLUE = 64.0F / 255.0F;

    /**
     * Beam strength (cone x attenuation) above which a lava top face counts as lit. At the defaults
     * (range 24, full angle 50, intensity 1) the axial falloff drops below this at 22.06 blocks, so the
     * outline follows the pool to where the shader still adds a visible 4%; the last ~2 blocks, where the
     * surface light has faded to near nothing, do not pull the outline past the visible light. Off-axis the
     * soft shoulder ({@link HeadlightMath#cone}) narrows the lit area to 84-91% of the cone radius.
     */
    public static final double LIT_THRESHOLD = 0.04;

    /** Outline height above the fluid surface; {@code RenderType.lines()} is LEQUAL depth-tested against it. */
    public static final double SURFACE_LIFT = 0.01;

    /** Neighbouring surfaces this close in height are the same step: no segment between them. */
    public static final double SAME_HEIGHT = 1.0E-4;

    /** Hard cap on highlighted blocks per recompute; a 24-block beam covers far fewer even over a lava sea. */
    public static final int MAX_SURFACES = 1024;

    /** Alpha pulse frequency in cycles per second (one cycle per 20 game ticks). */
    public static final double PULSE_HZ = 1.0;

    /**
     * Fraction of the pulse alpha used for edge parts the camera cannot see (behind the train cab, terrain
     * or a wall). Every segment is drawn once at this dimmer alpha without the depth test, then the
     * depth-tested pass paints the visible parts at the full pulse alpha.
     */
    public static final float HIDDEN_ALPHA_FACTOR = 0.4F;

    private static final float MIN_ALPHA = 0.35F;
    private static final float MAX_ALPHA = 0.75F;
    private static final double TICKS_PER_SECOND = 20.0;

    /**
     * Beam strength at a point, mirroring the surface shader. {@code direction} must be a unit vector
     * ({@code HeadlightCollector} and {@link HeadlightLens} normalise it). Samples at or past the range are
     * skipped as in {@code surface.fsh:23}; a zero-length offset has no direction and is skipped too.
     */
    public static double intensity(Vec3 lens, Vec3 direction, double x, double y, double z,
                                   double range, double fullAngle, double strength) {
        double dx = x - lens.x;
        double dy = y - lens.y;
        double dz = z - lens.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < 1.0E-4 || distance >= range) {
            return 0.0;
        }
        double cosine = (dx * direction.x + dy * direction.y + dz * direction.z) / distance;
        return HeadlightMath.cone(cosine, fullAngle) * HeadlightMath.attenuation(distance, range, strength);
    }

    /** Whether the beam lights this point strongly enough to highlight the surface it belongs to. */
    public static boolean lit(Vec3 lens, Vec3 direction, double x, double y, double z,
                              double range, double fullAngle, double strength) {
        return intensity(lens, direction, x, y, z, range, fullAngle, strength) > LIT_THRESHOLD;
    }

    /**
     * Whether a lava top face lies within {@code radius} blocks (Euclidean) of {@code center}. Measured from the
     * face's centre -- the same point the lit test samples -- and including the fluid's own height, so a
     * source block and a flowing step at the same block coordinates are not conflated.
     */
    public static boolean withinRadius(int x, int y, int z, float height, Vec3 center, double radius) {
        return surfaceDistanceSqr(x, y, z, height, center) <= radius * radius;
    }

    /**
     * Whether any lit top face in {@code surfaces} lies within {@code radius} blocks of {@code center}. The server
     * scans once per recompute window and answers every observer with this test, so the scan cost is paid per
     * tracker, not per observer.
     */
    public static boolean anyWithin(List<LavaSurface> surfaces, Vec3 center, double radius) {
        double limit = radius * radius;
        for (LavaSurface surface : surfaces) {
            if (surfaceDistanceSqr(surface.x(), surface.y(), surface.z(), surface.height(), center) <= limit) {
                return true;
            }
        }
        return false;
    }

    /** Squared distance from {@code center} to a lava top face's centre. */
    public static double surfaceDistanceSqr(int x, int y, int z, float height, Vec3 center) {
        double dx = x + 0.5 - center.x;
        double dy = (double) y + height - center.y;
        double dz = z + 0.5 - center.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Conservative bound on whether a beam can light anything within {@code radius} of {@code point}: the lens
     * sits at most {@code range} from every point the cone can reach, so a lamp farther than
     * {@code range + radius} cannot mark anything there. Used to skip lamps before their cone is scanned.
     */
    public static boolean coneReaches(Vec3 lens, Vec3 point, double range, double radius) {
        double reach = range + radius;
        return lens.distanceToSqr(point) <= reach * reach;
    }

    /**
     * A top-face side needs a segment unless the horizontal neighbour is lit lava at the same surface
     * height: equal-height lit neighbours hide the side, a step keeps both sides (each at its own height).
     */
    public static boolean drawsEdge(boolean neighbourLit, float height, float neighbourHeight) {
        return !neighbourLit || Math.abs(height - neighbourHeight) > SAME_HEIGHT;
    }

    /** Perimeter segments of the lit region, in input order: one to four per lit top face. */
    public static List<LavaEdge> edges(List<LavaSurface> surfaces) {
        Map<Long, Float> heights = new HashMap<>(Math.max(16, surfaces.size() * 2));
        for (LavaSurface surface : surfaces) {
            heights.put(BlockPos.asLong(surface.x(), surface.y(), surface.z()), surface.height());
        }
        List<LavaEdge> edges = new ArrayList<>(surfaces.size() * 4);
        for (LavaSurface surface : surfaces) {
            int x = surface.x();
            int y = surface.y();
            int z = surface.z();
            double outline = surface.outlineY();
            // The four sides of the top face; each neighbour is compared at the same block layer.
            if (needsEdge(heights, x + 1, y, z, surface.height())) {
                edges.add(new LavaEdge(x + 1, outline, z, x + 1, outline, z + 1));
            }
            if (needsEdge(heights, x - 1, y, z, surface.height())) {
                edges.add(new LavaEdge(x, outline, z, x, outline, z + 1));
            }
            if (needsEdge(heights, x, y, z - 1, surface.height())) {
                edges.add(new LavaEdge(x, outline, z, x + 1, outline, z));
            }
            if (needsEdge(heights, x, y, z + 1, surface.height())) {
                edges.add(new LavaEdge(x, outline, z + 1, x + 1, outline, z + 1));
            }
        }
        return edges;
    }

    private static boolean needsEdge(Map<Long, Float> heights, int x, int y, int z, float height) {
        Float neighbour = heights.get(BlockPos.asLong(x, y, z));
        return drawsEdge(neighbour != null, height, neighbour != null ? neighbour : 0.0F);
    }

    /**
     * Hard cap: keeps the blocks nearest the camera (ties broken by position, so the same set is kept for
     * the same scene regardless of scan order). A cut drops the far end of a huge lava field, not the pool
     * at the bumper.
     */
    public static List<LavaSurface> capped(List<LavaSurface> surfaces, Vec3 camera, int limit) {
        if (surfaces.size() <= limit) {
            return surfaces;
        }
        List<LavaSurface> sorted = new ArrayList<>(surfaces);
        sorted.sort(Comparator.comparingDouble((LavaSurface surface) -> distanceSquared(surface, camera))
                .thenComparingInt(LavaSurface::x)
                .thenComparingInt(LavaSurface::y)
                .thenComparingInt(LavaSurface::z));
        return List.copyOf(sorted.subList(0, limit));
    }

    private static double distanceSquared(LavaSurface surface, Vec3 camera) {
        double dx = surface.centerX() - camera.x;
        double dy = surface.centerY() - camera.y;
        double dz = surface.centerZ() - camera.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Gentle 1 Hz alpha pulse between 0.35 and 0.75; {@code gameTime} is ticks plus the partial tick. */
    public static float alpha(double gameTime) {
        double phase = 0.5 + 0.5 * Math.sin(gameTime * Math.PI * 2.0 * PULSE_HZ / TICKS_PER_SECOND);
        return (float) (MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * phase);
    }

    /** Alpha of the see-through under-pass: {@link #HIDDEN_ALPHA_FACTOR} of the current pulse alpha. */
    public static float hiddenAlpha(float alpha) {
        return alpha * HIDDEN_ALPHA_FACTOR;
    }

    private HeadlightLavaMath() {
    }
}
