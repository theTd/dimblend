package dimblend.spyglass.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OutlineBoxTest {
    @Test
    void centerTreatsTheMaxCoordinateAsInclusive() {
        OutlineBox singleBlock = new OutlineBox(0, 64, 0, 0, 64, 0);
        assertEquals(0.5, singleBlock.centerX());
        assertEquals(0.5, singleBlock.centerZ());

        OutlineBox box = new OutlineBox(-20, -10, 100, 19, 29, 131);
        assertEquals(0.0, box.centerX());
        assertEquals(116.0, box.centerZ());
    }

    @Test
    void centerWidensBeforeSumming() {
        int max = OutlineBox.MAX_COORDINATE;
        OutlineBox corner = new OutlineBox(max, max, max, max, max, max);

        assertEquals(max + 0.5, corner.centerX());
        assertEquals(max + 0.5, corner.centerZ());
    }

    @Test
    void volumeWidensBeforeSubtracting() {
        int max = OutlineBox.MAX_COORDINATE;
        OutlineBox fullSpan = new OutlineBox(-max, 0, 0, max, 0, 0);

        // Span 2^31 + 1 blocks: an int subtraction would wrap negative.
        assertEquals(2.0 * max + 1.0, fullSpan.volume());
    }
}
