package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

class SpyglassProjectionTest {
    private static final float FOV = (float) Math.toRadians(70);
    private static final float ASPECT = 16.0F / 9.0F;

    @Test
    void keepsThousandBlockGeometryInsideExpandedClipRange() {
        Matrix4f original = new Matrix4f().perspective(FOV, ASPECT, 0.05F, 640.0F);
        Vector4f point = new Vector4f(0, 0, -1024, 1);
        assertTrue(original.transformProject(new Vector4f(point)).z() > 1.0F);

        Matrix4f expanded = SpyglassProjection.withFarPlane(
                original, 0.05F, SpyglassProjection.REQUIRED_FAR_PLANE);
        Vector4f projected = expanded.transformProject(new Vector4f(point));
        float clippedZ = projected.z();

        assertTrue(clippedZ >= -1.0F && clippedZ <= 1.0F);
        assertEquals(original.m00(), expanded.m00());
        assertEquals(original.m11(), expanded.m11());
        assertEquals(original.m23(), expanded.m23());
    }

    @Test
    void doesNotShrinkAnAlreadyLargerFarPlane() {
        Matrix4f original = new Matrix4f().perspective(FOV, ASPECT, 0.05F, 8192.0F);

        Matrix4f result = SpyglassProjection.withFarPlane(original, 0.05F, 4096.0F);

        assertEquals(original, result);
    }
}
