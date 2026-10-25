package dimblend.spyglass.client;

import org.joml.Matrix4f;

public final class SpyglassProjection {
    public static final float REQUIRED_FAR_PLANE = 4096.0F;

    private SpyglassProjection() {
    }

    public static Matrix4f withFarPlane(Matrix4f source, float near, float requiredFar) {
        float currentFar = near * (source.m22() - 1.0F) / (source.m22() + 1.0F);
        Matrix4f result = new Matrix4f(source);
        if (!Float.isFinite(currentFar) || currentFar >= requiredFar) {
            return result;
        }
        float denominator = near - requiredFar;
        result.m22((requiredFar + near) / denominator);
        result.m32(2.0F * requiredFar * near / denominator);
        return result;
    }

    /**
     * Replaces the depth range of a frame projection with {@code [near, far]}, keeping screen x/y
     * exactly as the frame drew them.
     *
     * <p>The frame projection of {@code RenderLevelStageEvent} is not a plain perspective matrix: it
     * is {@code P × A}, where {@code P} is {@code perspective(fov, aspect, 0.05, depthFar)} and
     * {@code A} is the affine view-bobbing / hurt-bobbing / nausea transform multiplied onto it
     * ({@code GameRenderer.java:1234-1254}). Rewriting only {@code m22}/{@code m32} would leave
     * {@code A}'s contribution to the depth row unscaled. Instead the depth row is rebuilt from the
     * {@code w} row, which only {@code A} determines:
     *
     * <ul>
     *   <li>{@code A} is affine, so row 3 of {@code P × A} is {@code -row2(A)} and row 2 is
     *       {@code c·row2(A) + d·(0, 0, 0, 1)}, i.e. {@code row2 = -c·row3 + (0, 0, 0, d)}, where
     *       {@code c = (f + n) / (n - f)} and {@code d = 2fn / (n - f)} are {@code P}'s depth terms;</li>
     *   <li>{@code perspective(fov, aspect, near, far) × A} therefore has the same rows 0, 1 and 3 and
     *       {@code row2' = -c'·row3 + (0, 0, 0, d')} with the new {@code c', d'}.</li>
     * </ul>
     *
     * This is {@code P' P⁻¹ (P × A)} written without the large intermediate factors of
     * {@code P' P⁻¹} (they cancel catastrophically in float for near 0.05 → 100). A matrix whose
     * {@code w} row has no perspective divide, or whose depth row does not have the
     * {@code -c·row3 + d} form, is copied unchanged.
     *
     * @param dest receives the result; may be reused across frames (no allocation)
     * @return {@code dest}
     */
    public static Matrix4f withDepthRange(Matrix4f source, float near, float far, Matrix4f dest) {
        // JOML names elements mCR (column, row): row 2 is (m02, m12, m22, m32), row 3 is (m03, m13, m23, m33).
        float z0 = source.m02();
        float z1 = source.m12();
        float z2 = source.m22();
        float z3 = source.m32();
        float w0 = source.m03();
        float w1 = source.m13();
        float w2 = source.m23();
        float w3 = source.m33();

        // Recover c from the component where row 3 is largest: row2[k] = -c * row3[k] for k < 3.
        float wk = w0;
        float zk = z0;
        if (Math.abs(w1) > Math.abs(wk)) {
            wk = w1;
            zk = z1;
        }
        if (Math.abs(w2) > Math.abs(wk)) {
            wk = w2;
            zk = z2;
        }
        double c = -(double) zk / wk;
        double d = z3 + c * w3;
        if (Math.abs(wk) < 1.0E-6F || !Double.isFinite(c) || !Double.isFinite(d) || Math.abs(d) < 1.0E-12
                || !(far > near) || !(near > 0.0F)) {
            return dest.set(source);
        }

        double newC = ((double) far + near) / ((double) near - far);
        double newD = 2.0 * far * near / ((double) near - far);
        // 16-argument set (column-major): recomputes JOML's matrix property flags for the new depth row.
        return dest.set(
                source.m00(), source.m01(), (float) (-newC * w0), w0,
                source.m10(), source.m11(), (float) (-newC * w1), w1,
                source.m20(), source.m21(), (float) (-newC * w2), w2,
                source.m30(), source.m31(), (float) (-newC * w3 + newD), w3);
    }
}
