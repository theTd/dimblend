package dimblend.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class YShiftWindowTest {
    @Test
    void positiveOffsetKeepsMinYAndGrowsHeight() {
        assertEquals(0, YShiftWindow.minY(0, 64));
        assertEquals(320, YShiftWindow.height(0, 256, 64));
    }

    @Test
    void negativeOffsetDropsMinYAndGrowsHeight() {
        assertEquals(-64, YShiftWindow.minY(0, -64));
        assertEquals(320, YShiftWindow.height(0, 256, -64));
    }

    @Test
    void zeroOffsetLeavesTheSourceWindow() {
        assertEquals(0, YShiftWindow.minY(0, 0));
        assertEquals(256, YShiftWindow.height(0, 256, 0));
    }

    @Test
    void windowEdgesSnapToSectionAlignment() {
        assertEquals(-16, YShiftWindow.minY(0, -1));
        assertEquals(272, YShiftWindow.height(0, 256, -1));
        assertEquals(0, YShiftWindow.minY(0, 1));
        assertEquals(272, YShiftWindow.height(0, 256, 1));
    }
}
