package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AcousticFrameTest {
    /** A structure far out in the plot grid, turned and moved somewhere in the world. */
    private static Pose3d pose(double x, double z, double yaw, double pitch, double scale) {
        return new Pose3d(new Vector3d(x, 70, z), new Quaterniond().rotateY(yaw).rotateX(pitch),
                new Vector3d(20_480_008.5, 64, 20_480_008.5), new Vector3d(scale));
    }

    private static void assertClose(Vec3 expected, Vec3 actual, double tolerance) {
        assertTrue(expected.distanceTo(actual) <= tolerance, "expected " + expected + " but was " + actual);
    }

    @Test void theWorldsFrameChangesNothing() {
        Pose3d train = pose(12, -40, 0.4, 0, 1);
        Vec3 point = new Vec3(3, 4, 5);
        assertSame(point, AcousticFrame.WORLD.toLocal(point));
        assertSame(point, AcousticFrame.WORLD.toWorld(point));
        assertTrue(AcousticUpdateGate.samePose(train, AcousticFrame.WORLD.relative(train)));
        assertTrue(AcousticUpdateGate.samePose(new Pose3d(), AcousticFrame.WORLD.relative(null)));
        assertTrue(AcousticFrame.WORLD.sameAxes(AcousticFrame.WORLD));
    }

    @Test void positionsGoIntoTheStructuresFrameAndBack() {
        var frame = AcousticFrame.of(UUID.randomUUID(), pose(1200, -3400, 0.7, 0.05, 1));
        Vec3 world = new Vec3(1205.25, 71.5, -3398.75);
        Vec3 local = frame.toLocal(world);
        assertTrue(local.x > 20_000_000, "local coordinates are plot coordinates: " + local);
        assertClose(world, frame.toWorld(local), 1e-6);
        Vec3 direction = new Vec3(0, 0, -1);
        Vec3 turned = frame.directionToLocal(direction);
        Vector3d back = frame.orientation().transform(new Vector3d(turned.x, turned.y, turned.z));
        assertClose(direction, new Vec3(back.x, back.y, back.z), 1e-9);
    }

    /** What another structure places in the world, placed in the frame instead, lands where the frame sees it. */
    @Test void anotherStructureIsPlacedWhereTheFrameSeesIt() {
        for (double scale : new double[] {1, 2}) {
            var frame = AcousticFrame.of(UUID.randomUUID(), pose(1200, -3400, 0.7, 0.05, scale));
            Pose3d other = new Pose3d(new Vector3d(1190, 66, -3420), new Quaterniond().rotateY(-1.1).rotateZ(0.2),
                    new Vector3d(20_484_104.5, 80, 20_480_008.5), new Vector3d(1.5));
            Pose3d relative = frame.relative(other);
            for (Vec3 local : new Vec3[] {new Vec3(20_484_104.5, 80, 20_480_008.5), new Vec3(20_484_110, 83, 20_480_001)}) {
                assertClose(frame.toLocal(other.transformPosition(local)), relative.transformPosition(local), 1e-6);
            }
        }
    }

    @Test void terrainIsPlacedByTheFramesInverse() {
        var frame = AcousticFrame.of(UUID.randomUUID(), pose(31_000, -27_000, 2.1, -0.03, 1));
        Pose3d terrain = frame.relative(null);
        for (Vec3 world : new Vec3[] {new Vec3(31_010, 64, -27_005), new Vec3(30_960, 90, -26_990)}) {
            assertClose(frame.toLocal(world), terrain.transformPosition(world), 1e-6);
        }
    }

    /**
     * A turn of the structure below the pose tolerance is not a moved terrain, however far the
     * structure is from the world's origin; moving by more is.
     */
    @Test void terrainPoseComparesAsTheStructureMovesNotAsTheOriginWould() {
        UUID id = UUID.randomUUID();
        Pose3d still = pose(31_000, -27_000, 2.1, 0, 1);
        Pose3d turned = new Pose3d(still);
        turned.orientation().rotateY(Math.toRadians(0.02));
        assertTrue(AcousticUpdateGate.samePose(AcousticFrame.of(id, still).relative(null),
                AcousticFrame.of(id, turned).relative(null)), "a 0.02 degree turn 41 km out");
        Pose3d moved = new Pose3d(still);
        moved.position().add(0.5, 0, 0);
        assertFalse(AcousticUpdateGate.samePose(AcousticFrame.of(id, still).relative(null),
                AcousticFrame.of(id, moved).relative(null)));
    }

    @Test void aStructuresFrameKeepsItsAxesWhereverItMoves() {
        UUID train = UUID.randomUUID();
        Pose3dc here = pose(0, 0, 0, 0, 1), there = pose(400, 10, 1, 0, 1);
        assertTrue(AcousticFrame.of(train, here).sameAxes(AcousticFrame.of(train, there)));
        assertFalse(AcousticFrame.of(train, here).sameAxes(AcousticFrame.of(UUID.randomUUID(), here)));
        assertFalse(AcousticFrame.of(train, here).sameAxes(AcousticFrame.WORLD));
    }

    @Test void theFrameKeepsItsOwnCopyOfThePose() {
        Pose3d live = pose(0, 0, 0, 0, 1);
        var frame = AcousticFrame.of(UUID.randomUUID(), live);
        Vec3 before = frame.toWorld(new Vec3(20_480_010, 64, 20_480_010));
        live.position().add(5, 0, 0);
        assertEquals(before, frame.toWorld(new Vec3(20_480_010, 64, 20_480_010)));
    }
}
