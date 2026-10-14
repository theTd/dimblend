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

    private TrainSpeedGateMath() {
    }
}
