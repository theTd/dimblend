package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MarkClickDecisionTest {
    @Test
    void aLeftPressOnADrawnMarkWhileScopingSelects() {
        assertTrue(MarkClickDecision.selects(true, true, false, false, false, true, true, true, 0));
    }

    @Test
    void aReleaseNeverSelects() {
        assertFalse(MarkClickDecision.selects(true, false, false, false, false, true, true, true, 0));
    }

    @Test
    void holdingTheButtonDoesNotToggleAgain() {
        assertFalse(MarkClickDecision.selects(true, true, true, false, false, true, true, true, 0));
    }

    @Test
    void anotherButtonNeverSelects() {
        assertFalse(MarkClickDecision.selects(false, true, false, false, false, true, true, true, 0));
    }

    @Test
    void anOpenScreenNeverSelects() {
        assertFalse(MarkClickDecision.selects(true, true, false, true, false, true, true, true, 0));
    }

    @Test
    void aLoadingOverlayNeverSelects() {
        assertFalse(MarkClickDecision.selects(true, true, false, false, true, true, true, true, 0));
    }

    @Test
    void anUngrabbedMouseNeverSelects() {
        assertFalse(MarkClickDecision.selects(true, true, false, false, false, false, true, true, 0));
    }

    @Test
    void notScopingNeverSelects() {
        assertFalse(MarkClickDecision.selects(true, true, false, false, false, true, false, true, 0));
    }

    @Test
    void aStaleFrameNeverSelects() {
        assertFalse(MarkClickDecision.selects(true, true, false, false, false, true, true, false, 0));
    }

    @Test
    void aBlankAreaNeverSelects() {
        assertFalse(MarkClickDecision.selects(true, true, false, false, false, true, true, true, -1));
    }
}
