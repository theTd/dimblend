package dimblend.client.bandrift;

import javax.annotation.Nullable;

/**
 * Pure state core of {@link BandFogTransition}: band identity observation, the 250 ms arming
 * grace window, and the per-channel exponential blend. No Minecraft types, so the race that
 * P4 fixed is reproducible in a plain unit test.
 *
 * <p><b>The race.</b> {@code ClientBandLane} is pushed by a 20 Hz server tick while fog is
 * computed per render frame (up to 280 Hz). A frame can arm a lane change and then compute fog
 * <em>before</em> the lane packet for the new band arrives: the fog target still equals the
 * previous output, {@code settleIfConverged()} sees "converged" and clears the blend. The real
 * target arrives a few milliseconds later and is applied with no blend at all — the visible snap
 * the P4 report describes.</p>
 *
 * <p><b>The fix, in one place.</b> (1) Every entry point observes the band identity, including
 * the two render handlers, so a change is armed by the first frame that can see it instead of
 * waiting for the next tick. (2) The blend can never settle within {@link #MIN_ARM_NANOS} of
 * being armed, so an early "already equal" frame cannot disarm it before the real target shows
 * up. Both rules live here and are asserted by {@code BandFogTransitionTest}.</p>
 */
public final class BandFogTransitionState {
    /** Grace window after arming during which the blend may not settle. */
    public static final long MIN_ARM_NANOS = 250_000_000L;
    /** Maximum channel difference that still counts as converged. */
    static final double SETTLE_EPSILON = 0.002;
    private static final long NEVER = Long.MIN_VALUE;

    private boolean active;
    private long armedNanos = NEVER;
    @Nullable
    private String lane;
    private int region = Integer.MIN_VALUE;

    private boolean colorValid;
    private float outRed;
    private float outGreen;
    private float outBlue;
    private float targetRed;
    private float targetGreen;
    private float targetBlue;

    private boolean skyValid;
    private float skyNear;
    private float skyFar;
    private float targetSkyNear;
    private float targetSkyFar;

    private boolean terrainValid;
    private float terrainNear;
    private float terrainFar;
    private float targetTerrainNear;
    private float targetTerrainFar;

    private long colorNanos;
    private long skyNanos;
    private long terrainNanos;

    /**
     * Records the band identity seen by one entry point.
     *
     * @return true when this observation is the one that armed the blend
     */
    public boolean observe(int region, @Nullable String lane, long nowNanos) {
        boolean first = this.region == Integer.MIN_VALUE;
        boolean changed = !first && (this.region != region || !sameLane(this.lane, lane));
        this.region = region;
        this.lane = lane;
        if (changed) {
            this.active = true;
            this.armedNanos = nowNanos;
        }
        return changed;
    }

    public boolean active() {
        return this.active;
    }

    /** True once the arming grace window has passed; only then may the blend settle. */
    public boolean maySettle(long nowNanos) {
        return this.active && this.armedNanos != NEVER && nowNanos - this.armedNanos >= MIN_ARM_NANOS;
    }

    @Nullable
    public String lane() {
        return this.lane;
    }

    public int region() {
        return this.region;
    }

    public float outRed() {
        return this.outRed;
    }

    public float outGreen() {
        return this.outGreen;
    }

    public float outBlue() {
        return this.outBlue;
    }

    public float skyNear() {
        return this.skyNear;
    }

    public float skyFar() {
        return this.skyFar;
    }

    public float terrainNear() {
        return this.terrainNear;
    }

    public float terrainFar() {
        return this.terrainFar;
    }

    public boolean colorValid() {
        return this.colorValid;
    }

    public boolean skyValid() {
        return this.skyValid;
    }

    public boolean terrainValid() {
        return this.terrainValid;
    }

    /** Advances the fog colour blend for one render frame. */
    public void stepColor(float red, float green, float blue, long nowNanos) {
        double dt = secondsSince(this.colorNanos, nowNanos);
        this.colorNanos = nowNanos;
        this.targetRed = red;
        this.targetGreen = green;
        this.targetBlue = blue;
        if (!this.colorValid || !this.active) {
            this.outRed = red;
            this.outGreen = green;
            this.outBlue = blue;
            this.colorValid = true;
            return;
        }
        this.outRed = (float) BandRiftSpring.fogBlendStep(this.outRed, red, dt);
        this.outGreen = (float) BandRiftSpring.fogBlendStep(this.outGreen, green, dt);
        this.outBlue = (float) BandRiftSpring.fogBlendStep(this.outBlue, blue, dt);
    }

    /** Advances the sky-plane fog distances ({@code FOG_SKY}) for one render frame. */
    public void stepSky(float near, float far, long nowNanos) {
        double dt = secondsSince(this.skyNanos, nowNanos);
        this.skyNanos = nowNanos;
        this.targetSkyNear = near;
        this.targetSkyFar = far;
        if (!this.skyValid || !this.active) {
            this.skyNear = near;
            this.skyFar = far;
            this.skyValid = true;
            return;
        }
        this.skyNear = (float) BandRiftSpring.fogBlendStep(this.skyNear, near, dt);
        this.skyFar = (float) BandRiftSpring.fogBlendStep(this.skyFar, far, dt);
    }

    /** Advances the terrain-plane fog distances ({@code FOG_TERRAIN}) for one render frame. */
    public void stepTerrain(float near, float far, long nowNanos) {
        double dt = secondsSince(this.terrainNanos, nowNanos);
        this.terrainNanos = nowNanos;
        this.targetTerrainNear = near;
        this.targetTerrainFar = far;
        if (!this.terrainValid || !this.active) {
            this.terrainNear = near;
            this.terrainFar = far;
            this.terrainValid = true;
            return;
        }
        this.terrainNear = (float) BandRiftSpring.fogBlendStep(this.terrainNear, near, dt);
        this.terrainFar = (float) BandRiftSpring.fogBlendStep(this.terrainFar, far, dt);
    }

    /**
     * Clears the blend once every channel has converged, but only after the grace window. Called
     * by both render handlers; the gate is the entire P4 fix.
     */
    public void settleIfConverged(long nowNanos) {
        if (!maySettle(nowNanos)) {
            return;
        }
        boolean settled = close(this.outRed, this.targetRed)
                && close(this.outGreen, this.targetGreen)
                && close(this.outBlue, this.targetBlue)
                && (!this.skyValid || (close(this.skyNear, this.targetSkyNear)
                        && close(this.skyFar, this.targetSkyFar)))
                && (!this.terrainValid || (close(this.terrainNear, this.targetTerrainNear)
                        && close(this.terrainFar, this.targetTerrainFar)));
        if (settled) {
            this.active = false;
        }
    }

    public void reset() {
        this.active = false;
        this.armedNanos = NEVER;
        this.lane = null;
        this.region = Integer.MIN_VALUE;
        this.colorValid = false;
        this.skyValid = false;
        this.terrainValid = false;
        this.outRed = 0.0F;
        this.outGreen = 0.0F;
        this.outBlue = 0.0F;
        this.skyNear = 0.0F;
        this.skyFar = 0.0F;
        this.terrainNear = 0.0F;
        this.terrainFar = 0.0F;
        this.targetRed = 0.0F;
        this.targetGreen = 0.0F;
        this.targetBlue = 0.0F;
        this.targetSkyNear = 0.0F;
        this.targetSkyFar = 0.0F;
        this.targetTerrainNear = 0.0F;
        this.targetTerrainFar = 0.0F;
        this.colorNanos = 0L;
        this.skyNanos = 0L;
        this.terrainNanos = 0L;
    }

    private static boolean close(float current, float target) {
        return Math.abs(current - target) <= SETTLE_EPSILON;
    }

    private static boolean sameLane(@Nullable String first, @Nullable String second) {
        return first == null ? second == null : first.equals(second);
    }

    private static double secondsSince(long previousNanos, long nowNanos) {
        if (previousNanos == 0L) {
            return 0.0;
        }
        return BandRiftSpring.sanitizeDt((nowNanos - previousNanos) / 1_000_000_000.0);
    }
}
