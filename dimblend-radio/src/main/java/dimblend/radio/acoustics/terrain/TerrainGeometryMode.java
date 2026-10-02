package dimblend.radio.acoustics.terrain;

/**
 * Where reflection geometry comes from. AUTO uses the Sodium render-mesh mirror when it is live
 * and covers the acoustic bounds, falling back to CPU voxelization otherwise; SODIUM forces the
 * mirror (accepting coverage holes, for diagnostics); VOXEL disables the mirror entirely,
 * including the build-pipeline tee.
 */
public enum TerrainGeometryMode {
    AUTO, SODIUM, VOXEL;

    /** Volatile so live probes can A/B the geometry source without a restart. */
    public static volatile TerrainGeometryMode CURRENT = parse(System.getProperty("dimblend.radio.acoustic.geometry"));

    private static TerrainGeometryMode parse(String value) {
        if ("sodium".equalsIgnoreCase(value)) {
            return SODIUM;
        }
        if ("voxel".equalsIgnoreCase(value)) {
            return VOXEL;
        }
        return AUTO;
    }
}
