package dimblend.radio.acoustics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticSceneChangesTest {
    @AfterEach void reset() { AcousticSceneChanges.reset(); }

    @Test void watchedBlockEditsWakeTheNextFrameInsteadOfWaitingForTheHalfSecondPoll() {
        long generation=AcousticSceneChanges.beginCapture();
        long revision=AcousticSceneChanges.revision(), capture=1_000_000_000L;
        assertFalse(AcousticSceneChanges.needsCapture(false,revision,capture,capture+16_000_000));
        AcousticSceneChanges.paletteChanged(generation);
        assertTrue(AcousticSceneChanges.needsCapture(false,revision,capture,capture+16_000_000));
        assertFalse(AcousticSceneChanges.needsCapture(false,AcousticSceneChanges.revision(),capture,capture+16_000_000));
    }

    @Test void oldOrUnwatchedPalettesDoNotTriggerCaptureStorms() {
        long previous=AcousticSceneChanges.beginCapture();
        AcousticSceneChanges.beginCapture();
        long revision=AcousticSceneChanges.revision();
        AcousticSceneChanges.paletteChanged(previous);
        AcousticSceneChanges.paletteChanged(0);
        assertEquals(revision,AcousticSceneChanges.revision());
    }

    @Test void fallbackPollingStillRecapturesUnchangedScenes() {
        long revision=AcousticSceneChanges.revision();
        assertFalse(AcousticSceneChanges.needsCapture(false,revision,0,499_000_000));
        assertTrue(AcousticSceneChanges.needsCapture(false,revision,0,500_000_000));
        assertTrue(AcousticSceneChanges.needsCapture(true,revision,0,0));
    }
}
