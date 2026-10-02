package dimblend.radio.acoustics;

import java.util.Arrays;
import java.util.function.DoubleSupplier;

/**
 * Propagation delay of a radio's emitted PCM, shared by its direct path and its reflection
 * convolution. Steam Audio times reflection responses from the direct arrival, so this delay only
 * places both in absolute time; how fast it changes is the Doppler shift.
 * <p>
 * Minecraft moves several times faster than people walk, and abruptly, so the physical delay
 * would bend music by a fifth of a semitone at walking pace. The delay follows a scaled copy of
 * the physical one ({@code -Ddimblend.radio.acoustic.doppler}, 0 = no Doppler, 1 = physical)
 * through a critically damped follower: pitch glides in and out instead of stepping with every
 * camera frame, jump or turn of a block's worth of pose. Teleport-sized changes crossfade between
 * the two fixed delays instead of sweeping the read head.
 */
public final class PropagationDelayLine {
    public static final double SPEED_OF_SOUND = 343;
    private static final AcousticTuningProperty DOPPLER =
            new AcousticTuningProperty("dimblend.radio.acoustic.doppler", 0.25f, 1);
    /** Follower stiffness: pitch settles within roughly 0.3 s of a change in speed. */
    private static final double OMEGA = 12;
    /** Physical delay changes beyond this many samples per sample (~86 m/s) are jumps, not motion. */
    private static final double MAX_SLEW = 0.25;
    /** A followed delay this far (seconds) from its target crossfades instead of gliding. */
    private static final double MAX_LAG = 0.1;
    /** Pitch deviation bound while gliding (samples per sample). */
    private static final double MAX_RATE = 0.06;
    private final float[] line;
    private final int rate;
    private final DoubleSupplier scale;
    private int cursor;
    /** Followed delay and its rate, in samples; NaN before the first block after a clear. */
    private double delay = Double.NaN, velocity;
    private double physical = Double.NaN;

    /** A line following the configured Doppler scale. */
    public PropagationDelayLine(int rate) {
        this(rate, PropagationDelayLine::configuredScale);
    }

    public PropagationDelayLine(int rate, DoubleSupplier scale) {
        this.rate = rate;
        this.scale = scale;
        line = new float[rate];
    }

    /** The configured fraction of the physical Doppler shift. */
    public static double configuredScale() { return DOPPLER.value(); }

    /** Replaces {@code samples} with their delayed copy for a source {@code distance} metres away. */
    public void process(float[] samples, double distance) {
        double nextPhysical = Math.min(line.length - 2, distance / SPEED_OF_SOUND * rate);
        double target = Math.min(line.length - 2, nextPhysical * Math.max(0, Math.min(1, scale.getAsDouble())));
        double start = Double.isNaN(delay) ? target : delay;
        boolean jump = !Double.isNaN(delay) && (Math.abs(nextPhysical - physical) > samples.length * MAX_SLEW
                || Math.abs(target - start) > MAX_LAG * rate);
        double end;
        if (Double.isNaN(delay) || jump) {
            end = target;
            velocity = 0;
        } else {
            double omega = OMEGA / rate, step = samples.length;
            velocity += (omega * omega * (target - start) - 2 * omega * velocity) * step;
            velocity = Math.max(-MAX_RATE, Math.min(MAX_RATE, velocity));
            end = Math.max(0, Math.min(line.length - 2, start + velocity * step));
        }
        for (int i = 0; i < samples.length; i++) {
            line[cursor] = samples[i];
            double weight = (i + 1.0) / samples.length;
            samples[i] = (float) (jump
                    ? read(cursor - start) * (1 - weight) + read(cursor - end) * weight
                    : read(cursor - (start + (end - start) * weight)));
            cursor = (cursor + 1) % line.length;
        }
        delay = end;
        physical = nextPhysical;
    }

    /** Samples of already-received input still inside the line. */
    public int pendingSamples() {
        return Double.isNaN(delay) ? 0 : (int) Math.ceil(delay) + 1;
    }

    /** The current delay in samples (0 before the first block). */
    public double delaySamples() {
        return Double.isNaN(delay) ? 0 : delay;
    }

    /** Discards the buffered audio; the next block starts at its target delay, without a glide. */
    public void clear() {
        Arrays.fill(line, 0);
        delay = Double.NaN;
        velocity = 0;
        physical = Double.NaN;
    }

    private double read(double position) {
        int first = (int) Math.floor(position);
        double fraction = position - first;
        float a = line[Math.floorMod(first, line.length)];
        float b = line[Math.floorMod(first + 1, line.length)];
        return a + (b - a) * fraction;
    }
}
