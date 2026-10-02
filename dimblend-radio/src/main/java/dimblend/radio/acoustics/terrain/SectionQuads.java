package dimblend.radio.acoustics.terrain;

/**
 * Decoded acoustic geometry of one 16³ render section. Vertices are section-local floats
 * (range roughly [-8, 24), matching Sodium's compact vertex encoding domain), so precision
 * is independent of world coordinates; {@code originX/Y/Z} give the world block corner.
 * {@code materials} holds one 0..4 acoustic material index per quad (12 floats per quad in
 * {@code vertices}). {@code owners} holds the world block that produced each quad's material,
 * section-relative and biased by {@link #OWNER_OFFSET_BIAS} (3 bytes per quad) — it drives
 * emitter-block exclusion with the same block-level semantics as the voxel path.
 * {@code version} changes on every rebuild of the section and feeds the acoustic update gate.
 */
public record SectionQuads(int originX, int originY, int originZ, float[] vertices, byte[] materials,
        byte[] owners, long version) {
    public static final int OWNER_OFFSET_BIAS = 16;

    public int quadCount() {
        return materials.length;
    }

    public int ownerX(int quad) {
        return originX + (owners[quad * 3] & 0xFF) - OWNER_OFFSET_BIAS;
    }

    public int ownerY(int quad) {
        return originY + (owners[quad * 3 + 1] & 0xFF) - OWNER_OFFSET_BIAS;
    }

    public int ownerZ(int quad) {
        return originZ + (owners[quad * 3 + 2] & 0xFF) - OWNER_OFFSET_BIAS;
    }
}
