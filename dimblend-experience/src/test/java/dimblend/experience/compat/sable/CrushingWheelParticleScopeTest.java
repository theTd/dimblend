package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CrushingWheelParticleScopeTest {

    @AfterEach
    void tearDown() {
        while (CrushingWheelParticleScope.isActive()) {
            CrushingWheelParticleScope.exit();
        }
    }

    @Test
    void inactiveByDefaultAndBalancesNestedScopes() {
        assertFalse(CrushingWheelParticleScope.isActive());

        CrushingWheelParticleScope.enter();
        assertTrue(CrushingWheelParticleScope.isActive());

        CrushingWheelParticleScope.enter();
        assertTrue(CrushingWheelParticleScope.isActive());

        CrushingWheelParticleScope.exit();
        assertTrue(CrushingWheelParticleScope.isActive());

        CrushingWheelParticleScope.exit();
        assertFalse(CrushingWheelParticleScope.isActive());
    }

    @Test
    void extraExitDoesNotUnderflowSubsequentEnter() {
        CrushingWheelParticleScope.exit();
        assertFalse(CrushingWheelParticleScope.isActive());

        CrushingWheelParticleScope.enter();
        assertTrue(CrushingWheelParticleScope.isActive());

        CrushingWheelParticleScope.exit();
        assertFalse(CrushingWheelParticleScope.isActive());
    }
}
