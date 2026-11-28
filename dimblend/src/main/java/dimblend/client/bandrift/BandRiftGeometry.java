package dimblend.client.bandrift;

import dimblend.worldgen.OakTrackCorridor;

/**
 * Octagonal vault cross-section mesh for the band-rift membrane.
 *
 * <p>The cross-section is {@link OakTrackCorridor#inVault}: a blocky octagon spanning
 * {@code dy = VAULT_FLOOR_DY..VAULT_APEX_DY} with per-row half widths 3/5/6/7/8/8/9…
 * mirrored top to bottom. Gate columns fill exactly those cells (plus the roadbed row), so the
 * membrane silhouette is built from the same predicate instead of a copied tier table. It is the
 * silhouette of the cells <em>plus a half-cell margin</em>: gate pixels are squares, and the
 * membrane has to cover their outer halves too or the starfield would leave a hairline of
 * background along every edge. The margin that spills into the surrounding rock is hidden by
 * that rock.</p>
 *
 * <p>Topology: one closed outline (up the {@code +z} edge, then down the mirrored {@code -z}
 * edge), {@link #RADIAL_RINGS} copies of it scaled toward the plane centre, and a triangle fan
 * at the centre. Scaling a <em>star-shaped</em> polygon toward its centre keeps every ring inside
 * the previous one — the blocky octagon is not convex, but it is star-shaped from the plane
 * centre, which is why the rings never fold or overlap. Rings are connected 1:1 so the mesh is
 * watertight.</p>
 *
 * <p>Coordinates are gate-local block space: {@code (yBlocks, zBlocks)} with the origin at the
 * plane centre, {@code zBlocks} along the corridor cross-section and the plane normal left to the
 * vertex shader's bubble bulge. {@code (u, v)} are the same points normalised to {@code [-1, 1]},
 * which is what the fragment shader uses for the radial tear distance.</p>
 */
public final class BandRiftGeometry {
    /** Concentric outline rings; the innermost one fans into a single centre vertex. */
    public static final int RADIAL_RINGS = 5;

    private BandRiftGeometry() {
    }

    /**
     * Inclusive half width of the vault row {@code dy}, or {@code -1} when the octagon has no row
     * there. Derived from {@link OakTrackCorridor#inVault}, never from a local table.
     */
    public static int halfWidth(int dy) {
        for (int dz = OakTrackCorridor.VAULT_RADIUS; dz >= 0; dz--) {
            if (OakTrackCorridor.inVault(dz, dy)) {
                return dz;
            }
        }
        return -1;
    }

    /** Per-row half widths for {@code minDy..maxDy}, index 0 = {@code minDy}; {@code -1} = no row. */
    public static int[] profileHalfWidths(int minDy, int maxDy) {
        int rows = Math.max(0, maxDy - minDy + 1);
        int[] widths = new int[rows];
        for (int i = 0; i < rows; i++) {
            widths[i] = halfWidth(minDy + i);
        }
        return widths;
    }

    /** Plane centre height in blocks above {@code TRACK_Y} for a {@code minDy..maxDy} slice. */
    public static double centerYBlocks(int minDy, int maxDy) {
        return (minDy + maxDy + 1) * 0.5;
    }

    /** Half height of the slice in blocks. */
    public static double halfHeightBlocks(int minDy, int maxDy) {
        return Math.max(0.5, (maxDy - minDy + 1) * 0.5);
    }

    /** Half width of the widest vault row, in blocks, including the half-cell margin. */
    public static double halfWidthBlocks() {
        return OakTrackCorridor.VAULT_RADIUS + 0.5;
    }

    /** Builds the triangle mesh for one gate column slice; returns {@link Mesh#EMPTY} if invalid. */
    public static Mesh build(int minDy, int maxDy) {
        if (maxDy < minDy) {
            return Mesh.EMPTY;
        }
        int[] widths = profileHalfWidths(minDy, maxDy);
        for (int width : widths) {
            if (width < 0) {
                return Mesh.EMPTY;
            }
        }
        double centerY = centerYBlocks(minDy, maxDy);
        double halfHeight = halfHeightBlocks(minDy, maxDy);
        double halfWidth = halfWidthBlocks();
        float[] outline = outline(widths, minDy, centerY);
        int points = outline.length / 2;

        int triangles = points + points * (RADIAL_RINGS - 1) * 2;
        int vertices = triangles * 3;
        float[] positions = new float[vertices * 3];
        float[] uvs = new float[vertices * 2];
        int at = 0;

        // Innermost fan: the plane centre to ring 1.
        float[] ring = scaled(outline, 1.0 / RADIAL_RINGS);
        for (int i = 0; i < points; i++) {
            int next = (i + 1) % points;
            at = vertex(positions, uvs, at, 0.0F, 0.0F, halfWidth, halfHeight);
            at = vertex(positions, uvs, at, ring[i * 2], ring[i * 2 + 1], halfWidth, halfHeight);
            at = vertex(positions, uvs, at, ring[next * 2], ring[next * 2 + 1], halfWidth, halfHeight);
        }

        // Ring-to-ring strips.
        for (int ringIndex = 1; ringIndex < RADIAL_RINGS; ringIndex++) {
            float[] inner = scaled(outline, (double) ringIndex / RADIAL_RINGS);
            float[] outer = scaled(outline, (double) (ringIndex + 1) / RADIAL_RINGS);
            for (int i = 0; i < points; i++) {
                int next = (i + 1) % points;
                float innerY = inner[i * 2];
                float innerZ = inner[i * 2 + 1];
                float outerY = outer[i * 2];
                float outerZ = outer[i * 2 + 1];
                float nextInnerY = inner[next * 2];
                float nextInnerZ = inner[next * 2 + 1];
                float nextOuterY = outer[next * 2];
                float nextOuterZ = outer[next * 2 + 1];
                at = vertex(positions, uvs, at, innerY, innerZ, halfWidth, halfHeight);
                at = vertex(positions, uvs, at, outerY, outerZ, halfWidth, halfHeight);
                at = vertex(positions, uvs, at, nextOuterY, nextOuterZ, halfWidth, halfHeight);
                at = vertex(positions, uvs, at, innerY, innerZ, halfWidth, halfHeight);
                at = vertex(positions, uvs, at, nextOuterY, nextOuterZ, halfWidth, halfHeight);
                at = vertex(positions, uvs, at, nextInnerY, nextInnerZ, halfWidth, halfHeight);
            }
        }
        return new Mesh(positions, uvs, at);
    }

    private static int vertex(float[] positions, float[] uvs, int index,
                              float yBlocks, float zBlocks, double halfWidth, double halfHeight) {
        int p = index * 3;
        positions[p] = 0.0F;
        positions[p + 1] = yBlocks;
        positions[p + 2] = zBlocks;
        int t = index * 2;
        uvs[t] = (float) (zBlocks / halfWidth);
        uvs[t + 1] = (float) (yBlocks / halfHeight);
        return index + 1;
    }

    /** Closed outline in {@code (y, z)} pairs: {@code +z} edge bottom to top, then {@code -z} top to bottom. */
    private static float[] outline(int[] widths, int minDy, double centerY) {
        int rows = widths.length;
        float[] result = new float[(rows * 2 + 4) * 2];
        double bottomY = minDy - centerY;
        double topY = minDy + rows - centerY;
        int at = 0;
        at = point(result, at, bottomY, widths[0] + 0.5);
        for (int i = 0; i < rows; i++) {
            at = point(result, at, minDy + i + 0.5 - centerY, widths[i] + 0.5);
        }
        at = point(result, at, topY, widths[rows - 1] + 0.5);
        at = point(result, at, topY, -(widths[rows - 1] + 0.5));
        for (int i = rows - 1; i >= 0; i--) {
            at = point(result, at, minDy + i + 0.5 - centerY, -(widths[i] + 0.5));
        }
        point(result, at, bottomY, -(widths[0] + 0.5));
        return result;
    }

    private static int point(float[] result, int index, double y, double z) {
        result[index * 2] = (float) y;
        result[index * 2 + 1] = (float) z;
        return index + 1;
    }

    private static float[] scaled(float[] outline, double factor) {
        float[] result = new float[outline.length];
        for (int i = 0; i < outline.length; i++) {
            result[i] = (float) (outline[i] * factor);
        }
        return result;
    }

    /** One vertex in gate-local block space plus its normalised plane coordinates. */
    public record Vertex(float yBlocks, float zBlocks, float u, float v) {
    }

    /** Triangle-list mesh: {@link #positions()} is {@code x,y,z} per vertex (x always 0). */
    public record Mesh(float[] positions, float[] uvs, int vertexCount) {
        public static final Mesh EMPTY = new Mesh(new float[0], new float[0], 0);

        public boolean isEmpty() {
            return this.vertexCount == 0;
        }

        public int triangleCount() {
            return this.vertexCount / 3;
        }

        public Vertex vertex(int index) {
            int p = index * 3;
            int t = index * 2;
            return new Vertex(this.positions[p + 1], this.positions[p + 2], this.uvs[t], this.uvs[t + 1]);
        }
    }
}
