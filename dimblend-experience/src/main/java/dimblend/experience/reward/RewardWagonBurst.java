package dimblend.experience.reward;

/**
 * Where the dissolve particle burst lands.
 *
 * <p>The requirement says the monster-death particles play "at the bogey's y-1, radius 5". The
 * first implementation put all twenty at one point with a half-block spread, which reads as a
 * puff at the wagon and nothing five blocks away. This class turns the radius into actual
 * positions.
 *
 * <p><b>Uniform inside the ball, not on the shell.</b> A random direction times a uniform radius
 * would clump near the centre; scaling by the cube root of the draw makes every unit of volume
 * equally likely, so the burst fills the sphere the requirement describes. Kept pure so the
 * distribution can be pinned by a test instead of eyeballed in game.
 */
public final class RewardWagonBurst {

    /** Radius of the burst, in blocks, from the requirement. */
    public static final double RADIUS = 5.0;

    /** How many particles one burst spawns: the vanilla mob-death count. */
    public static final int PARTICLES = 20;

    /** One particle's offset from the burst centre, in blocks. */
    public record Offset(double dx, double dy, double dz) {
        public double length() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * One particle position, drawn uniformly inside the ball of {@link #RADIUS}.
     *
     * @param theta   uniform draw in [0, 1), used as the azimuth
     * @param cosPhi  uniform draw in [0, 1), mapped to the cosine of the polar angle
     * @param radial  uniform draw in [0, 1), cube-rooted for uniform volume
     */
    public static Offset sample(double theta, double cosPhi, double radial) {
        double azimuth = theta * Math.PI * 2.0;
        double cosine = cosPhi * 2.0 - 1.0;
        double sine = Math.sqrt(Math.max(0.0, 1.0 - cosine * cosine));
        double radius = RADIUS * Math.cbrt(Math.max(0.0, Math.min(1.0, radial)));
        return new Offset(
                radius * sine * Math.cos(azimuth),
                radius * cosine,
                radius * sine * Math.sin(azimuth));
    }

    private RewardWagonBurst() {
    }
}