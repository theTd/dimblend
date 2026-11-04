package dimblend.experience.reward;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Repairing the site records that the broken sub-level lookup closed by mistake.
 *
 * <p>A dissolve always plays its effects, so a record that says "dissolved" but never played them
 * was closed by the old lookup rather than by an actual dissolve.
 */
class RewardWagonSitesTest {

    @Test
    void aRecordClosedWithoutDissolvingIsReopened() {
        assertTrue(RewardWagonSites.wasRetiredWithoutDissolving(true, false));
    }

    @Test
    void aWagonThatReallyDissolvedStaysClosed() {
        assertFalse(RewardWagonSites.wasRetiredWithoutDissolving(true, true),
                "a dissolve sets effectsPlayed, so this record is honest");
    }

    @Test
    void aWagonThatIsStillTrackedIsNotTouched() {
        assertFalse(RewardWagonSites.wasRetiredWithoutDissolving(false, false));
    }
}
