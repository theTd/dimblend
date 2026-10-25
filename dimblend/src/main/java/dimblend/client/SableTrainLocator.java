package dimblend.client;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import java.util.OptionalDouble;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

/**
 * Locates the train for the band HUD's off-track marker: among all Sable
 * sub-levels intersecting the track corridor (|z| &lt;= {@link #TRACK_HALF_WIDTH})
 * the frontmost one wins — the one farthest from spawn along the line — and
 * its bounding-box centroid is reported as the train position. Empty when
 * nothing sits on the corridor, and a silent no-op when Sable itself is
 * absent (the JiJ'd companion stub then returns an empty iterable).
 */
public final class SableTrainLocator {
    /** Half-width of the track corridor the HUD treats as "on the train line". */
    public static final int TRACK_HALF_WIDTH = 16;

    private static final int WORLD_X_LIMIT = 30_000_000;

    private SableTrainLocator() {
    }

    /** World-x of the frontmost on-track Sable centroid, or empty when none is found. */
    public static OptionalDouble frontmostCenterX(Level level) {
        BoundingBox3d corridor = new BoundingBox3d(
                -WORLD_X_LIMIT, level.getMinBuildHeight(), -TRACK_HALF_WIDTH,
                WORLD_X_LIMIT, level.getMaxBuildHeight(), TRACK_HALF_WIDTH);
        double bestX = Double.NaN;
        double bestAbs = -1;
        Vector3d center = new Vector3d();
        for (SubLevelAccess sub : SableCompanion.INSTANCE.getAllIntersecting(level, corridor)) {
            sub.boundingBox().center(center);
            double abs = Math.abs(center.x);
            if (abs > bestAbs) {
                bestAbs = abs;
                bestX = center.x;
            }
        }
        return bestAbs >= 0 ? OptionalDouble.of(bestX) : OptionalDouble.empty();
    }
}
