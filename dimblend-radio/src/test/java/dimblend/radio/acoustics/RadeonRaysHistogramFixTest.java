package dimblend.radio.acoustics;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RadeonRaysHistogramFixTest {
    private static final String KERNEL = "#define NUM_LOCAL_HISTOGRAMS        2                     // copies\n"
            + "        uint bin = convert_uint_sat(floor(time / BIN_DURATION)) * NUM_LOCAL_HISTOGRAMS;\n\n"
            + "        if (bin < NUM_BINS)\n        {\n";

    @Test void theBoundCoversEveryInterleavedBinAndNothingElseChanges() {
        byte[] fixed = RadeonRaysHistogramFix.apply(bytes(KERNEL));
        assertEquals(KERNEL.replace("if (bin < NUM_BINS)", "if (bin<2*NUM_BINS)"), new String(fixed, StandardCharsets.US_ASCII));
        assertArrayEquals(fixed, RadeonRaysHistogramFix.apply(fixed.clone()), "a fixed library stays as it is");
    }

    @Test void anotherKernelIsRefusedRatherThanPatchedBlindly() {
        assertThrows(IllegalStateException.class, () -> RadeonRaysHistogramFix.apply(bytes("no kernel here")));
        assertThrows(IllegalStateException.class, () -> RadeonRaysHistogramFix.apply(bytes(KERNEL.replace("        2 ", "        4 "))),
                "the fixed bound assumes two histogram copies");
        assertThrows(IllegalStateException.class, () -> RadeonRaysHistogramFix.apply(bytes(KERNEL + KERNEL.substring(KERNEL.indexOf('\n')))),
                "an ambiguous match is not patched");
    }

    /** The library actually loaded: the bundled phonon.dll with only the bound rewritten. */
    @Test void theInstalledPhononCarriesTheFix() throws Exception {
        byte[] bundled;
        try (var input = SteamNativeLibraries.class.getResourceAsStream("/native/steamaudio/windows-x64/phonon.dll")) {
            bundled = input.readAllBytes();
        }
        byte[] installed = SteamNativeLibraries.image("phonon.dll");
        assertEquals(bundled.length, installed.length);
        int changed = 0;
        for (int i = 0; i < bundled.length; i++) if (bundled[i] != installed[i]) changed++;
        assertTrue(changed > 0 && changed <= 19, "only the bound differs: " + changed + " bytes");
        String kernel = new String(installed, StandardCharsets.ISO_8859_1);
        assertTrue(kernel.contains("if (bin<2*NUM_BINS)"));
        assertFalse(kernel.contains("if (bin < NUM_BINS)"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
