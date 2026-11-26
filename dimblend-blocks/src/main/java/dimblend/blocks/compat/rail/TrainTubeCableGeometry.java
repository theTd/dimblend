package dimblend.blocks.compat.rail;

import net.minecraft.world.phys.Vec3;

/**
 * 跨车厢线缆的纯数学：端点朝向偏移、下垂采样、世界→相机换算、亮度取样。
 *
 * <p>抽成纯函数是为了让"线缆为什么画错位置"这类问题能被单测钉住（渲染链路本身跑不进 GameTest）。</p>
 *
 * <p><b>为什么端点要从方块中心往对端推 {@link #ENDPOINT_OFFSET}</b>：端点在子层级 plot 里是一个整方块，
 * 但管道模型只有 4px（{@code TrainPressuredTubeBlock} 的 {@code super(0.25f, ...)}）半径，车厢壳体又把方块中心
 * 包在里面。直接从方块中心起笔，线缆整段埋在车厢体内，肉眼只剩两车之间那一点点缝隙——看起来就是"没有视觉"。</p>
 *
 * <p><b>为什么相机差值必须在 double 上做</b>：{@code PoseStack} 的矩阵是 float，plot 坐标原点在
 * {@code 20,480,000}，该量级 float ULP = 2.0（{@code dimblend-dev} §8.47）。线缆顶点必须先在
 * 世界坐标（double）上减去相机位置，再把差值转 float 交给 identity 姿态的 {@code addVertex}。</p>
 */
public final class TrainTubeCableGeometry {

    /**
     * 端点从方块中心沿"朝对端"的主轴推出去的距离：管道模型半径 0.25（4/16 格），
     * 推到管口表面，线缆正好从管口长出来，既不出方块也不留悬空缝。
     */
    public static final double ENDPOINT_OFFSET = 0.25;

    /** 线缆截面半宽：2/16 格 → 0.0625。 */
    public static final double HALF_WIDTH = 0.0625;

    /** 下垂折线分段数。 */
    public static final int SEGMENTS = 8;

    /** 下垂高度 = 世界距离 × 该比例，再截到 {@link #MAX_SAG}。 */
    public static final double SAG_RATIO = 0.08;
    public static final double MAX_SAG = 0.35;

    /** 超过这个距离的连接不画（正常世界距离 ≤ 16，这里只是防御）。 */
    public static final double MAX_DRAW_DISTANCE = 64.0;

    private static final double EPSILON = 1.0E-9;

    private TrainTubeCableGeometry() {
    }

    /**
     * 把端点方块中心沿"指向对端"的主轴推到管口表面。
     *
     * @param blockCenter   端点方块中心（子层级 plot 坐标系）
     * @param partnerInLocal 对端在<b>同一个</b> plot 坐标系里的位置
     * @return 偏移后的 plot 坐标点；两端重合时原样返回
     */
    public static Vec3 endpoint(Vec3 blockCenter, Vec3 partnerInLocal) {
        Vec3 delta = partnerInLocal.subtract(blockCenter);
        double axisX = Math.abs(delta.x);
        double axisY = Math.abs(delta.y);
        double axisZ = Math.abs(delta.z);
        if (Math.max(axisX, Math.max(axisY, axisZ)) < EPSILON) {
            return blockCenter;
        }
        if (axisX >= axisY && axisX >= axisZ) {
            return blockCenter.add(sign(delta.x) * ENDPOINT_OFFSET, 0.0, 0.0);
        }
        if (axisY >= axisZ) {
            return blockCenter.add(0.0, sign(delta.y) * ENDPOINT_OFFSET, 0.0);
        }
        return blockCenter.add(0.0, 0.0, sign(delta.z) * ENDPOINT_OFFSET);
    }

    /** 下垂高度：距离越远垂得越多，但有上限（16 格 → 0.21 格左右）。 */
    public static double sag(double distance) {
        return Math.min(MAX_SAG, Math.max(0.0, distance) * SAG_RATIO);
    }

    /**
     * 线缆折线的世界坐标采样：{@code points[0]} 是起点、{@code points[n]} 是终点（两端不下垂）。
     *
     * <p>下垂方向固定为世界 −Y（重力），不跟车体姿态走。</p>
     */
    public static Vec3[] cablePoints(Vec3 from, Vec3 to, int segments) {
        int count = Math.max(1, segments);
        Vec3[] points = new Vec3[count + 1];
        double sag = sag(from.distanceTo(to));
        for (int index = 0; index <= count; index++) {
            double t = (double) index / (double) count;
            points[index] = from.lerp(to, t).add(0.0, -sag * 4.0 * t * (1.0 - t), 0.0);
        }
        return points;
    }

    /**
     * 世界坐标 → 相机相对坐标（double 相减，**不**先把世界坐标转 float）。
     *
     * <p>与 {@code AcousticBakeView#box} 的 {@code box.move(camera.reverse())} 同一口径。</p>
     */
    public static Vec3 cameraRelative(Vec3 world, Vec3 camera) {
        return new Vec3(world.x - camera.x, world.y - camera.y, world.z - camera.z);
    }

    /**
     * 三个采样点里取最亮的一个（{@code LevelRenderer.getLightColor} 的打包值直接比大小）。
     *
     * <p>线缆两端的取样点常常落在车厢壳体内部（暗），而中点落在两车之间的缝隙（通常是天光），
     * 只取端点会让线缆整根发黑 = "看不见"。打包值里方块光在高位，比大小等价于"方块光优先"。</p>
     */
    public static int brightestLight(int first, int second, int third) {
        return Math.max(first, Math.max(second, third));
    }

    /** 距离是否值得画：太近（两端重合）或超长（异常数据）都不画。 */
    public static boolean isDrawable(double distance) {
        return distance > EPSILON && distance <= MAX_DRAW_DISTANCE;
    }

    private static double sign(double value) {
        return value < 0.0 ? -1.0 : 1.0;
    }
}
