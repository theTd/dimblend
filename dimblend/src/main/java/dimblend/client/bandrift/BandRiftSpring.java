package dimblend.client.bandrift;

/**
 * Pure state and curve math behind the band-rift membrane. No Minecraft types, no
 * client initialisation: everything here runs in a plain unit test.
 *
 * <p>The membrane at a band boundary column opens on one distance-driven curve:</p>
 * <ul>
 *   <li>{@code dist >= 44} — closed starfield curtain ({@code target = 0});</li>
 *   <li>{@code 44 > dist > openNear} — unstable random-holes preview
 *       ({@code target = 0.45 * smoothstep(44, openNear, dist)});</li>
 *   <li>{@code dist <= openNear} — fully torn open ({@code target = 1}), latched until the
 *       distance exceeds {@code openNear + 4.5} (hysteresis, so a train hovering on the
 *       threshold does not strobe).</li>
 * </ul>
 *
 * <p>A fast train tears the membrane early: {@code openNear} grows with the closing speed by
 * {@link #APPROACH_LEAD_SECONDS} of travel (capped at {@link #APPROACH_LEAD_MAX}), so a
 * 20 m/s train starts opening ~38 blocks out instead of ~16.</p>
 *
 * <p>The opening itself is a <b>critically damped</b> second-order response, integrated with
 * the exact analytic step (not Euler): a stable, frame-rate independent ease-in whose
 * overshoot is structurally impossible. {@code dt} is sanitised first — a stalled frame, a
 * NaN clock or a time-travelling value cannot inject energy into the spring.</p>
 */
public final class BandRiftSpring {
    /** Distance where the membrane is fully closed. */
    public static final double FAR_DISTANCE = 44.0;
    /** Close-range tear threshold at zero closing speed. */
    public static final double OPEN_NEAR_BASE = 16.0;
    /** Seconds of closing travel used as the speed-based early-open lead. */
    public static final double APPROACH_LEAD_SECONDS = 1.35;
    /**
     * Cap on the lead, in blocks. Kept at {@code 22} so {@code 16 + 22 = 38 < 44}: the fastest
     * approach still reaches full open ~6 blocks before the closed curtain would begin.
     */
    public static final double APPROACH_LEAD_MAX = 22.0;
    /** Latch release margin past the close-range threshold. */
    public static final double LATCH_RELEASE = 4.5;
    /** Peak opening of the mid-distance "unstable holes" preview state. */
    public static final double PREVIEW_TARGET = 0.45;

    /** {@code solidScale} ramps in over this range of {@code openFactor}. */
    public static final double SOLID_EDGE_LOW = 0.42;
    public static final double SOLID_EDGE_HIGH = 0.98;
    /** Peak random glitch-cell density (fraction of 1/4-block cells). */
    public static final double GLITCH_PEAK = 0.34;
    /** {@code openFactor} where the glitch density ramp is saturated. */
    public static final double GLITCH_RAMP = 0.52;
    /** How much the torn core suppresses the remaining random holes. */
    public static final double GLITCH_SOLID_SUPPRESS = 0.85;

    /** Camera distance where the remaining membrane starts fading out. */
    public static final double CAMERA_FADE_START = 1.20;
    /** Camera distance where the remaining membrane is gone. */
    public static final double CAMERA_FADE_END = 2.20;
    /** Sub-level box distance to the plane where the carriage fade starts. */
    public static final double CARRIAGE_FADE_START = 0.50;
    /** Sub-level box distance to the plane where the carriage fade ends. */
    public static final double CARRIAGE_FADE_END = 3.00;

    /** Distance from the crossed plane after which the burst may fire again. */
    public static final double BURST_REARM = 6.0;
    /** Burst intensity time constant (~0.26 s to 5% of the peak). */
    public static final double BURST_TAU = 0.085;
    /** Crossing flash time constant (~0.36 s to 5% of the peak). */
    public static final double FLASH_TAU = 0.12;
    /** Fog colour / distance blend time constant. */
    public static final double FOG_TAU = 0.75;

    /** Critically damped angular frequency, 1/s. */
    private static final double SPRING_OMEGA = 9.0;
    /** Longest step the spring or a blend may take; longer frames are treated as this. */
    private static final double MAX_STEP_SECONDS = 0.25;

    private double openFactor;
    private double velocity;
    private boolean latched;

    private double burstIntensity;
    private double flashIntensity;
    private boolean burstArmed;
    private boolean hasCamera;
    private double lastCameraX;

    /** Opens the membrane toward its distance target for one frame; returns the new factor. */
    public double advance(double distanceBlocks, double approachSpeed, double dtSeconds) {
        double distance = Double.isFinite(distanceBlocks) ? Math.max(0.0, distanceBlocks) : FAR_DISTANCE;
        double speed = Double.isFinite(approachSpeed) ? Math.max(0.0, approachSpeed) : 0.0;
        double closeRange = openNear(speed);
        updateLatch(distance, closeRange);
        double goal = latched ? 1.0 : target(distance, closeRange, false);
        double dt = sanitizeDt(dtSeconds);
        if (dt <= 0.0) {
            return this.openFactor;
        }
        // Exact critically damped step: x(t) = goal + (c1 + c2 t) e^(-w t).
        double error = this.openFactor - goal;
        double c2 = this.velocity + SPRING_OMEGA * error;
        double decay = Math.exp(-SPRING_OMEGA * dt);
        this.openFactor = goal + (error + c2 * dt) * decay;
        this.velocity = (c2 - SPRING_OMEGA * (error + c2 * dt)) * decay;
        if (this.openFactor <= 0.0) {
            this.openFactor = 0.0;
            if (this.velocity < 0.0) {
                this.velocity = 0.0;
            }
        } else if (this.openFactor >= 1.0) {
            this.openFactor = 1.0;
            if (this.velocity > 0.0) {
                this.velocity = 0.0;
            }
        }
        return this.openFactor;
    }

    /** Drops the opening state (and the latch) but keeps any in-flight burst. */
    public void resetOpening() {
        this.openFactor = 0.0;
        this.velocity = 0.0;
        this.latched = false;
    }

    public void reset() {
        resetOpening();
        this.burstIntensity = 0.0;
        this.flashIntensity = 0.0;
        this.burstArmed = false;
        this.hasCamera = false;
        this.lastCameraX = 0.0;
    }

    /** Forces the opening factor for the manual {@code /dimblendrift open <value>} override. */
    public void forceOpen(double factor) {
        this.openFactor = clamp(factor, 0.0, 1.0);
        this.velocity = 0.0;
        this.latched = this.openFactor >= 1.0;
    }

    /**
     * Advances the crossing burst/flash decay and fires once per pass through {@code planeX}.
     * Re-arms after the camera is more than {@link #BURST_REARM} blocks away, so a train that
     * straddles the plane for a while glitches exactly once.
     *
     * @return true when this call fired the burst
     */
    public boolean updateBurst(double cameraX, double planeX, double dtSeconds) {
        double dt = sanitizeDt(dtSeconds);
        this.burstIntensity = decay(this.burstIntensity, dt, BURST_TAU);
        this.flashIntensity = decay(this.flashIntensity, dt, FLASH_TAU);
        if (!Double.isFinite(cameraX) || !Double.isFinite(planeX)) {
            this.hasCamera = false;
            return false;
        }
        double offset = cameraX - planeX;
        if (!this.burstArmed && Math.abs(offset) > BURST_REARM) {
            this.burstArmed = true;
        }
        boolean fired = false;
        if (this.hasCamera && this.burstArmed) {
            double previous = this.lastCameraX - planeX;
            boolean crossed = (previous < 0.0 && offset >= 0.0) || (previous > 0.0 && offset <= 0.0);
            if (crossed) {
                this.burstIntensity = 1.0;
                this.flashIntensity = 1.0;
                this.burstArmed = false;
                fired = true;
            }
        }
        this.lastCameraX = cameraX;
        this.hasCamera = true;
        return fired;
    }

    /** Manual burst for the {@code /dimblendrift burst} capture aid. */
    public void triggerBurst() {
        this.burstIntensity = 1.0;
        this.flashIntensity = 1.0;
        this.burstArmed = false;
    }

    public double openFactor() {
        return this.openFactor;
    }

    public double velocity() {
        return this.velocity;
    }

    public boolean latched() {
        return this.latched;
    }

    public double burstIntensity() {
        return this.burstIntensity;
    }

    public double flashIntensity() {
        return this.flashIntensity;
    }

    /** True while the burst is armed, i.e. a crossing would fire. */
    public boolean burstArmed() {
        return this.burstArmed;
    }

    /** Close-range threshold for a given closing speed. */
    public static double openNear(double approachSpeed) {
        double speed = Double.isFinite(approachSpeed) ? Math.max(0.0, approachSpeed) : 0.0;
        return OPEN_NEAR_BASE + clamp(speed * APPROACH_LEAD_SECONDS, 0.0, APPROACH_LEAD_MAX);
    }

    /** Target opening for a distance, before the latch short-circuits it to 1. */
    public static double target(double distanceBlocks, double openNear, boolean latched) {
        if (latched) {
            return 1.0;
        }
        double distance = Math.max(0.0, distanceBlocks);
        if (distance >= FAR_DISTANCE) {
            return 0.0;
        }
        return PREVIEW_TARGET * smoothstep(FAR_DISTANCE, openNear, distance);
    }

    /** {@code smoothstep} that also accepts {@code edge0 > edge1} (descending ramp). */
    public static double smoothstep(double edge0, double edge1, double value) {
        if (edge0 == edge1) {
            return value >= edge0 ? 1.0 : 0.0;
        }
        double t = clamp((value - edge0) / (edge1 - edge0), 0.0, 1.0);
        return t * t * (3.0 - 2.0 * t);
    }

    /** Fraction of the cross-section torn out as one solid core. */
    public static double solidScale(double openFactor) {
        return smoothstep(SOLID_EDGE_LOW, SOLID_EDGE_HIGH, clamp(openFactor, 0.0, 1.0));
    }

    /** Fraction of 1/4-block cells punched out at random before the core tears. */
    public static double glitchDensity(double openFactor) {
        double open = clamp(openFactor, 0.0, 1.0);
        double ramp = clamp(open / GLITCH_RAMP, 0.0, 1.0);
        return GLITCH_PEAK * ramp * ramp * (1.0 - GLITCH_SOLID_SUPPRESS * solidScale(open));
    }

    /**
     * Alpha multiplier for the remaining membrane.
     *
     * <p>Fades to zero when the camera is closer than {@link #CAMERA_FADE_END} to the plane, or
     * when the Sable sub-level the camera rides either straddles the plane ({@code overlaps})
     * or comes within {@link #CARRIAGE_FADE_END} of it. This is the whole "never poke into the
     * carriage" rule: we fade the membrane, we never clip the vehicle's geometry.</p>
     *
     * @param cameraAbsDistToSlab absolute X distance from the camera to the gate plane
     * @param subLevelOverlapsSlab whether the ridden sub-level box spans the plane
     * @param subLevelDistToSlab gap between that box and the plane; anything at or above
     *     {@link #CARRIAGE_FADE_END} (or no sub-level at all) means "no fade"
     * @return 1 = full membrane, 0 = fully faded out
     */
    public static double carriageFade(double cameraAbsDistToSlab, boolean subLevelOverlapsSlab, double subLevelDistToSlab) {
        double camera = clamp((cameraAbsDistToSlab - CAMERA_FADE_START) / (CAMERA_FADE_END - CAMERA_FADE_START), 0.0, 1.0);
        double carriage;
        if (subLevelOverlapsSlab) {
            carriage = 0.0;
        } else {
            carriage = clamp((subLevelDistToSlab - CARRIAGE_FADE_START) / (CARRIAGE_FADE_END - CARRIAGE_FADE_START), 0.0, 1.0);
        }
        double t = Math.min(camera, carriage);
        return t * t * (3.0 - 2.0 * t);
    }

    /** Exponential blend toward {@code target} for one frame; the fog transition step. */
    public static double fogBlendStep(double current, double target, double dtSeconds) {
        if (!Double.isFinite(current)) {
            return target;
        }
        if (!Double.isFinite(target)) {
            return current;
        }
        double dt = sanitizeDt(dtSeconds);
        if (dt <= 0.0) {
            return current;
        }
        double alpha = 1.0 - Math.exp(-dt / FOG_TAU);
        return current + (target - current) * alpha;
    }

    /** Clamps a frame delta into {@code [0, MAX_STEP_SECONDS]}; NaN, inf and negatives become 0. */
    public static double sanitizeDt(double dtSeconds) {
        if (!Double.isFinite(dtSeconds) || dtSeconds <= 0.0) {
            return 0.0;
        }
        return Math.min(dtSeconds, MAX_STEP_SECONDS);
    }

    private void updateLatch(double distance, double openNear) {
        if (this.latched) {
            if (distance > openNear + LATCH_RELEASE) {
                this.latched = false;
            }
        } else if (distance <= openNear) {
            this.latched = true;
        }
    }

    private static double decay(double value, double dt, double tau) {
        if (dt <= 0.0) {
            return value;
        }
        double decayed = value * Math.exp(-dt / tau);
        return decayed < 1.0E-4 ? 0.0 : decayed;
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
