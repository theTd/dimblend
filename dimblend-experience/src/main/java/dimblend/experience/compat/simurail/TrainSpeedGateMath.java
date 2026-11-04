package dimblend.experience.compat.simurail;

/** Independent speed gates for unattended braking and the configurable brake sound. */
public final class TrainSpeedGateMath {
    /** Emergency braking starts strictly above 4 m/s, independently of the sound setting. */
    public static final double SPEED_THRESHOLD = 4.0D;

    public static boolean isFast(double absSpeed) {
        return absSpeed > SPEED_THRESHOLD;
    }

    /** E3 sound uses its own configurable threshold; E4 release sound is not speed-gated. */
    public static boolean isBrakeAudible(double absSpeed, double speedThreshold) {
        return absSpeed > speedThreshold;
    }

    /** Pitch of the ordinary brake sound, i.e. every application the driver causes. */
    public static final float NORMAL_BRAKE_PITCH = 1.0F;
    /** Pitch of the announcement a deadman (unattended) latch produces: 2026-11-04 user item. */
    public static final float EMERGENCY_BRAKE_PITCH = 0.5F;

    /**
     * The pitch for one brake application.
     *
     * <p>The requirement of 2026-11-04 lowers "急刹播报" to 0.5 — the sound the train makes when
     * the unattended latch stops it. It names the emergency application, not braking in general, so
     * a driver's own brake keeps its pitch; only the latch's announcement is pitched down.
     */
    public static float brakePitch(boolean emergencyLatched) {
        return emergencyLatched ? EMERGENCY_BRAKE_PITCH : NORMAL_BRAKE_PITCH;
    }

    private TrainSpeedGateMath() {
    }
}
