package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StructureAcousticsTest {
    @Test
    void rotatedPlotHitAndReflectionReturnToWorldSpace() {
        Vec3 plotCenter = new Vec3(20_480_000.5, 80.5, 20_480_000.5);
        Pose3d pose = new Pose3d(new Vector3d(10, 30, 20), new Quaterniond().rotateY(Math.PI / 2),
                new Vector3d(plotCenter.x, plotCenter.y, plotCenter.z), new Vector3d(1, 1, 1));
        AcousticRay local = new AcousticRay(AcousticRay.Kind.HIT, plotCenter.add(3, 0, 0),
                new Vec3(-1, 0, 0), 0.9f);
        AcousticRay world = AcousticRaycaster.projectHit(local, pose);
        Vec3 worldOrigin = pose.transformPosition(plotCenter);
        assertEquals(3, worldOrigin.distanceTo(world.position()), 1e-6);
        assertEquals(0, world.normal().x, 1e-6);
        assertEquals(1, world.normal().z, 1e-6);
        Vec3 incident = world.position().subtract(worldOrigin).normalize();
        assertEquals(-1, incident.dot(world.normal()), 1e-6);
        Vec3 reflected = AcousticRay.reflect(incident, world.normal());
        assertEquals(-1, reflected.dot(incident), 1e-6);
        assertEquals(0, pose.transformPositionInverse(world.position()).distanceTo(local.position()), 1e-6);
    }

    @Test
    void worldBoundsCropRaysEnteringAndStartingInsideAStructure() {
        AABB box = new AABB(10, 0, 0, 20, 10, 10);
        assertArrayEquals(new double[] {0.25, 0.5}, AcousticRaycaster.clipRange(
                new Vec3(0, 5, 5), new Vec3(40, 5, 5), box), 1e-9);
        assertArrayEquals(new double[] {0, 0.2}, AcousticRaycaster.clipRange(
                new Vec3(15, 5, 5), new Vec3(40, 5, 5), box), 1e-9);
        assertNull(AcousticRaycaster.clipRange(new Vec3(0, 15, 5), new Vec3(40, 15, 5), box));
    }
}
