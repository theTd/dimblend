package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Terrain and structures are passed apart and uploaded as one mesh; both must occlude, and either may change alone. */
class SteamSceneMeshesTest {
    private static final Vec3 ORIGIN = new Vec3(16, 0, 0);
    private static final AcousticMesh.Data EMPTY = AcousticMesh.Data.empty(ORIGIN);

    /** A wall across x = {@code x} (mesh-relative), between the listener and the source. */
    private static AcousticMesh.Data wall(float x) {
        return new AcousticMesh.Data(new float[] {x,-8,-8, x,8,-8, x,8,8, x,-8,8},
                new int[] {0,1,2,0,2,3}, new int[] {4,4}, ORIGIN);
    }

    @Test void eitherMeshOccludesAndEachIsReplacedOnItsOwn() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        Vec3 listener = ORIGIN, source = ORIGIN.add(4, 0, 0);
        var terrainWall = wall(2);
        try (var simulation = new SteamSimulation(44100, 1, true)) {
            assertEquals(1, simulation.simulateGpu(EMPTY, EMPTY, listener, source, 1, 0).direct.occlusion, 0.001);
            assertEquals(0, simulation.simulateGpu(terrainWall, EMPTY, listener, source, 1, 0).direct.occlusion, 0.001,
                    "terrain occludes");
            assertEquals(0, simulation.simulateGpu(EMPTY, wall(3), listener, source, 1, 0).direct.occlusion, 0.001,
                    "a structure occludes");
            // The train moves out of the way; terrain stays uploaded as it was.
            assertEquals(1, simulation.simulateGpu(EMPTY, wall(6), listener, source, 1, 0).direct.occlusion, 0.001);
            assertEquals(0, simulation.simulateGpu(terrainWall, wall(6), listener, source, 1, 0).direct.occlusion, 0.001);
            assertEquals(0, simulation.simulateGpu(terrainWall, wall(7), listener, source, 1, 0).direct.occlusion, 0.001,
                    "moving the structure keeps the terrain");
            assertEquals(1, simulation.simulateGpu(EMPTY, wall(7), listener, source, 1, 0).direct.occlusion, 0.001);
        }
    }

    @Test void meshesMustShareOneOrigin() {
        assumeTrue(Boolean.getBoolean("dimblend.radio.testAudio"));
        try (var simulation = new SteamSimulation(44100, 1, true)) {
            assertThrows(IllegalArgumentException.class, () -> simulation.simulateGpu(EMPTY,
                    AcousticMesh.Data.empty(Vec3.ZERO), ORIGIN, ORIGIN.add(4, 0, 0), 1, 0));
        }
    }
}
