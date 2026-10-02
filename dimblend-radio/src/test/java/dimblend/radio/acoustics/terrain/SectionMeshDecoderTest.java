package dimblend.radio.acoustics.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionMeshDecoderTest {
    /** Packs one vertex exactly like Sodium's CompactChunkVertex encoder (position only). */
    private static void putVertex(ByteBuffer buffer, float x, float y, float z) {
        int qx = quantize(x), qy = quantize(y), qz = quantize(z);
        buffer.putInt(((qx >>> 10) & 0x3FF) | (((qy >>> 10) & 0x3FF) << 10) | (((qz >>> 10) & 0x3FF) << 20));
        buffer.putInt((qx & 0x3FF) | ((qy & 0x3FF) << 10) | ((qz & 0x3FF) << 20));
        buffer.putInt(0).putInt(0).putInt(0);
    }

    private static int quantize(float local) {
        return ((int) ((8 + local) / 32 * (1 << 20))) & 0xFFFFF;
    }

    private static ByteBuffer quad(float... corners) {
        ByteBuffer buffer = ByteBuffer.allocate(corners.length / 3 * SectionMeshDecoder.STRIDE)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < corners.length; i += 3) {
            putVertex(buffer, corners[i], corners[i + 1], corners[i + 2]);
        }
        return buffer.flip();
    }

    @Test
    void decodesPositionsAndClassifiesWithWorldCenterAndNormal() {
        ByteBuffer buffer = quad(0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
        float[] vertices = new float[12];
        byte[] materials = new byte[1];
        byte[] owners = new byte[3];
        List<double[]> seen = new ArrayList<>();
        int quads = SectionMeshDecoder.decode(32, 64, 16, buffer, (cx, cy, cz, nx, ny, nz, owner) -> {
            seen.add(new double[] {cx, cy, cz, nx, ny, nz});
            owner[0] = 32;
            owner[1] = 63; // one block below the quad plane
            owner[2] = 16;
            return 3;
        }, vertices, materials, owners, 0);
        assertEquals(1, quads);
        assertEquals(3, materials[0]);
        assertArrayEquals(new byte[] {16, 15, 16}, owners); // owner stored origin-relative, biased
        assertEquals(1, seen.size());
        assertEquals(32.5, seen.get(0)[0], 1e-4);
        assertEquals(64.0, seen.get(0)[1], 1e-4);
        assertEquals(16.5, seen.get(0)[2], 1e-4);
        assertEquals(1.0, Math.abs(seen.get(0)[4]), 1e-4); // unit normal, Y axis
        assertEquals(0.0, vertices[0], 1e-4); // section-local coordinates survive
        assertEquals(1.0, vertices[3], 1e-4);
    }

    @Test
    void droppedQuadsAreCompactedAway() {
        ByteBuffer buffer = ByteBuffer.allocate(8 * SectionMeshDecoder.STRIDE).order(ByteOrder.nativeOrder());
        putVertex(buffer, 0, 0, 0); putVertex(buffer, 1, 0, 0); putVertex(buffer, 1, 0, 1); putVertex(buffer, 0, 0, 1);
        putVertex(buffer, 4, 2, 4); putVertex(buffer, 5, 2, 4); putVertex(buffer, 5, 2, 5); putVertex(buffer, 4, 2, 5);
        buffer.flip();
        float[] vertices = new float[24];
        byte[] materials = new byte[2];
        byte[] owners = new byte[6];
        int quads = SectionMeshDecoder.decode(0, 0, 0, buffer,
                (cx, cy, cz, nx, ny, nz, owner) -> {
                    if (cy < 1) {
                        return -1;
                    }
                    owner[0] = 4;
                    owner[1] = 1;
                    owner[2] = 4;
                    return 2;
                }, vertices, materials, owners, 0);
        assertEquals(1, quads);
        assertEquals(2, materials[0]);
        assertArrayEquals(new byte[] {20, 17, 20}, new byte[] {owners[0], owners[1], owners[2]});
        assertEquals(4.0, vertices[0], 1e-4); // the surviving quad moved into the first slot
        assertEquals(2.0, vertices[1], 1e-4);
    }

    @Test
    void smearedQuadsFromQuantizationWraparoundAreDropped() {
        // A vertex beyond the [-8, 24) encoding domain wraps to the opposite side (Sodium's
        // & 0xFFFFF mask), smearing the quad across the section; it must not become a phantom wall.
        ByteBuffer buffer = ByteBuffer.allocate(8 * SectionMeshDecoder.STRIDE).order(ByteOrder.nativeOrder());
        putVertex(buffer, 0, 0, 0); putVertex(buffer, 1, 0, 0); putVertex(buffer, 1, 0, 1); putVertex(buffer, 0, 0, 1);
        putVertex(buffer, 0, 0, 0); putVertex(buffer, 22, 0, 0); putVertex(buffer, 0, -7, 0); putVertex(buffer, 0, 0, 23);
        buffer.flip();
        float[] vertices = new float[24];
        byte[] materials = new byte[2];
        byte[] owners = new byte[6];
        int quads = SectionMeshDecoder.decode(0, 0, 0, buffer,
                (cx, cy, cz, nx, ny, nz, owner) -> {
                    owner[0] = 0; owner[1] = 0; owner[2] = 0;
                    return 2;
                }, vertices, materials, owners, 0);
        assertEquals(1, quads); // only the sane quad survives
        assertEquals(1.0, vertices[3], 1e-4);
    }

    @Test
    void degenerateQuadsAndEmptyBuffersProduceNothing() {
        ByteBuffer degenerate = quad(3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3);
        assertEquals(0, SectionMeshDecoder.decode(0, 0, 0, degenerate,
                (cx, cy, cz, nx, ny, nz, owner) -> 0, new float[12], new byte[1], new byte[3], 0));
        assertEquals(0, SectionMeshDecoder.decode(0, 0, 0, ByteBuffer.allocate(0),
                (cx, cy, cz, nx, ny, nz, owner) -> 0, new float[0], new byte[0], new byte[0], 0));
    }
}
