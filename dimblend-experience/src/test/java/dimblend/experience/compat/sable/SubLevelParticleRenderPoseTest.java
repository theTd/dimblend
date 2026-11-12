package dimblend.experience.compat.sable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ryanhcode.sable.companion.math.Pose3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

/**
 * 子层级粒子位姿对齐的纯数学测试（{@link SubLevelParticleRenderPose#computeRenderPosition}）。
 *
 * <p>不需要 Minecraft Bootstrap：只用了 JOML 与 Sable companion 的 {@code Pose3d}/{@code Pose3dc}，
 * 位姿语义与运行期完全一致（{@code transformPosition(local) = q * (local - rotationPoint) * scale
 * + position}）。三个用例各自钉住一条实机缺陷：</p>
 * <ul>
 *   <li>tick 0 生成即前移一整 tick（{@code xo == x} 都在 {@code logicalPose} 里）；</li>
 *   <li>tick 1+ 的 {@code 7428143} 反向滞后（把 {@code -(1 - pt) * V} 叠到坐标上）；</li>
 *   <li>转弯时「世界坐标线性插值」的弦线误差（正确落点必须在圆弧上）。</li>
 * </ul>
 */
class SubLevelParticleRenderPoseTest {

    private static final double EPS = 1.0E-9;
    private static final Vector3d UNIT_SCALE = new Vector3d(1.0, 1.0, 1.0);

    private static Pose3d pose(Vector3d position, Quaterniond orientation, Vector3d rotationPoint) {
        return new Pose3d(new Vector3d(position), new Quaterniond(orientation), new Vector3d(rotationPoint),
                new Vector3d(UNIT_SCALE));
    }

    /** 与 {@code ClientSubLevel#renderPose(pt)} 同一口径：{@code lastPose.lerp(logicalPose, pt)}。 */
    private static Pose3d renderPose(Pose3d lastPose, Pose3d logicalPose, float partialTick) {
        return lastPose.lerp(logicalPose, partialTick, new Pose3d());
    }

    private static Vector3d world(Pose3d pose, Vector3d local) {
        return pose.transformPosition(new Vector3d(local), new Vector3d());
    }

    private static void assertVectorEquals(Vector3d expected, Vector3d actual, String message) {
        assertEquals(expected.x, actual.x, EPS, message + ".x");
        assertEquals(expected.y, actual.y, EPS, message + ".y");
        assertEquals(expected.z, actual.z, EPS, message + ".z");
    }

    /**
     * tick 0：粒子刚由 {@code MillstoneBlockEntity#tick} 生成，{@code ParticleEngine#add} 里 Sable 的
     * {@code sable$initialKickOut} 用 {@code logicalPose} 换算出世界坐标，此时 {@code xo == x}；
     * 该 tick 粒子还没被 {@code Particle#tick} 跑过，所以 {@code xo} 也在 {@code logicalPose} 里。
     *
     * <p>正确落点只由 {@code renderPose(pt)} 决定：{@code pt=0} 给 {@code P(T-1)}、{@code pt=1} 给
     * {@code P(T)}；旧实现（直接用 {@code xo/x}）在整个 partialTick 上多出一个 tick 的位移。</p>
     */
    @Test
    void tickZeroParticleFollowsRenderPoseWithoutTheOneTickJump() {
        // 沿 +X 每 tick 前进 2 格的载具；rotationPoint 与 position 相同，位姿是纯平移。
        Vector3d rotationPoint = new Vector3d(100.0, 64.0, 200.0);
        Pose3d lastPose = pose(new Vector3d(100.0, 64.0, 200.0), new Quaterniond(), rotationPoint);
        Pose3d logicalPose = pose(new Vector3d(102.0, 64.0, 200.0), new Quaterniond(), rotationPoint);

        // 磨盘口在 plot 局部空间里的点；生成瞬间 xo = x = logicalPose(p0)。
        Vector3d plotPoint = new Vector3d(100.25, 64.5, 200.75);
        Vector3d spawnedWorld = world(logicalPose, plotPoint);

        for (float partialTick : new float[] {0.0F, 0.25F, 0.5F, 1.0F}) {
            Pose3d framePose = renderPose(lastPose, logicalPose, partialTick);
            Vector3d expected = world(framePose, plotPoint);
            Vector3d actual = new Vector3d();

            assertTrue(SubLevelParticleRenderPose.computeRenderPosition(logicalPose, logicalPose, framePose,
                    partialTick,
                    spawnedWorld.x, spawnedWorld.y, spawnedWorld.z,
                    spawnedWorld.x, spawnedWorld.y, spawnedWorld.z,
                    actual), "tick 0（xo == x）必须给出可用结果 pt=" + partialTick);

            assertVectorEquals(expected, actual, "tick 0 必须落在 renderPose(pt)(p0) 上 pt=" + partialTick);
        }

        // pt = 0 就是「上一 tick 的位姿」：x 必须回到 100.25，而不是生成时的 102.25。
        Vector3d atTickStart = new Vector3d();
        assertTrue(SubLevelParticleRenderPose.computeRenderPosition(logicalPose, logicalPose,
                renderPose(lastPose, logicalPose, 0.0F), 0.0F,
                spawnedWorld.x, spawnedWorld.y, spawnedWorld.z,
                spawnedWorld.x, spawnedWorld.y, spawnedWorld.z,
                atTickStart));
        assertEquals(100.25, atTickStart.x, EPS, "pt=0 必须正好是 P(T-1)，不能是 P(T)");
        assertTrue(Math.abs(spawnedWorld.x - atTickStart.x) > 1.9,
                "未修复时整帧停在 P(T)，会与 P(T-1) 差一个 tick 的 2.0 格");
    }

    /**
     * tick 1+：{@code xo} 已在 {@code lastPose}（上一 tick 的局部位置 {@code p0}），{@code x} 已在
     * {@code logicalPose}（本 tick 的局部位置 {@code p1}，含粒子自身的局部运动）。
     *
     * <p>正确落点 = {@code renderPose(pt)(lerp(pt, p0, p1))}。旧实现（{@code 7428143}）把
     * {@code renderPose(logicalPose^-1(x)) - x} 叠到坐标上，落点等于
     * {@code renderPose(localCurr)}，多出 {@code (1 - pt)} 倍的局部/列车位移。</p>
     */
    @Test
    void tickedParticleInterpolatesInPlotSpaceWithoutBackwardLag() {
        Vector3d rotationPoint = new Vector3d(100.0, 64.0, 200.0);
        Pose3d lastPose = pose(new Vector3d(100.0, 64.0, 200.0), new Quaterniond(), rotationPoint);
        Pose3d logicalPose = pose(new Vector3d(102.0, 64.0, 200.0), new Quaterniond(), rotationPoint);

        Vector3d previousLocal = new Vector3d(100.25, 64.5, 200.75);
        Vector3d localMotion = new Vector3d(0.1, 0.2, -0.05);
        Vector3d currentLocal = new Vector3d(previousLocal).add(localMotion);

        // tick 1+：xo 来自 lastPose 的局部位置，x 来自 logicalPose 的局部位置。
        Vector3d previousWorld = world(lastPose, previousLocal);
        Vector3d currentWorld = world(logicalPose, currentLocal);

        for (float partialTick : new float[] {0.0F, 0.25F, 0.5F, 1.0F}) {
            Pose3d framePose = renderPose(lastPose, logicalPose, partialTick);
            Vector3d expectedLocal = new Vector3d(previousLocal).lerp(currentLocal, partialTick);
            Vector3d expected = world(framePose, expectedLocal);
            Vector3d actual = new Vector3d();

            assertTrue(SubLevelParticleRenderPose.computeRenderPosition(lastPose, logicalPose, framePose,
                    partialTick,
                    previousWorld.x, previousWorld.y, previousWorld.z,
                    currentWorld.x, currentWorld.y, currentWorld.z,
                    actual), "tick 1+ 必须给出可用结果 pt=" + partialTick);

            assertVectorEquals(expected, actual, "tick 1+ 必须在局部空间插值 pt=" + partialTick);

            // 旧实现的落点：renderPose(logicalPose^-1(x))（= x + 那个反向位移）。
            Vector3d legacy = world(framePose, logicalPose.transformPositionInverse(new Vector3d(currentWorld),
                    new Vector3d()));
            if (partialTick < 1.0F) {
                assertTrue(actual.distance(legacy) > 1.0E-6,
                        "pt<1 时旧实现的反向滞后必须被消除 pt=" + partialTick);
            }
        }
    }

    /**
     * 转弯：{@code yaw} 从 0° 转到 30°，绕非原点 {@code rotationPoint = (100, 65, 200)} 旋转。
     *
     * <p>在 plot 局部空间插值再正变换，落点严格在半径 {@code r} 的圆弧上、角度正好半个 yaw（15°）；
     * 「世界坐标线性插值」会落在弦中点上（半径 {@code r * cos(15°)}，约短 0.136 格）。</p>
     */
    @Test
    void turningSubLevelKeepsTheParticleOnTheArcNotOnTheChord() {
        Vector3d rotationPoint = new Vector3d(100.0, 65.0, 200.0);
        Pose3d lastPose = pose(rotationPoint, new Quaterniond(), rotationPoint);
        Pose3d logicalPose = pose(rotationPoint, new Quaterniond().rotationY(Math.toRadians(30.0)), rotationPoint);

        // 距旋转点 4 格（Y 上抬 0.5）的 plot 局部点；绕 Y 轴旋转，XZ 半径恒为 4。
        double radius = 4.0;
        Vector3d plotPoint = new Vector3d(104.0, 65.5, 200.0);
        Vector3d previousWorld = world(lastPose, plotPoint);
        Vector3d currentWorld = world(logicalPose, plotPoint);

        float partialTick = 0.5F;
        Pose3d framePose = renderPose(lastPose, logicalPose, partialTick);
        Vector3d actual = new Vector3d();
        assertTrue(SubLevelParticleRenderPose.computeRenderPosition(lastPose, logicalPose, framePose, partialTick,
                previousWorld.x, previousWorld.y, previousWorld.z,
                currentWorld.x, currentWorld.y, currentWorld.z,
                actual));

        assertVectorEquals(world(framePose, plotPoint), actual, "转弯时必须落在 renderPose(pt)(p0) 上");

        double dx = actual.x - rotationPoint.x;
        double dz = actual.z - rotationPoint.z;
        assertEquals(radius, Math.sqrt(dx * dx + dz * dz), EPS, "必须严格在圆弧上（半径不变）");
        assertEquals(65.5, actual.y, EPS, "绕 Y 轴旋转不改高度");

        // 相对 0° 的转角必须是 15°（半个 yaw）：弦中点也会「在两点之间」，只有角度能区分弧与弦。
        double cos = (radius * dx) / (radius * radius);
        assertEquals(15.0, Math.toDegrees(Math.acos(Math.min(1.0, cos))), 1.0E-7, "转角必须正好半个 yaw");

        // 弦中点（世界坐标线性插值）半径短 r * (1 - cos 15°) ≈ 0.136 格，必须被判为不同点。
        Vector3d chordMidpoint = new Vector3d(previousWorld).add(currentWorld).mul(0.5);
        assertEquals(0.0, chordMidpoint.y - 65.5, EPS, "弦中点也在同一高度，只有 XZ 半径能区分");
        assertTrue(actual.distance(chordMidpoint) > 0.1, "落点不能在弦上");
    }

    @Test
    void rejectsMissingOrNonFiniteInputs() {
        Vector3d rotationPoint = new Vector3d(0.0, 64.0, 0.0);
        Pose3d identity = pose(rotationPoint, new Quaterniond(), rotationPoint);
        Vector3d out = new Vector3d();

        assertFalse(SubLevelParticleRenderPose.computeRenderPosition(null, identity, identity, 0.0F,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, out), "缺 prevPose 必须拒绝");
        assertFalse(SubLevelParticleRenderPose.computeRenderPosition(identity, null, identity, 0.0F,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, out), "缺 currPose 必须拒绝");
        assertFalse(SubLevelParticleRenderPose.computeRenderPosition(identity, identity, null, 0.0F,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, out), "缺 renderPose 必须拒绝");
        assertFalse(SubLevelParticleRenderPose.computeRenderPosition(identity, identity, identity, Float.NaN,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, out), "partialTick 非有限必须拒绝");
        assertFalse(SubLevelParticleRenderPose.computeRenderPosition(identity, identity, identity, 0.5F,
                Double.NaN, 0.0, 0.0, 0.0, 0.0, 0.0, out), "坐标非有限必须拒绝");
        assertFalse(SubLevelParticleRenderPose.computeRenderPosition(identity, identity, identity, 0.5F,
                0.0, 0.0, 0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, out), "坐标非有限必须拒绝");
        assertFalse(SubLevelParticleRenderPose.computeRenderPosition(identity, identity, identity, 0.5F,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, null), "缺输出向量必须拒绝");
    }

    @Test
    void particleEngineMixinWrapsNeoForgeFiveArgRenderOverload() throws Exception {
        String resource = "dimblend/experience/mixin/client/SableParticleEngineRenderPoseMixin.class";
        byte[] bytes;
        try (var stream = SubLevelParticleRenderPoseTest.class.getClassLoader().getResourceAsStream(resource)) {
            org.junit.jupiter.api.Assertions.assertNotNull(stream, "Mixin class bytes must be present");
            bytes = stream.readAllBytes();
        }
        String ascii = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(ascii.contains(
                "render(Lnet/minecraft/client/renderer/LightTexture;"
                        + "Lnet/minecraft/client/Camera;"
                        + "FLnet/minecraft/client/renderer/culling/Frustum;"
                        + "Ljava/util/function/Predicate;)V"),
                "SableParticleEngineRenderPoseMixin must wrap NeoForge's 5-arg ParticleEngine#render overload");
    }
}
