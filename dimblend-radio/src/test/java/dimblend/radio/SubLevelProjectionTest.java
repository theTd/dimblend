package dimblend.radio;

import dev.ryanhcode.sable.companion.ClientSubLevelAccess;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SubLevelProjectionTest {
    private static final Vec3 PLOT = new Vec3(20_480_000.5, 80.5, 20_480_000.5);

    /** A structure between two ticks, interpolated as Sable's ClientSubLevel renders it. */
    private record Structure(Pose3d lastPose, Pose3d logicalPose) implements ClientSubLevelAccess {
        @Override public Pose3dc renderPose() { return renderPose(1); }
        @Override public Pose3dc renderPose(float partialTick) { return new Pose3d(lastPose).lerp(logicalPose, partialTick); }
        @Override public BoundingBox3dc boundingBox() { throw new UnsupportedOperationException(); }
        @Override public UUID getUniqueId() { return new UUID(0, 1); }
        @Override public String getName() { return "train car"; }
    }

    private static Pose3d pose(double x, double yawDegrees) {
        return new Pose3d(new Vector3d(x, 64, 0), new Quaterniond().rotateY(Math.toRadians(yawDegrees)),
                new Vector3d(PLOT.x, PLOT.y, PLOT.z), new Vector3d(1, 1, 1));
    }

    @Test
    void aRiderHearsTheRadioWhereTheFrameDrawsItNotATickAhead() {
        // One block and ten degrees per tick: a train taking a curve.
        var car = new Structure(pose(0, 0), pose(1, 10));
        Vec3 radio = PLOT.add(3, 1.5, 0), eye = PLOT.add(0, 1.62, 2);
        double apart = radio.distanceTo(eye);
        for (float partialTick : new float[] {0, 0.25f, 0.5f, 0.75f, 1}) {
            // Sable's Camera.setup mixin places a rider standing still on the car by this render pose.
            Vec3 camera = car.renderPose(partialTick).transformPosition(eye);
            Vec3 source = SubLevelProjection.framePose(car, partialTick).transformPosition(radio);
            assertEquals(apart, source.distanceTo(camera), 1e-6, "partial tick " + partialTick);
        }
        Vec3 cameraAfterTick = car.renderPose(0).transformPosition(eye);
        Vec3 logicalSource = car.logicalPose().transformPosition(radio);
        assertTrue(Math.abs(logicalSource.distanceTo(cameraAfterTick) - apart) > 0.5,
                "the logical pose leads the drawn frame by up to a tick of motion");
    }

    @Test
    void serverStructuresKeepTheirLogicalPose() {
        Pose3d logical = pose(5, 30);
        SubLevelAccess server = new SubLevelAccess() {
            @Override public Pose3dc logicalPose() { return logical; }
            @Override public Pose3dc lastPose() { return pose(0, 0); }
            @Override public BoundingBox3dc boundingBox() { throw new UnsupportedOperationException(); }
            @Override public UUID getUniqueId() { return new UUID(0, 2); }
            @Override public String getName() { return "server"; }
        };
        assertSame(logical, SubLevelProjection.framePose(server, 0.3f));
    }
}
