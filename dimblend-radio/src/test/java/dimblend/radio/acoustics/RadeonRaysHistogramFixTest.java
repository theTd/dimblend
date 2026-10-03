package dimblend.radio.acoustics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /** The runtime wiring: a local phonon.dll override is installed with only the bound rewritten. */
    @Test void theInstalledPhononCarriesTheFix() throws Exception {
        Path raw = Files.createTempFile("phonon-raw", ".dll");
        String previous = System.getProperty("dimblend.radio.phononDll");
        System.setProperty("dimblend.radio.phononDll", raw.toString());
        try {
            Files.write(raw, bytes(KERNEL));
            byte[] installed = SteamNativeLibraries.image("phonon.dll");
            assertEquals(KERNEL.replace("if (bin < NUM_BINS)", "if (bin<2*NUM_BINS)"),
                    new String(installed, StandardCharsets.US_ASCII));
        } finally {
            if (previous == null) System.clearProperty("dimblend.radio.phononDll");
            else System.setProperty("dimblend.radio.phononDll", previous);
            Files.deleteIfExists(raw);
        }
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
