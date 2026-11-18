package dimblend.experience.compat.simurail;

import dimblend.api.ChaseBrakeBogeyMarker;

/**
 * Isolated class-loading boundary for {@link ChaseBrakeBogeyMarker}: only resolved when the core
 * {@code dimblend} mod is installed alongside {@code dimblend_experience}.
 */
public final class ChaseBrakeBogeyProbe {

    public static boolean isMarked(Object bogey) {
        return bogey instanceof ChaseBrakeBogeyMarker marker && marker.dimblend$isChaseBrakeBogey();
    }

    private ChaseBrakeBogeyProbe() {
    }
}
