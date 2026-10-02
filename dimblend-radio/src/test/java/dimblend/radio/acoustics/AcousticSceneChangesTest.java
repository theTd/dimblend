package dimblend.radio.acoustics;

import dimblend.radio.acoustics.terrain.SectionGeometryCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticSceneChangesTest {
    @AfterEach void reset() { AcousticSceneChanges.reset(); SectionGeometryCache.clear(); }

    @Test void watchedBlockEditsWakeTheNextFrameInsteadOfWaitingForTheHalfSecondPoll() {
        long generation=AcousticSceneChanges.beginCapture();
        AcousticSceneChanges.observe(generation,17);
        long revision=AcousticSceneChanges.revision(), capture=1_000_000_000L;
        assertFalse(AcousticSceneChanges.needsCapture(false,revision,capture,capture+16_000_000));
        AcousticSceneChanges.paletteChanged(generation,17);
        assertTrue(AcousticSceneChanges.needsCapture(false,revision,capture,capture+16_000_000));
        assertTrue(SectionGeometryCache.needsValidation(17));
        assertFalse(AcousticSceneChanges.needsCapture(false,AcousticSceneChanges.revision(),capture,capture+16_000_000));
    }

    @Test void oldOrUnwatchedPalettesDoNotTriggerCaptureStorms() {
        long previous=AcousticSceneChanges.beginCapture();
        AcousticSceneChanges.observe(previous,17);
        AcousticSceneChanges.beginCapture();
        long revision=AcousticSceneChanges.revision();
        AcousticSceneChanges.paletteChanged(previous,17);
        AcousticSceneChanges.paletteChanged(0,19);
        AcousticSceneChanges.geometryChanged(17);
        assertEquals(revision,AcousticSceneChanges.revision());
    }

    @Test void asynchronousMeshArrivalWakesACurrentSnapshotAndFallbackPollingStillWorks() {
        long generation=AcousticSceneChanges.beginCapture();
        AcousticSceneChanges.observe(generation,17);
        long revision=AcousticSceneChanges.revision();
        AcousticSceneChanges.geometryChanged(17);
        assertNotEquals(revision,AcousticSceneChanges.revision());
        assertTrue(AcousticSceneChanges.needsCapture(false,AcousticSceneChanges.revision(),0,500_000_000));
    }
}
