package dimblend.radio.acoustics;

import java.util.Arrays;

/**
 * Lets the two ears hear the decoded reflections the way they hear a diffuse field: alike in
 * level and spectrum, different in fine structure. Steam Audio reconstructs a reflection response
 * from one noise sequence shared by all its Ambisonic channels, so the decoded echo reaches both
 * ears nearly identical (interaural cross-correlation about 0.96 in a recorded train scene) and
 * is heard as a wash inside the head.
 * <p>
 * Each ear splits its signal at {@link #CROSSOVER_HZ}: the low band is half the sum of the signal
 * and a first-order all-pass of it, the high band the rest. The low band passes as is, so the
 * bass stays coherent between the ears as it does in a real room; the high band goes through the
 * ear's own chain of Schroeder all-pass filters, whose phase differs between the ears. The bands
 * are complementary in amplitude and in power, so on average over the chain's phase each ear
 * keeps its level at every frequency. The fraction of an ear's power that stays coherent, about
 * {@code fc² / (fc² + f²)}, is about a real diffuse field's interaural coherence: 0.5 at 300 Hz,
 * 0.1 at 900 Hz.
 * <p>
 * One thread at a time; state carries across blocks.
 */
public final class EchoDecorrelator {
    /** Per ear, the all-pass delays in milliseconds; the ears share none of them. */
    private static final double[][] DELAYS_MS = {{1.9, 4.3, 7.1, 10.7}, {2.7, 5.3, 8.3, 12.1}};
    /** Low enough that each stage's ringing (6 dB per delay) is lost in the reverb it shapes. */
    private static final float GAIN = 0.5f;
    /** Where the ears' coherence falls to half. */
    private static final double CROSSOVER_HZ = 300;
    /** The crossover all-pass's coefficient: {@code (c + z^-1) / (1 + c z^-1)}. */
    private final float crossover;
    /** [ear]: the crossover all-pass's previous input and output. */
    private final float[] crossoverIn, crossoverOut;
    /** [ear][stage]: the stage's delay line of its internal signal. */
    private final float[][][] lines;
    private final int[][] positions;

    public EchoDecorrelator(int rate) {
        double warped = Math.tan(Math.PI * CROSSOVER_HZ / rate);
        crossover = (float) ((warped - 1) / (warped + 1));
        crossoverIn = new float[DELAYS_MS.length];
        crossoverOut = new float[DELAYS_MS.length];
        lines = new float[DELAYS_MS.length][][];
        positions = new int[DELAYS_MS.length][];
        for (int ear = 0; ear < DELAYS_MS.length; ear++) {
            lines[ear] = new float[DELAYS_MS[ear].length][];
            positions[ear] = new int[DELAYS_MS[ear].length];
            for (int stage = 0; stage < DELAYS_MS[ear].length; stage++) {
                lines[ear][stage] = new float[Math.max(1, (int) Math.round(DELAYS_MS[ear][stage] * rate / 1000))];
            }
        }
    }

    /** Decorrelates one block of each ear in place. */
    public void process(float[] left, float[] right) {
        process(0, left);
        process(1, right);
    }

    private void process(int ear, float[] samples) {
        float previousIn = crossoverIn[ear], previousOut = crossoverOut[ear];
        float[][] stages = lines[ear];
        int[] at = positions[ear];
        for (int i = 0; i < samples.length; i++) {
            float input = samples[i];
            float shifted = crossover * input + previousIn - crossover * previousOut;
            previousIn = input;
            previousOut = shifted;
            float low = 0.5f * (input + shifted);
            float high = input - low;
            for (int stage = 0; stage < stages.length; stage++) {
                // v[n] = x[n] + g v[n-D];  y[n] = v[n-D] - g v[n]: (z^-D - g) / (1 - g z^-D)
                float[] line = stages[stage];
                int position = at[stage];
                float delayed = line[position];
                float internal = high + GAIN * delayed;
                line[position] = internal;
                at[stage] = position + 1 == line.length ? 0 : position + 1;
                high = delayed - GAIN * internal;
            }
            samples[i] = low + high;
        }
        crossoverIn[ear] = previousIn;
        crossoverOut[ear] = previousOut;
    }

    /** Forgets the filters' history, e.g. when playback restarts out of silence. */
    public void reset() {
        Arrays.fill(crossoverIn, 0);
        Arrays.fill(crossoverOut, 0);
        for (float[][] ear : lines) for (float[] line : ear) Arrays.fill(line, 0);
        for (int[] ear : positions) Arrays.fill(ear, 0);
    }
}
