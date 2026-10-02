package dimblend.radio.acoustics;

import dimblend.radio.acoustics.terrain.SectionQuads;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticMeshAppendSectionsTest {
    private static final int BIAS = SectionQuads.OWNER_OFFSET_BIAS;

    private static SectionQuads section(int ox, int oy, int oz, float[] localVertices, byte[] materials,
            byte[] owners) {
        return new SectionQuads(ox, oy, oz, localVertices, materials, owners, 1L);
    }

    /** Owner offsets for a quad owned by (ox+dx, oy+dy, oz+dz). */
    private static byte[] owner(int dx, int dy, int dz) {
        return new byte[] {(byte) (dx + BIAS), (byte) (dy + BIAS), (byte) (dz + BIAS)};
    }

    @Test
    void verticesAreRebasedToMeshOriginWithWorldOffset() {
        // One quad: top face of block (16, 32, 0), stored section-local in section (1, 2, 0).
        float[] local = {0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1};
        AcousticMesh mesh = new AcousticMesh(new Vec3(16, 32, 0));
        mesh.appendSections(List.of(section(16, 32, 0, local, new byte[] {4}, owner(0, 0, 0))),
                new BlockPos(999, 999, 999));
        AcousticMesh.Data data = mesh.data();
        assertEquals(12, data.vertices().length);
        assertEquals(0, data.vertices()[0], 1e-6);
        assertEquals(1, data.vertices()[1], 1e-6);
        assertEquals(0, data.vertices()[2], 1e-6);
        assertArrayEquals(new int[] {0, 1, 2, 0, 2, 3}, data.triangles());
        assertArrayEquals(new int[] {4, 4}, data.materials());
    }

    @Test
    void onlyQuadsOwnedByTheEmitterBlockAreDropped() {
        // Emitter sits at (16, 32, 0). Quad A is the emitter's own side face; quad B is the floor
        // top face at y=32 the emitter stands on — its plane touches the emitter's block box but
        // it belongs to the floor block (16, 31, 0) and must survive, matching the voxel path.
        float[] emitterSide = {0.5f, 0.1f, 0, 0.5f, 0.9f, 0, 0.5f, 0.9f, 0.9f, 0.5f, 0.1f, 0.9f};
        float[] floorTop = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};
        AcousticMesh mesh = new AcousticMesh(new Vec3(16, 32, 0));
        mesh.appendSections(List.of(
                section(16, 32, 0, emitterSide, new byte[] {4}, owner(0, 0, 0)),
                section(16, 32, 0, floorTop, new byte[] {1}, owner(0, -1, 0))),
                new BlockPos(16, 32, 0));
        AcousticMesh.Data data = mesh.data();
        assertEquals(12, data.vertices().length); // only the floor quad survives
        assertArrayEquals(new int[] {1, 1}, data.materials());
        // And with no emitter, both survive.
        AcousticMesh all = new AcousticMesh(new Vec3(16, 32, 0));
        all.appendSections(List.of(
                section(16, 32, 0, emitterSide, new byte[] {4}, owner(0, 0, 0)),
                section(16, 32, 0, floorTop, new byte[] {1}, owner(0, -1, 0))), null);
        assertEquals(24, all.data().vertices().length);
    }

    @Test
    void geometryIsByteStableForIdenticalInput() {
        float[] local = {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1};
        AcousticMesh first = new AcousticMesh(new Vec3(0, 0, 0));
        first.appendSections(List.of(section(0, 0, 0, local, new byte[] {2}, owner(0, 0, 0))), null);
        AcousticMesh second = new AcousticMesh(new Vec3(0, 0, 0));
        second.appendSections(List.of(section(0, 0, 0, local, new byte[] {2}, owner(0, 0, 0))), null);
        assertArrayEquals(first.data().vertices(), second.data().vertices());
    }
}
