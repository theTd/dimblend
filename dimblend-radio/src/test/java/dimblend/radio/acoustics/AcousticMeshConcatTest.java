package dimblend.radio.acoustics;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Terrain and structures go to the GPU as one mesh: the second part's indices follow the first's vertices. */
class AcousticMeshConcatTest {
    private static final Vec3 ORIGIN = new Vec3(16, 0, -32);

    @Test void secondMeshIndicesAreOffsetPastTheFirstMeshVertices() {
        var first = new AcousticMesh.Data(new float[] {0,0,0, 1,0,0, 0,1,0}, new int[] {0,1,2}, new int[] {4}, ORIGIN);
        var second = new AcousticMesh.Data(new float[] {5,0,0, 6,0,0, 5,1,0, 6,1,0},
                new int[] {0,1,2, 1,3,2}, new int[] {1, 2}, ORIGIN);
        var joined = AcousticMesh.Data.concat(first, second);
        assertArrayEquals(new float[] {0,0,0, 1,0,0, 0,1,0, 5,0,0, 6,0,0, 5,1,0, 6,1,0}, joined.vertices());
        assertArrayEquals(new int[] {0,1,2, 3,4,5, 4,6,5}, joined.triangles());
        assertArrayEquals(new int[] {4, 1, 2}, joined.materials(), "each triangle keeps its own material");
        assertEquals(ORIGIN, joined.origin());
    }

    @Test void anEmptyOrMissingPartLeavesTheOtherAsIs() {
        var mesh = new AcousticMesh.Data(new float[] {0,0,0, 1,0,0, 0,1,0}, new int[] {0,1,2}, new int[] {4}, ORIGIN);
        var empty = AcousticMesh.Data.empty(ORIGIN);
        assertSame(mesh, AcousticMesh.Data.concat(mesh, null));
        assertSame(mesh, AcousticMesh.Data.concat(mesh, empty));
        assertSame(mesh, AcousticMesh.Data.concat(empty, mesh));
        assertSame(empty, AcousticMesh.Data.concat(empty, null));
    }

    @Test void partsMustShareOneOrigin() {
        var mesh = new AcousticMesh.Data(new float[] {0,0,0, 1,0,0, 0,1,0}, new int[] {0,1,2}, new int[] {4}, ORIGIN);
        var elsewhere = new AcousticMesh.Data(mesh.vertices(), mesh.triangles(), mesh.materials(), Vec3.ZERO);
        assertThrows(IllegalArgumentException.class, () -> AcousticMesh.Data.concat(mesh, elsewhere));
    }
}
