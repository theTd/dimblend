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
}
