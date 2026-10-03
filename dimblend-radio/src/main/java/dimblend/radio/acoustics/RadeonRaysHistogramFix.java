package dimblend.radio.acoustics;

import java.nio.charset.StandardCharsets;

/**
 * Fixes the GPU reflection kernel of the bundled phonon.dll (Steam Audio 4.8.1), which compiles
 * {@code radeonrays_reflection_simulator.cl} at runtime from source it embeds as text.
 * <p>
 * Its {@code gatherEnergyField} adds each ray's energy to a 10 ms bin of a histogram kept in
 * {@code NUM_LOCAL_HISTOGRAMS} interleaved copies, bin {@code i} of copy {@code j} at
 * {@code NUM_LOCAL_HISTOGRAMS * i + j}, but tests that interleaved index against {@code NUM_BINS}
 * (256). With two copies only bins below 128 pass, so every reflection arriving 1.28 s or more after
 * the direct sound is dropped and the reverb stops dead there, in a closed stone room as well as a
 * small hut. Testing against {@code 2 * NUM_BINS} keeps all 256 bins the host reads back
 * ({@code OpenCLEnergyField::kMaxBins}): 2.56 s. The check is rewritten in place at the same
 * length, so nothing else in the library moves.
 */
final class RadeonRaysHistogramFix {
    /** The interleaved bin index, then the faulty bound. */
    private static final byte[] FAULTY = ascii("* NUM_LOCAL_HISTOGRAMS;\n\n        if (bin < NUM_BINS)");
    private static final byte[] FIXED = ascii("* NUM_LOCAL_HISTOGRAMS;\n\n        if (bin<2*NUM_BINS)");
    /** The fixed bound's 2 is this constant of the same kernel. */
    private static final byte[] COPIES = ascii("#define NUM_LOCAL_HISTOGRAMS        2 ");

    static {
        if (FAULTY.length != FIXED.length) throw new AssertionError("The fix must keep the kernel's length");
    }

    /**
     * Fixes {@code phonon} in place; a library already fixed is left as it is.
     *
     * @throws IllegalStateException when it is not the kernel this fix was written for
     */
    static byte[] apply(byte[] phonon) {
        int faulty = only(phonon, FAULTY), fixed = only(phonon, FIXED), copies = only(phonon, COPIES);
        if (copies < 0 || (faulty < 0) == (fixed < 0)) {
            throw new IllegalStateException("phonon.dll does not embed the Steam Audio 4.8.1 reflection kernel this fix is for");
        }
        if (faulty >= 0) System.arraycopy(FIXED, 0, phonon, faulty, FIXED.length);
        return phonon;
    }

    /** Where {@code pattern} occurs in {@code data} if it does exactly once, else -1. */
    private static int only(byte[] data, byte[] pattern) {
        int found = -1;
        byte first = pattern[0];
        outer:
        for (int i = 0, last = data.length - pattern.length; i <= last; i++) {
            if (data[i] != first) continue;
            for (int k = 1; k < pattern.length; k++) if (data[i + k] != pattern[k]) continue outer;
            if (found >= 0) return -1;
            found = i;
        }
        return found;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private RadeonRaysHistogramFix() { }
}
