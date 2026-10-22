package dimblend.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BandLaneNamesTest {

    @Test
    void knownLanesMapToChineseNames() {
        assertEquals("地表", BandLaneNames.display("surface"));
        assertEquals("地下", BandLaneNames.display("underground"));
        assertEquals("下界", BandLaneNames.display("nether"));
        assertEquals("天域", BandLaneNames.display("aether"));
        assertEquals("暮色", BandLaneNames.display("twilight"));
        assertEquals("星光", BandLaneNames.display("starlight"));
        assertEquals("末地", BandLaneNames.display("end"));
        assertEquals("深暗", BandLaneNames.display("deeperdarker"));
        assertEquals("虚空", BandLaneNames.display("voidscape"));
    }

    @Test
    void unknownPlaceholderAndEmptySentinelAreUnknown() {
        assertEquals("未知", BandLaneNames.display("unknown"));
        assertEquals("未知", BandLaneNames.display(""));
    }

    @Test
    void unrecognizedModLanesFallBack() {
        assertEquals("异界", BandLaneNames.display("someothermod"));
        assertEquals("异界", BandLaneNames.display("mod"));
    }
}
