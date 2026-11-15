package dimblend.experience.compat.camerapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Film arithmetic behind the camera mixins. The ItemStack/Player wrappers cannot run without a
 * bootstrapped registries, so the decisions are pinned on the pure forms the wrappers delegate to.
 */
class CameraFilmRulesTest {
    @Test
    void remainingShotsIsMaxMinusDamage() {
        assertEquals(64, CameraFilmRules.remainingShots(64, 0));
        assertEquals(1, CameraFilmRules.remainingShots(64, 63));
        assertEquals(0, CameraFilmRules.remainingShots(64, 64));
        assertEquals(0, CameraFilmRules.remainingShots(0, 0));
    }

    @Test
    void remainingShotsNeverGoesNegative() {
        assertEquals(0, CameraFilmRules.remainingShots(64, 65));
        assertEquals(64, CameraFilmRules.remainingShots(64, -5), "damage is clamped at zero");
    }

    @Test
    void anEmptyCameraBlocksTheShot() {
        assertFalse(CameraFilmRules.canShoot(0, false));
        assertFalse(CameraFilmRules.canShoot(0, true),
                "with the bypass off, Creative is blocked exactly like Survival");
    }

    @Test
    void aLoadedCameraShootsInBothModes() {
        assertTrue(CameraFilmRules.canShoot(1, false));
        assertTrue(CameraFilmRules.canShoot(1, true));
    }

    @Test
    void creativeOnlyBypassesWhenTheToggleIsOn() {
        assertEquals(
                CameraFilmRules.CREATIVE_BYPASSES_DURABILITY,
                CameraFilmRules.canShoot(0, true),
                "the toggle alone decides the Creative bypass");
    }

    @Test
    void consumingAShotNeverExceedsMaxDamage() {
        assertEquals(1, CameraFilmRules.consumedDamage(64, 0));
        assertEquals(64, CameraFilmRules.consumedDamage(64, 63));
        assertEquals(64, CameraFilmRules.consumedDamage(64, 64), "an empty camera is not over-damaged");
        assertEquals(0, CameraFilmRules.consumedDamage(0, 0));
    }

    @Test
    void externalDamageCannotReachTheBreakingPoint() {
        assertEquals(1, CameraFilmRules.clampedDamage(64, 0, 1));
        assertEquals(63, CameraFilmRules.clampedDamage(64, 0, 100), "vanilla must still see 1 left");
        assertEquals(0, CameraFilmRules.clampedDamage(64, 63, 1), "one point left, no damage passes");
        assertEquals(0, CameraFilmRules.clampedDamage(64, 64, 1), "already empty, no damage passes");
        assertEquals(0, CameraFilmRules.clampedDamage(64, 0, 0));
        assertEquals(0, CameraFilmRules.clampedDamage(64, 0, -3));
        assertEquals(0, CameraFilmRules.clampedDamage(0, 0, 5));
    }
}
