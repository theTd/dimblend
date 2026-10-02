package dimblend.radio.acoustics;

/**
 * The five acoustic material classes shared by the voxel mesher, the Sodium mesh tee and both
 * Steam Audio scenes. A class is chosen from {@link AcousticRaycaster#reflectivity}; transmission
 * depends on the class and on how much of it the direct path crosses.
 */
public final class AcousticMaterials {
    public static final int COUNT = 5;
    /** Shortest and longest path through one solid run the transmission model distinguishes. */
    public static final double MIN_THICKNESS = 0.125, MAX_THICKNESS = 8;
    /**
     * Diffuse share of GPU reflections. Steam Audio shades every visible hit toward the listener
     * with this Lambert term; at zero only the 100-exponent specular lobe gathers, which leaves the
     * axis-aligned voxel field sparse. CPU scenes keep zero so their reflection field stays repeatable.
     */
    public static final float GPU_SCATTERING = 0.05f;
    private static final int THICKNESS_STEPS_PER_BLOCK = 8;
    private static final float[] REFLECTIVITY = {0.15f, 0.25f, 0.45f, 0.65f, 0.9f};
    /** Low/mid/high amplitude transmission through one block of each class. */
    private static final float[][] TRANSMISSION = {
            {0.50f, 0.25f, 0.08f}, // wool, carpet: porous, absorbs highs
            {0.85f, 0.70f, 0.50f}, // leaves: open foliage
            {0.30f, 0.16f, 0.06f}, // snow, sand, soil, gravel
            {0.45f, 0.28f, 0.12f}, // wood
            {0.35f, 0.20f, 0.08f}, // stone, metal, glass and everything else
    };
    /**
     * Thickness exponent: solid walls follow the mass law (amplitude halves per doubling of
     * thickness, -6 dB); foliage is mostly air and loses far less per extra block.
     */
    private static final double[] THICKNESS_LAW = {1, 0.5, 1, 1, 1};

    /** Material class for a block reflectivity; the same thresholds feed every geometry path. */
    public static int bucket(float reflectivity) {
        return reflectivity < 0.2 ? 0 : reflectivity < 0.3 ? 1 : reflectivity < 0.5 ? 2 : reflectivity < 0.8 ? 3 : 4;
    }

    public static float reflectivity(int bucket) {
        return REFLECTIVITY[bucket];
    }

    public static float[] absorption(int bucket) {
        float absorption = Math.max(0.02f, 1 - REFLECTIVITY[bucket]);
        return new float[] {absorption * 0.4f, absorption * 0.6f, Math.min(0.98f, absorption * 1.2f)};
    }

    /** Transmission through {@code thickness} blocks of the class, clamped to the modeled range. */
    public static float[] transmission(int bucket, double thickness) {
        double clamped = Math.max(MIN_THICKNESS, Math.min(MAX_THICKNESS, thickness));
        double scale = Math.pow(clamped, -THICKNESS_LAW[bucket]);
        float[] result = new float[3];
        for (int band = 0; band < 3; band++) {
            result[band] = (float) Math.min(0.95, TRANSMISSION[bucket][band] * scale);
        }
        return result;
    }

    /** Quantizes a traversed thickness so native material records stay bounded and reusable. */
    public static int thicknessStep(double thickness) {
        double clamped = Math.max(MIN_THICKNESS, Math.min(MAX_THICKNESS, thickness));
        return (int) Math.round(clamped * THICKNESS_STEPS_PER_BLOCK);
    }

    public static double thickness(int step) {
        return step / (double) THICKNESS_STEPS_PER_BLOCK;
    }

    private AcousticMaterials() { }
}
