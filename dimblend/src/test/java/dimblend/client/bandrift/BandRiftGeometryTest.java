package dimblend.client.bandrift;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.client.bandrift.BandRiftGeometry.Mesh;
import dimblend.client.bandrift.BandRiftGeometry.Vertex;
import dimblend.worldgen.OakTrackCorridor;
import org.junit.jupiter.api.Test;

/**
 * Geometry contracts for the vault cross-section mesh: it must cover exactly the cells the gate
 * column fills, stay inside the octagon silhouette, survive truncation at a per-band carve ceiling,
 * and contain no fold-over or zero-area triangles (the strip builder relies on the outline being
 * star-shaped from the plane centre).
 */
class BandRiftGeometryTest {
    private static final int MIN_DY = OakTrackCorridor.VAULT_FLOOR_DY;
    private static final int MAX_DY = OakTrackCorridor.VAULT_APEX_DY;

    @Test
    void profileMatchesTheVaultPredicate() {
        int[] widths = BandRiftGeometry.profileHalfWidths(MIN_DY, MAX_DY);
        assertArrayEquals(new int[]{3, 5, 6, 7, 8, 8, 9, 9, 9, 9, 9, 9, 9, 8, 8, 7, 6, 5, 3}, widths,
                "3/5/6/7/8/8/9... mirrored top to bottom");

        for (int dy = MIN_DY; dy <= MAX_DY; dy++) {
            int expected = -1;
            for (int dz = -OakTrackCorridor.VAULT_RADIUS; dz <= OakTrackCorridor.VAULT_RADIUS; dz++) {
                if (OakTrackCorridor.inVault(dz, dy)) {
                    expected = Math.max(expected, Math.abs(dz));
                }
            }
            assertEquals(expected, BandRiftGeometry.halfWidth(dy), "dy=" + dy);
        }
        assertEquals(-1, BandRiftGeometry.halfWidth(MAX_DY + 1));
        assertEquals(-1, BandRiftGeometry.halfWidth(MIN_DY - 1));
    }

    @Test
    void gateColumnIsStill293Cells() {
        assertEquals(293, gateCells(MIN_DY, MAX_DY),
                "the performance baseline: 293 opaque gateway cubes per boundary column");
    }

    @Test
    void outerRingSitsOnTheGateCellSilhouette() {
        Mesh mesh = BandRiftGeometry.build(MIN_DY, MAX_DY);
        assertFalse(mesh.isEmpty());
        double centerY = BandRiftGeometry.centerYBlocks(MIN_DY, MAX_DY);

        for (int dy = MIN_DY; dy <= MAX_DY; dy++) {
            double rowY = dy + 0.5 - centerY;
            double widest = 0.0;
            for (int i = 0; i < mesh.vertexCount(); i++) {
                Vertex vertex = mesh.vertex(i);
                if (Math.abs(vertex.yBlocks() - rowY) < 1.0E-4) {
                    widest = Math.max(widest, Math.abs(vertex.zBlocks()));
                }
            }
            assertEquals(BandRiftGeometry.halfWidth(dy) + 0.5, widest, 1.0E-4,
                    "row dy=" + dy + " must reach the outer half of the edge gate cell");
        }

        double maxZ = 0.0;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            maxZ = Math.max(maxZ, Math.abs(mesh.vertex(i).zBlocks()));
        }
        assertEquals(BandRiftGeometry.halfWidthBlocks(), maxZ, 1.0E-4);
    }

    @Test
    void truncationStopsExactlyAtTheCarveCeiling() {
        int ceiling = 5;
        Mesh mesh = BandRiftGeometry.build(MIN_DY, ceiling);
        assertFalse(mesh.isEmpty());
        double centerY = BandRiftGeometry.centerYBlocks(MIN_DY, ceiling);
        double halfHeight = BandRiftGeometry.halfHeightBlocks(MIN_DY, ceiling);

        double lowest = Double.MAX_VALUE;
        double highest = -Double.MAX_VALUE;
        for (int i = 0; i < mesh.vertexCount(); i++) {
            Vertex vertex = mesh.vertex(i);
            lowest = Math.min(lowest, vertex.yBlocks());
            highest = Math.max(highest, vertex.yBlocks());
        }
        assertEquals(MIN_DY - centerY, lowest, 1.0E-4);
        assertEquals(ceiling + 1 - centerY, highest, 1.0E-4);
        assertEquals(halfHeight, Math.max(Math.abs(lowest), Math.abs(highest)), 1.0E-4);
        assertEquals(2.0 * halfHeight, highest - lowest, 1.0E-4);
    }

    @Test
    void noTriangleIsDegenerate() {
        assertHealthy(BandRiftGeometry.build(MIN_DY, MAX_DY));
        assertHealthy(BandRiftGeometry.build(MIN_DY, 0));
        assertHealthy(BandRiftGeometry.build(MIN_DY, MIN_DY));
        assertHealthy(BandRiftGeometry.build(0, 0));
    }

    @Test
    void uvMatchesBlockCoordinates() {
        Mesh mesh = BandRiftGeometry.build(MIN_DY, MAX_DY);
        double halfWidth = BandRiftGeometry.halfWidthBlocks();
        double halfHeight = BandRiftGeometry.halfHeightBlocks(MIN_DY, MAX_DY);
        for (int i = 0; i < mesh.vertexCount(); i++) {
            Vertex vertex = mesh.vertex(i);
            assertEquals(vertex.zBlocks() / halfWidth, vertex.u(), 1.0E-5, "u at vertex " + i);
            assertEquals(vertex.yBlocks() / halfHeight, vertex.v(), 1.0E-5, "v at vertex " + i);
            assertTrue(Math.abs(vertex.u()) <= 1.0 + 1.0E-5, "u out of range: " + vertex.u());
            assertTrue(Math.abs(vertex.v()) <= 1.0 + 1.0E-5, "v out of range: " + vertex.v());
        }
    }

    @Test
    void rowsOutsideTheOctagonProduceNoMesh() {
        assertTrue(BandRiftGeometry.build(0, -1).isEmpty());
        assertTrue(BandRiftGeometry.build(MAX_DY + 1, MAX_DY + 1).isEmpty());
        assertTrue(BandRiftGeometry.build(MIN_DY - 1, MIN_DY - 1).isEmpty());
    }

    private static void assertHealthy(Mesh mesh) {
        assertFalse(mesh.isEmpty());
        assertEquals(0, mesh.vertexCount() % 3, "triangle list needs a multiple of three vertices");
        assertTrue(mesh.triangleCount() > 0);
        for (int i = 0; i < mesh.vertexCount(); i += 3) {
            Vertex a = mesh.vertex(i);
            Vertex b = mesh.vertex(i + 1);
            Vertex c = mesh.vertex(i + 2);
            double doubledArea = Math.abs((b.yBlocks() - a.yBlocks()) * (c.zBlocks() - a.zBlocks())
                    - (b.zBlocks() - a.zBlocks()) * (c.yBlocks() - a.yBlocks()));
            assertTrue(doubledArea > 1.0E-6, "degenerate triangle at vertex " + i + ", area " + doubledArea);
        }
    }

    private static int gateCells(int minDy, int maxDy) {
        int cells = 0;
        for (int dy = minDy; dy <= maxDy; dy++) {
            for (int dz = -OakTrackCorridor.VAULT_RADIUS; dz <= OakTrackCorridor.VAULT_RADIUS; dz++) {
                if (OakTrackCorridor.inVault(dz, dy)) {
                    cells++;
                }
            }
        }
        return cells;
    }
}
