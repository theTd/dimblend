package dimblend.radio.acoustics;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SteamSimulationTest {
    @Test
    void nativeSimulatorUsesActualOcclusionAndProducesAConvolutionIR() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 3)) {
            var clear = simulation.simulate((from, to) -> AcousticRay.miss(to), Vec3.ZERO, new Vec3(4, 0, 0), 16, 16);
            assertEquals(1, clear.direct.occlusion, 0.001);
            assertEquals(0.25, clear.direct.distance, 0.01);
            var room = new AABB(-8, -8, -8, 8, 8, 8);
            var reflected = simulation.simulate((from, to) -> {
                double[] range = AcousticRaycaster.clipRange(from, to, room);
                if (range == null) return AcousticRay.miss(to);
                double t = room.contains(from) ? range[1] : range[0];
                if (t >= 1) return AcousticRay.miss(to);
                Vec3 point = from.add(to.subtract(from).scale(t));
                Vec3 normal = Math.abs(Math.abs(point.x) - 8) < 1e-4 ? new Vec3(-Math.signum(point.x), 0, 0)
                        : Math.abs(Math.abs(point.y) - 8) < 1e-4 ? new Vec3(0, -Math.signum(point.y), 0)
                        : new Vec3(0, 0, -Math.signum(point.z));
                return new AcousticRay(AcousticRay.Kind.HIT, point, normal, 0.9f);
            }, Vec3.ZERO, new Vec3(4, 0, 0), 32, 64);
            assertNotNull(reflected.reflections.ir);
            assertEquals(4, reflected.reflections.channels);
            var blocked = simulation.simulate((from, to) -> new AcousticRay(AcousticRay.Kind.HIT,
                    from.add(to.subtract(from).scale(0.5)), new Vec3(-1, 0, 0), 0.9f),
                    Vec3.ZERO, new Vec3(4, 0, 0), 16, 0);
            assertEquals(0, blocked.direct.occlusion, 0.001);
            assertTrue(blocked.direct.transmission[2] < blocked.direct.transmission[0]);
        }
    }
}
