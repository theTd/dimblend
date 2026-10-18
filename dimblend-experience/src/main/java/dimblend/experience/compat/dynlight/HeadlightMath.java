package dimblend.experience.compat.dynlight;

import org.joml.Quaternionf;
import org.joml.Vector3dc;

/** Headlight selection, brightness and orientation, independent of the client renderer. */
public final class HeadlightMath {
    private HeadlightMath() {}

    public static boolean matches(String namespace, String path) {
        return namespace.equals("dndecor") && path.endsWith("_frontlight");
    }

    public static float brightness(boolean enabled, int emission, int cap) {
        return enabled ? Math.min(emission, cap) * (4.0F / 15.0F) : 0;
    }

    public static void worldToLight(Vector3dc direction, Quaternionf rotation) {
        // JOML's antiparallel branch can return a very short, unnormalised axis
        // when Sable slightly tilts a north-facing lamp. Veil needs a unit rotation.
        rotation.rotationTo((float) direction.x(), (float) direction.y(), (float) direction.z(), 0, 0, 1).normalize();
    }
}
