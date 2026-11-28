package dimblend.client.bandrift;

import java.util.Locale;

/**
 * The six rift uniforms of the current frame, in the exact form every shader consumes:
 * {@code u_RiftMode}, {@code u_RiftPlane}, {@code u_RiftCenter}, {@code u_RiftRadii},
 * {@code u_RiftStyle} and {@code u_RiftCam}.
 *
 * <p>Written once per frame by {@link BandRiftRenderer} before the first masked terrain pass and
 * read by three consumers that all run later in the same frame: the Sodium chunk shader (through
 * {@code DefaultShaderInterfaceRiftUniformsMixin}), {@code dimblend:mirage_terrain} and
 * {@code dimblend:band_far_sky}. Keeping one holder is what guarantees the three passes agree on
 * the shell to the last bit — a divergent copy would show as a seam between real and mirage
 * geometry.</p>
 *
 * <p>Arrays are exposed read-only for the uniform writers; nothing outside this class mutates
 * them, and no accessor allocates (uniform upload is per region, per pass).</p>
 */
public final class VolumetricRiftState {
    /** Layer indices match {@code DefaultTerrainRenderPasses.ALL}: SOLID, CUTOUT, TRANSLUCENT. */
    public static final int LAYER_SOLID = 0;
    public static final int LAYER_CUTOUT = 1;
    public static final int LAYER_TRANSLUCENT = 2;

    private static final float[] PLANE = {1.0F, 0.0F, 0.0F, 0.0F};
    private static final float[] CENTER = {0.0F, 0.0F, 0.0F, 0.0F};
    private static final float[] RADII = {1.0F, 1.0F, 1.0F, 0.0F};
    private static final float[] STYLE = {0.55F, 0.80F, 1.0F, 0.0F};
    private static final float[] CAM = {0.0F, 0.0F, 0.0F, VolumetricRiftMath.RIM_WIDTH};

    private static boolean active;
    private static int mode;
    private static int lightOverride;
    private static int slabX = Integer.MIN_VALUE;
    private static String nearLane = "";
    private static String farLane = "";
    private static double distance;
    private static double open;
    private static double glitch;
    private static double farLaneDistance = Double.POSITIVE_INFINITY;

    private static int farDraws;
    private static int farDrawsThisFrame;
    private static long farFrameStart;
    private static int lastFrameMicros;

    private VolumetricRiftState() {
    }

    /**
     * Publishes this frame's shell.
     *
     * @param params     camera-relative shell from {@link VolumetricRiftMath#volume}
     * @param glowRed    far-lane glow colour
     * @param nearLaneId lane name of the band the camera is in
     * @param farLaneId  lane name of the band across the seam
     */
    public static void activate(
            VolumetricRiftMath.Params params,
            float glowRed, float glowGreen, float glowBlue,
            int seamSlabX, String nearLaneId, String farLaneId,
            double seamDistance, double openFactor, double glitchDensity,
            double seconds) {
        active = true;
        slabX = seamSlabX;
        nearLane = nearLaneId == null ? "" : nearLaneId;
        farLane = farLaneId == null ? "" : farLaneId;
        distance = seamDistance;
        open = openFactor;
        glitch = glitchDensity;
        PLANE[0] = params.normalX();
        PLANE[1] = params.normalY();
        PLANE[2] = params.normalZ();
        PLANE[3] = params.planeW();
        CENTER[0] = params.centerX();
        CENTER[1] = params.centerY();
        CENTER[2] = params.centerZ();
        CENTER[3] = params.open();
        RADII[0] = params.radiusX();
        RADII[1] = params.radiusY();
        RADII[2] = params.radiusZ();
        RADII[3] = params.glitch();
        STYLE[0] = glowRed;
        STYLE[1] = glowGreen;
        STYLE[2] = glowBlue;
        STYLE[3] = (float) seconds;
        CAM[0] = params.camX();
        CAM[1] = params.camY();
        CAM[2] = params.camZ();
        CAM[3] = params.rimWidth();
    }

    /** No active seam (or S2 not usable this frame): every mask is off. */
    public static void deactivate() {
        active = false;
        mode = VolumetricRiftMath.MODE_OFF;
        lightOverride = 0;
        slabX = Integer.MIN_VALUE;
        nearLane = "";
        farLane = "";
        distance = 0.0;
        open = 0.0;
        glitch = 0.0;
        CENTER[3] = 0.0F;
    }

    public static boolean active() {
        return active;
    }

    public static int mode() {
        return mode;
    }

    public static void setMode(int riftMode) {
        mode = riftMode;
    }

    public static int lightTextureOverride() {
        return lightOverride;
    }

    public static void setLightTextureOverride(int textureId) {
        lightOverride = textureId;
    }

    public static int slabX() {
        return slabX;
    }

    public static String nearLane() {
        return nearLane;
    }

    public static String farLane() {
        return farLane;
    }

    public static double distance() {
        return distance;
    }

    public static double open() {
        return open;
    }

    public static double glitch() {
        return glitch;
    }

    public static float[] plane() {
        return PLANE;
    }

    public static float[] center() {
        return CENTER;
    }

    public static float[] radii() {
        return RADII;
    }

    public static float[] style() {
        return STYLE;
    }

    /** Updates the shader clock without touching the other style channels. */
    public static void setSeconds(float seconds) {
        STYLE[3] = seconds;
    }

    public static float[] cam() {
        return CAM;
    }

    /** Called once before the far frame's first pass; resets the per-frame draw counter. */
    public static void beginFarFrame() {
        farDrawsThisFrame = 0;
        farFrameStart = System.nanoTime();
    }

    /** Called by the second-pass scheduler per {@code renderLayer} / far-sky / mirage draw. */
    public static void noteFarDraw() {
        farDraws++;
        farDrawsThisFrame++;
    }

    public static void endFarFrame() {
        lastFrameMicros = farFrameStart == 0L
                ? 0
                : (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - farFrameStart) / 1000L);
        farFrameStart = 0L;
    }

    public static int farDraws() {
        return farDraws;
    }

    public static int farDrawsThisFrame() {
        return farDrawsThisFrame;
    }

    public static int lastFrameMicros() {
        return lastFrameMicros;
    }

    public static void setFarLaneDistance(double blocks) {
        farLaneDistance = blocks;
    }

    public static double farLaneDistance() {
        return farLaneDistance;
    }

    /** One-line uniform dump for {@code /dimblendrift diag}. */
    public static String describe() {
        return String.format(Locale.ROOT,
                "rift=%s mode=%d slabX=%s near=%s far=%s dist=%.1f open=%.3f glitch=%.3f "
                        + "plane=%.2f,%.2f,%.2f w=%.2f radii=%.2f,%.2f,%.2f",
                active ? "active" : "idle", mode,
                slabX == Integer.MIN_VALUE ? "?" : Integer.toString(slabX),
                nearLane, farLane, distance, open, glitch,
                PLANE[0], PLANE[1], PLANE[2], PLANE[3], RADII[0], RADII[1], RADII[2]);
    }
}
