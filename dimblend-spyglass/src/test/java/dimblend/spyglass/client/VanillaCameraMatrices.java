package dimblend.spyglass.client;

import org.joml.Matrix4f;
import org.joml.Quaternionf;

/** Builds the matrices {@code RenderLevelStageEvent} carries, the way vanilla does. */
final class VanillaCameraMatrices {
    /** Vanilla default FOV option. */
    static final float DEFAULT_FOV = 70.0F;
    /** {@code AbstractClientPlayer#getFieldOfViewModifier} returns 0.1 while scoping. */
    static final float SCOPED_FOV = DEFAULT_FOV * 0.1F;

    /**
     * Event model-view: the conjugated camera rotation ({@code GameRenderer.java:1256-1257}), with
     * the camera rotation from {@code Camera#setRotation} ({@code Camera.java:108}).
     * Yaw 0 faces south (+Z), -90 east (+X); negative pitch looks up.
     */
    static Matrix4f modelView(float yRotDegrees, float xRotDegrees) {
        Quaternionf cameraRotation = new Quaternionf().rotationYXZ(
                (float) Math.PI - yRotDegrees * (float) (Math.PI / 180.0),
                -xRotDegrees * (float) (Math.PI / 180.0),
                0.0F);
        return new Matrix4f().rotation(cameraRotation.conjugate(new Quaternionf()));
    }

    /** {@code GameRenderer#getProjectionMatrix}: vertical FOV in degrees, near plane 0.05. */
    static Matrix4f projection(float fovDegrees, float aspect, float far) {
        return new Matrix4f().perspective(fovDegrees * (float) (Math.PI / 180.0), aspect, 0.05F, far);
    }

    private VanillaCameraMatrices() {
    }
}
