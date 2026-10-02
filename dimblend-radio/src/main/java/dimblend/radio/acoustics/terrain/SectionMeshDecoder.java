package dimblend.radio.acoustics.terrain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Decodes Sodium's CompactChunkVertex buffers (20-byte stride, positions quantized to u20 per
 * axis over the range [-8, 24) section-local) into acoustic quads. Pure math: block-type
 * decisions are delegated to {@link QuadClassifier} so this stays unit-testable and free of
 * Sodium/Minecraft world types. Never retains or frees the source buffer; the caller's copy
 * must happen before Sodium destroys it on the render thread.
 */
public final class SectionMeshDecoder {
    public static final int STRIDE = 20;
    private static final float POSITION_SCALE = 32.0f / (1 << 20);
    private static final float POSITION_ORIGIN = 8.0f;

    @FunctionalInterface
    public interface QuadClassifier {
        /**
         * @param ownerOut receives the world block coordinates that produced the material
         *                 (three ints); contents are only meaningful for non-negative verdicts
         * @return {@link dimblend.radio.acoustics.AcousticMaterials} index, or a negative value to drop the quad
         */
        int classify(double centerX, double centerY, double centerZ,
                double normalX, double normalY, double normalZ, int[] ownerOut);
    }

    /**
     * Decodes every quad in {@code buffer} into {@code verticesOut} (section-local floats,
     * 12 per quad), {@code materialsOut} (one per quad) and {@code ownersOut} (owner block
     * coordinates relative to the section origin, biased by {@link SectionQuads#OWNER_OFFSET_BIAS},
     * three per quad), starting at quad slot {@code quadOffset}. World quad centers and normals
     * are handed to {@code classifier}; dropped quads are compacted away.
     *
     * @return number of quads actually written.
     */
    public static int decode(int originX, int originY, int originZ, ByteBuffer buffer,
            QuadClassifier classifier, float[] verticesOut, byte[] materialsOut, byte[] ownersOut,
            int quadOffset) {
        ByteBuffer bytes = buffer.slice().order(ByteOrder.nativeOrder());
        int quadCount = bytes.remaining() / (STRIDE * 4);
        int written = quadOffset;
        int[] owner = new int[3];
        for (int quad = 0; quad < quadCount; quad++) {
            int base = written * 12;
            for (int corner = 0; corner < 4; corner++) {
                int offset = (quad * 4 + corner) * STRIDE;
                int hi = bytes.getInt(offset);
                int lo = bytes.getInt(offset + 4);
                verticesOut[base + corner * 3] = axis(hi, lo, 0);
                verticesOut[base + corner * 3 + 1] = axis(hi, lo, 10);
                verticesOut[base + corner * 3 + 2] = axis(hi, lo, 20);
            }
            double centerX = 0, centerY = 0, centerZ = 0;
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                float vx = verticesOut[base + corner * 3];
                float vy = verticesOut[base + corner * 3 + 1];
                float vz = verticesOut[base + corner * 3 + 2];
                centerX += vx;
                centerY += vy;
                centerZ += vz;
                minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
                minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
                minZ = Math.min(minZ, vz); maxZ = Math.max(maxZ, vz);
            }
            // Sodium's quantization wraps out-of-domain vertices to the opposite side of the
            // section; the wrapped quads smear across the whole section (phantom walls for
            // acoustics). Real block faces stay within ~1.5 blocks; vanilla model JSON elements
            // can legally reach 3, and the wrap jump is ~32 — 3.5 keeps a wide safety margin.
            if (maxX - minX > 3.5f || maxY - minY > 3.5f || maxZ - minZ > 3.5f) {
                continue;
            }
            centerX = centerX / 4 + originX;
            centerY = centerY / 4 + originY;
            centerZ = centerZ / 4 + originZ;
            boolean first = dimblend.radio.acoustics.AcousticMesh.validTriangle(verticesOut, base, base + 3, base + 6);
            boolean second = dimblend.radio.acoustics.AcousticMesh.validTriangle(verticesOut, base, base + 6, base + 9);
            if (!first && !second) continue;
            int b = base + (first ? 3 : 6), c = base + (first ? 6 : 9);
            double e1x = verticesOut[b] - verticesOut[base];
            double e1y = verticesOut[b + 1] - verticesOut[base + 1];
            double e1z = verticesOut[b + 2] - verticesOut[base + 2];
            double e2x = verticesOut[c] - verticesOut[base];
            double e2y = verticesOut[c + 1] - verticesOut[base + 1];
            double e2z = verticesOut[c + 2] - verticesOut[base + 2];
            double normalX = e1y * e2z - e1z * e2y;
            double normalY = e1z * e2x - e1x * e2z;
            double normalZ = e1x * e2y - e1y * e2x;
            double length = Math.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
            // Near-zero-area quads (length ≈ quad area) produce NaN intersections in the GPU
            // ray tracer; anything below a square millimetre has no acoustic value either.
            if (length < 1e-3) {
                continue;
            }
            int material = classifier.classify(centerX, centerY, centerZ,
                    normalX / length, normalY / length, normalZ / length, owner);
            if (material < 0) {
                continue;
            }
            materialsOut[written] = (byte) material;
            ownersOut[written * 3] = (byte) (owner[0] - originX + SectionQuads.OWNER_OFFSET_BIAS);
            ownersOut[written * 3 + 1] = (byte) (owner[1] - originY + SectionQuads.OWNER_OFFSET_BIAS);
            ownersOut[written * 3 + 2] = (byte) (owner[2] - originZ + SectionQuads.OWNER_OFFSET_BIAS);
            written++;
        }
        return written - quadOffset;
    }

    private static float axis(int hi, int lo, int shift) {
        int quantized = (((hi >>> shift) & 0x3FF) << 10) | ((lo >>> shift) & 0x3FF);
        return quantized * POSITION_SCALE - POSITION_ORIGIN;
    }

    private SectionMeshDecoder() { }
}
