package dimblend.experience.chassis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

class ChassisPoseTest {
    private static final double EPS = 1.0E-6;

    @Test
    void identityAnglesAreZero() {
        ChassisPose.Angles angles = ChassisPose.angles(new Quaterniond());
        assertEquals(0.0, angles.yawDegrees(), EPS);
        assertEquals(0.0, angles.pitchDegrees(), EPS);
        assertEquals(0.0, angles.rollDegrees(), EPS);
    }

    @Test
    void yxzEulerRoundTrips() {
        double[][] cases = {
                {30.0, 10.0, -20.0},
                {90.0, 0.0, 0.0},
                {-135.0, 20.0, 15.0},
                {179.0, -35.0, 5.0},
                {0.0, 45.0, 30.0}
        };
        for (double[] e : cases) {
            Quaterniond q = new Quaterniond().rotationYXZ(
                    Math.toRadians(e[0]), Math.toRadians(e[1]), Math.toRadians(e[2]));
            ChassisPose.Angles angles = ChassisPose.angles(q);
            assertEquals(e[0], angles.yawDegrees(), 1.0E-4, "yaw " + e[0]);
            assertEquals(e[1], angles.pitchDegrees(), 1.0E-4, "pitch " + e[1]);
            assertEquals(e[2], angles.rollDegrees(), 1.0E-4, "roll " + e[2]);
        }
    }

    @Test
    void yawFrameDropsPitchAndRoll() {
        Quaterniond body = new Quaterniond().rotationYXZ(
                Math.toRadians(90.0), Math.toRadians(30.0), Math.toRadians(20.0));
        Quaterniond frame = ChassisPose.yawFrame(body);
        ChassisPose.Angles angles = ChassisPose.angles(frame);
        assertEquals(90.0, angles.yawDegrees(), 1.0E-4);
        assertEquals(0.0, angles.pitchDegrees(), 1.0E-4);
        assertEquals(0.0, angles.rollDegrees(), 1.0E-4);
    }

    @Test
    void bodyFrameComposesBackToWorldFrame() {
        Quaterniond body = new Quaterniond().rotationYXZ(
                Math.toRadians(-50.0), Math.toRadians(12.0), Math.toRadians(-7.0));
        Quaterniond world = ChassisPose.yawFrame(body);
        Quaterniond local = ChassisPose.bodyFrame(body, world);
        Quaterniond composed = new Quaterniond(body).mul(local);
        assertEquals(0.0, angleBetween(composed, world), 1.0E-6);

        // 反方向同样成立：世界帧左乘车体旋转仍是局部帧。
        Quaterniond localFromWorld = new Quaterniond(body).invert().mul(world);
        assertEquals(0.0, angleBetween(localFromWorld, local), 1.0E-6);
    }

    private static double angleBetween(org.joml.Quaterniondc a, org.joml.Quaterniondc b) {
        double dot = a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w();
        return 2.0 * Math.acos(Math.min(1.0, Math.abs(dot)));
    }

    @Test
    void anchorFollowsBodyRotationAndPosition() {
        Quaterniond body = new Quaterniond().rotationY(Math.toRadians(90.0));
        Vector3d world = ChassisPose.anchor(body, new Vector3d(2.0, 0.0, 0.0), new Vector3d(),
                new Vector3d(10.0, 66.0, -3.0), new Vector3d());
        assertEquals(10.0, world.x, EPS);
        assertEquals(66.0, world.y, EPS);
        assertEquals(-3.0 - 2.0, world.z, EPS);
        assertTrue(Math.abs(world.y - 66.0) < EPS);
    }

    @Test
    void anchorSubtractsLargeNonZeroPlotCenterOfMassLikeNativeJoint() {
        Quaterniond body = new Quaterniond().rotationYXZ(
                Math.toRadians(90.0), Math.toRadians(5.0), Math.toRadians(-3.0));
        Vector3d rawPlotPoint = new Vector3d(0.0, 128.5, 0.0);
        Vector3d centerOfMass = new Vector3d(0.0, 128.0, 0.0);
        Vector3d bodyPosition = new Vector3d(0.0, 65.0, 0.0);

        // Native body-local anchor is pos2 - COM; world = bodyPos + R * (pos2 - COM).
        Vector3d expected = new Vector3d(rawPlotPoint).sub(centerOfMass);
        body.transform(expected).add(bodyPosition);
        Vector3d actual = ChassisPose.anchor(body, rawPlotPoint, centerOfMass, bodyPosition, new Vector3d());

        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);
        assertEquals(65.5, actual.y, 0.01);

        // The old no-COM projection is not the native local frame; it must differ visibly.
        Vector3d oldNoCom = ChassisPose.anchor(body, rawPlotPoint, new Vector3d(), bodyPosition, new Vector3d());
        assertTrue(oldNoCom.distance(actual) > 1.0,
                "old position + R*rawPlot must be red for a non-zero plot COM");
    }
}
