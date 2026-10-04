package dimblend.radio.acoustics;

/**
 * Acoustic material table shared by the voxel mesher and both Steam Audio scenes.
 * {@link AcousticBlockMaterials} maps block states to these indices; the indices are the
 * per-triangle material IDs of the GPU mesh, so keep them stable (0–4 match the original five
 * classes and the recorded validation meshes).
 * <p>
 * Absorption is per band. Transmission is the low/mid/high amplitude through one block and falls
 * off with the path length by the material's thickness law; a run of mixed blocks combines its
 * layers ({@link Path}).
 * <p>
 * One block is a wall, not a metre of rock: a single layer muffles (one block of stone passes the
 * lows at -2.5 dB, the mids at -7.5 dB and the highs at -16.5 dB) and only thick walls insulate
 * (two blocks of stone -8.5/-13.5/-22.5 dB). The lows pass best and the highs worst, as through
 * any real wall.
 */
public final class AcousticMaterials {
    public static final int WOOL = 0, FOLIAGE = 1, SOIL = 2, WOOD = 3, STONE = 4, GLASS = 5, METAL = 6, ICE = 7, SNOW = 8;
    public static final int COUNT = 9;
    /** Shortest and longest path through one solid run the transmission model distinguishes. */
    public static final double MIN_THICKNESS = 0.125, MAX_THICKNESS = 8;
    /**
     * Diffuse share of GPU reflections. Steam Audio shades every visible hit toward the listener
     * with this Lambert term; at zero only the 100-exponent specular lobe gathers, which leaves the
     * axis-aligned voxel field sparse. CPU scenes keep zero so their reflection field stays repeatable.
     */
    public static final float GPU_SCATTERING = 0.05f;
    /** Most a solid run may pass, whatever its material and thickness. */
    private static final float MAX_TRANSMISSION = 0.95f;

    /**
     * @param absorption low/mid/high energy absorbed per reflection
     * @param transmission low/mid/high amplitude through one block
     * @param thicknessLaw exponent of the path length: 1 is the mass law (amplitude halves per
     *        doubling, -6 dB); foliage is mostly air and loses far less per extra block
     * @param weight what one block adds to a {@link Path}: {@code transmission^(-1/law)} per band
     */
    private record Profile(float[] absorption, float[] transmission, double thicknessLaw, double[] weight) {
        Profile(float[] absorption, float[] transmission, double thicknessLaw) {
            this(absorption, transmission, thicknessLaw, new double[] {Math.pow(transmission[0], -1 / thicknessLaw),
                    Math.pow(transmission[1], -1 / thicknessLaw), Math.pow(transmission[2], -1 / thicknessLaw)});
        }
    }

    private static final Profile[] PROFILES = new Profile[COUNT];
    static {
        // Wool, carpets, beds, hay, moss, sponge: porous, absorbs highs.
        PROFILES[WOOL] = new Profile(bands(0.34f, 0.51f, 0.98f), bands(0.80f, 0.48f, 0.15f), 1);
        PROFILES[FOLIAGE] = new Profile(bands(0.30f, 0.45f, 0.90f), bands(0.85f, 0.70f, 0.50f), 0.5);
        // Dirt, grass, sand, gravel, mud, soul soil.
        PROFILES[SOIL] = new Profile(bands(0.22f, 0.33f, 0.66f), bands(0.70f, 0.38f, 0.13f), 1);
        PROFILES[WOOD] = new Profile(bands(0.14f, 0.21f, 0.42f), bands(0.80f, 0.50f, 0.20f), 1);
        // Every natural rock, ore, brick, concrete and unknown block: one class keeps cave meshes merged.
        PROFILES[STONE] = new Profile(bands(0.04f, 0.06f, 0.12f), bands(0.75f, 0.42f, 0.15f), 1);
        // Light and stiff: reflects almost everything, panes leak mids and lows.
        PROFILES[GLASS] = new Profile(bands(0.10f, 0.05f, 0.04f), bands(0.85f, 0.60f, 0.30f), 1);
        // Dense: the hardest reflector and the best barrier.
        PROFILES[METAL] = new Profile(bands(0.08f, 0.05f, 0.05f), bands(0.60f, 0.28f, 0.08f), 1);
        PROFILES[ICE] = new Profile(bands(0.04f, 0.04f, 0.06f), bands(0.75f, 0.45f, 0.18f), 1);
        // Fresh snow is mostly air: soaks up highs like wool and blocks them like soil.
        PROFILES[SNOW] = new Profile(bands(0.25f, 0.50f, 0.80f), bands(0.75f, 0.38f, 0.10f), 1);
    }

    private static float[] bands(float low, float mid, float high) {
        return new float[] {low, mid, high};
    }

    public static float[] absorption(int material) {
        return PROFILES[material].absorption.clone();
    }

    /** Transmission through {@code thickness} blocks of one material, clamped to the modeled range. */
    public static float[] transmission(int material, double thickness) {
        Path path = new Path();
        path.add(material, thickness);
        return path.transmission();
    }

    /**
     * The layers of one solid run along a ray, e.g. carpet on planks on stone. Each block weighs
     * like a mass: one block of a material transmitting {@code T} counts {@code 1/T} (per band,
     * {@code T^(-1/law)} for a softer law), the run transmits the inverse of the summed weight, and
     * the law is the length-weighted mean. A run of one material gives
     * {@code T * thickness^-law}, exactly as before; a stone wall lined with wool passes what two
     * blocks of stone pass in the highs but what wool and stone together pass in the lows.
     */
    public static final class Path {
        private final double[] weight = new double[3];
        private double length, lawLength;

        public void add(int material, double blocks) {
            if (blocks <= 0) return;
            Profile profile = PROFILES[material];
            for (int band = 0; band < 3; band++) {
                weight[band] += blocks * profile.weight[band];
            }
            length += blocks;
            lawLength += blocks * profile.thicknessLaw;
        }

        public double length() { return length; }

        public float[] transmission() {
            float[] result = new float[3];
            if (length <= 0) {
                java.util.Arrays.fill(result, MAX_TRANSMISSION);
                return result;
            }
            // Clamp the whole run to the modeled range, keeping the share of each layer.
            double scale = Math.max(MIN_THICKNESS, Math.min(MAX_THICKNESS, length)) / length;
            double law = lawLength / length;
            for (int band = 0; band < 3; band++) {
                result[band] = (float) Math.min(MAX_TRANSMISSION, Math.pow(weight[band] * scale, -law));
            }
            return result;
        }
    }

    private AcousticMaterials() { }
}
