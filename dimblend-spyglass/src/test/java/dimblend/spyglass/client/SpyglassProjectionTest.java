package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    @Test
    void depthRangeOfAPlainPerspectiveEqualsAFreshPerspective() {
        Matrix4f frame = new Matrix4f().perspective(FOV, ASPECT, 0.05F, 512.0F);

        Matrix4f result = SpyglassProjection.withDepthRange(frame, 100.0F, 5000.0F, new Matrix4f());

        assertMatrixClose(new Matrix4f().perspective(FOV, ASPECT, 100.0F, 5000.0F), result);
    }

    @Test
    void depthRangeKeepsViewBobbingExactly() {
        // GameRenderer.renderLevel: projection = perspective(...) × bobbing pose (translate + rotations).
        Matrix4f bobbing = new Matrix4f()
                .translate(0.031F, -0.047F, 0.0F)
                .rotateZ((float) Math.toRadians(1.3))
                .rotateX((float) Math.toRadians(2.1));
        Matrix4f frame = new Matrix4f().perspective(FOV, ASPECT, 0.05F, 512.0F).mul(bobbing);

        Matrix4f result = SpyglassProjection.withDepthRange(frame, 64.0F, 4160.0F, new Matrix4f());

        Matrix4f expected = new Matrix4f().perspective(FOV, ASPECT, 64.0F, 4160.0F).mul(bobbing);
        assertMatrixClose(expected, result);
        // Screen x/y rows and the w row are untouched: the image does not move, only depth changes.
        assertEquals(frame.m00(), result.m00());
        assertEquals(frame.m10(), result.m10());
        assertEquals(frame.m21(), result.m21());
        assertEquals(frame.m31(), result.m31());
        assertEquals(frame.m03(), result.m03());
        assertEquals(frame.m23(), result.m23());
    }

    @Test
    void depthRangeKeepsTheNauseaScaleExactly() {
        // The nausea effect rotates/scales the projection further (GameRenderer.java:1243-1253).
        Matrix4f nausea = new Matrix4f()
                .rotate(0.7F, 0.0F, (float) Math.sqrt(0.5), (float) Math.sqrt(0.5))
                .scale(1.0F / 0.8F, 1.0F, 1.0F)
                .rotate(-0.7F, 0.0F, (float) Math.sqrt(0.5), (float) Math.sqrt(0.5));
        Matrix4f frame = new Matrix4f().perspective(FOV, ASPECT, 0.05F, 512.0F).mul(nausea);

        Matrix4f result = SpyglassProjection.withDepthRange(frame, 32.0F, 4096.0F, new Matrix4f());

        assertMatrixClose(new Matrix4f().perspective(FOV, ASPECT, 32.0F, 4096.0F).mul(nausea), result);
    }

    @Test
    void depthRangeMapsTheNewNearAndFarToTheClipBounds() {
        Matrix4f frame = new Matrix4f().perspective(FOV, ASPECT, 0.05F, 512.0F);
        Matrix4f result = SpyglassProjection.withDepthRange(frame, 128.0F, 4096.0F, new Matrix4f());

        assertEquals(-1.0F, result.transformProject(new Vector4f(0, 0, -128.0F, 1)).z(), 1.0E-4F);
        assertEquals(1.0F, result.transformProject(new Vector4f(0, 0, -4096.0F, 1)).z(), 1.0E-4F);
        float middle = result.transformProject(new Vector4f(0, 0, -1000.0F, 1)).z();
        assertTrue(middle > -1.0F && middle < 1.0F);
    }

    @Test
    void depthRangeResolvesNeighbouringSurfacesAtAThousandBlocks() {
        // One step of a 24-bit depth buffer spans ~d² / (near · 2²⁴) blocks at distance d: about 1.2
        // blocks at 1000 with the frame's 0.05 near plane (meshes would z-fight), well under a
        // millimetre with the shape pass's near plane of 128.
        Matrix4f frame = new Matrix4f().perspective(FOV, ASPECT, 0.05F, 4096.0F);
        Matrix4f pass = SpyglassProjection.withDepthRange(frame, 128.0F, 4096.0F, new Matrix4f());

        assertTrue(blocksPerDepthStep(frame, 1000.0) > 1.0, "frame: " + blocksPerDepthStep(frame, 1000.0));
        assertTrue(blocksPerDepthStep(pass, 1000.0) < 0.001, "pass: " + blocksPerDepthStep(pass, 1000.0));
    }

    @Test
    void depthRangeWritesIntoTheGivenMatrix() {
        Matrix4f frame = new Matrix4f().perspective(FOV, ASPECT, 0.05F, 512.0F);
        Matrix4f dest = new Matrix4f();

        assertSame(dest, SpyglassProjection.withDepthRange(frame, 1.0F, 4096.0F, dest));
    }

    @Test
    void depthRangeCopiesAMatrixWithoutPerspectiveDepth() {
        Matrix4f orthographic = new Matrix4f().ortho(-1, 1, -1, 1, 0.05F, 100.0F);

        Matrix4f result = SpyglassProjection.withDepthRange(orthographic, 10.0F, 4096.0F, new Matrix4f());

        assertEquals(orthographic, result);
    }

    /** Distance covered by one 24-bit depth step at {@code distance} straight ahead (double math). */
    private static double blocksPerDepthStep(Matrix4f projection, double distance) {
        double step = 1.0;
        double window = windowDepth(projection, distance);
        double next = windowDepth(projection, distance + step);
        return step / ((next - window) * ((1 << 24) - 1));
    }

    private static double windowDepth(Matrix4f projection, double distance) {
        double z = -distance;
        double clipZ = (double) projection.m22() * z + projection.m32();
        double clipW = (double) projection.m23() * z + projection.m33();
        return (clipZ / clipW + 1.0) * 0.5;
    }

    private static void assertMatrixClose(Matrix4f expected, Matrix4f actual) {
        float[] e = expected.get(new float[16]);
        float[] a = actual.get(new float[16]);
        for (int i = 0; i < 16; i++) {
            float tolerance = 1.0E-5F + Math.abs(e[i]) * 1.0E-4F;
            assertEquals(e[i], a[i], tolerance, "element " + i + "\nexpected " + expected + "\nactual " + actual);
        }
    }
}
