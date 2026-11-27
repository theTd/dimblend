package dimblend.experience.compat.cca;

import java.util.Objects;
import java.util.UUID;

import javax.annotation.Nullable;

/**
 * 跨 Sable 子层级接线器线缆的<b>纯几何</b>（无客户端 / Minecraft bootstrap 依赖，可单测）。
 *
 * <p>用户口径（2026-11-27）：跨 sable 连接的接线器之间的线缆粗细改为 <b>2 像素见方</b>
 * （同 {@code TrainTubeCableGeometry.HALF_WIDTH = 0.0625}），材质不变。Create: Addition
 * 原版线缆是两条 0.025 格宽的扁平 ribbon，本类改为绕 CCA 自己的下垂曲线生成
 * <b>四边封闭的方形截面棱柱</b>：截面半宽 {@link #HALF_WIDTH}（1/16 格 = 1 像素），
 * 全宽 2/16 格 = 2 像素；四个角点共享给相邻线段，弯曲段不会出现裂缝。</p>
 *
 * <p><b>曲线与 CCA 完全一致</b>（{@code WireNodeRenderer#wireVert} / {@code #hang}）：
 * {@code P(part) = (x·part, (y>0 ? y·part² : y − y(1−part)²) + hang(part), z·part)}，
 * {@code hang(part) = sin(−part·π) · 0.5·dis / SMALL_CONNECTOR_MAX_LENGTH}。
 * 分段数也与 CCA 的 24 段一致，所以新棱柱与原线缆完全重合，只是把 ribbon 换成方柱。</p>
 */
public final class CrossSableWireGeometry {

    /** 截面半宽：1/16 格 = 1 像素，全宽 2/16 格 = 2 像素（同加压管道）。 */
    public static final float HALF_WIDTH = 0.0625F;

    /** 曲线分段数，与 {@code WireNodeRenderer} 的 {@code 24} 段一致。 */
    public static final int SEGMENTS = 24;

    /** 每个截面环 4 个角点，每个角点 3 个 float。 */
    public static final int FLOATS_PER_RING = 4 * 3;

    /** CCA 配置缺失/非法时的下垂基准（{@code SMALL_CONNECTOR_MAX_LENGTH} 默认 16）。 */
    private static final float DEFAULT_MAX_LENGTH = 16.0F;

    private static final float EPSILON = 1.0E-6F;

    /**
     * 下垂高度（与 {@code WireNodeRenderer#hang} 同式）：
     * {@code sin(−part·π) · 0.5·dis / maxLength}，中点最大、两端归零。
     *
     * @param maxLength 接线器最大长度；{@code <= 0} 时回落到 16（与 CCA 配置默认值一致）
     */
    public static float hang(float part, float dis, float maxLength) {
        float length = maxLength <= 0.0F ? DEFAULT_MAX_LENGTH : maxLength;
        return (float) Math.sin(-part * (float) Math.PI) * (0.5F * dis / length);
    }

    /**
     * 中心线采样：{@code SEGMENTS + 1} 个点，扁平存 {@code [x0,y0,z0, x1,y1,z1, ...]}。
     *
     * <p>{@code P[0] = (0,0,0)}、{@code P[SEGMENTS] = (x,y,z)}（垂度在两端为 0）。</p>
     */
    public static float[] centerline(float x, float y, float z, float dis, float maxLength) {
        float[] points = new float[(SEGMENTS + 1) * 3];
        for (int i = 0; i <= SEGMENTS; i++) {
            float part = (float) i / (float) SEGMENTS;
            int base = i * 3;
            points[base] = x * part;
            points[base + 1] = (y > 0.0F
                    ? y * part * part
                    : y - y * (1.0F - part) * (1.0F - part)) + hang(part, dis, maxLength);
            points[base + 2] = z * part;
        }
        return points;
    }

    /**
     * 每个顶点的单位切线（中央差分）：首点 {@code P[1]−P[0]}、末点 {@code P[n]−P[n−1]}、
     * 中间 {@code P[i+1]−P[i−1]}。
     *
     * <p>零长度（两端重合）时回落到前一个切线，首点回落到 {@code (0,1,0)}——保证
     * {@link #ringCorners} 永远拿到可用方向，不产生 NaN。</p>
     */
    public static float[] tangents(float[] centerline) {
        int count = centerline.length / 3;
        float[] directions = new float[centerline.length];
        float lastX = 0.0F;
        float lastY = 1.0F;
        float lastZ = 0.0F;
        for (int i = 0; i < count; i++) {
            int from = i == 0 ? i : i - 1;
            int to = i == count - 1 ? i : i + 1;
            float dx = centerline[to * 3] - centerline[from * 3];
            float dy = centerline[to * 3 + 1] - centerline[from * 3 + 1];
            float dz = centerline[to * 3 + 2] - centerline[from * 3 + 2];
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length > EPSILON) {
                lastX = dx / length;
                lastY = dy / length;
                lastZ = dz / length;
            }
            directions[i * 3] = lastX;
            directions[i * 3 + 1] = lastY;
            directions[i * 3 + 2] = lastZ;
        }
        return directions;
    }

    /**
     * 单个截面环的 4 个角点（{@code 4 × 3} 个 float，顺序绕环一周）：
     * {@code P+s+u, P+s−u, P−s−u, P−s+u}，其中 {@code s}/{@code u} 是垂直于切线的
     * 一组正交基、各自长度 {@link #HALF_WIDTH}。
     *
     * <p>参考轴取 {@code |dir.y| > 0.9} 时 {@code (1,0,0)}，否则 {@code (0,1,0)}：
     * 水平/斜线缆的截面竖直对齐（"高"= 2 像素），纯竖直段则对齐 X/Z。</p>
     */
    public static float[] ringCorners(float px, float py, float pz,
                                      float tx, float ty, float tz) {
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        float dx;
        float dy;
        float dz;
        if (length > EPSILON) {
            dx = tx / length;
            dy = ty / length;
            dz = tz / length;
        } else {
            dx = 0.0F;
            dy = 1.0F;
            dz = 0.0F;
        }

        float rx = Math.abs(dy) > 0.9F ? 1.0F : 0.0F;
        float ry = Math.abs(dy) > 0.9F ? 0.0F : 1.0F;
        float rz = 0.0F;

        // side = normalize(dir × ref)
        float sx = dy * rz - dz * ry;
        float sy = dz * rx - dx * rz;
        float sz = dx * ry - dy * rx;
        float sideLength = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sideLength > EPSILON) {
            sx /= sideLength;
            sy /= sideLength;
            sz /= sideLength;
        } else {
            // 理论上不可达（dir 与 ref 至少差 0.43）；防御性回落。
            sx = 0.0F;
            sy = 0.0F;
            sz = 1.0F;
        }

        // up = normalize(side × dir)
        float ux = sy * dz - sz * dy;
        float uy = sz * dx - sx * dz;
        float uz = sx * dy - sy * dx;
        float upLength = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (upLength > EPSILON) {
            ux /= upLength;
            uy /= upLength;
            uz /= upLength;
        } else {
            ux = 0.0F;
            uy = 1.0F;
            uz = 0.0F;
        }

        float hsx = sx * HALF_WIDTH;
        float hsy = sy * HALF_WIDTH;
        float hsz = sz * HALF_WIDTH;
        float hux = ux * HALF_WIDTH;
        float huy = uy * HALF_WIDTH;
        float huz = uz * HALF_WIDTH;

        return new float[] {
                px + hsx + hux, py + hsy + huy, pz + hsz + huz,
                px + hsx - hux, py + hsy - huy, pz + hsz - huz,
                px - hsx - hux, py - hsy - huy, pz - hsz - huz,
                px - hsx + hux, py - hsy + huy, pz - hsz + huz
        };
    }

    /**
     * 整条棱柱的网格：{@code SEGMENTS + 1} 个环 × 4 角点 × 3 float。
     *
     * <p>环与环共享角点（每环只算一次），相邻线段直接引用同一对环顶点，弯曲处无裂缝。</p>
     */
    public static float[] mesh(float x, float y, float z, float dis, float maxLength) {
        float[] points = centerline(x, y, z, dis, maxLength);
        float[] directions = tangents(points);
        float[] corners = new float[(SEGMENTS + 1) * FLOATS_PER_RING];
        for (int i = 0; i <= SEGMENTS; i++) {
            int pointBase = i * 3;
            float[] ring = ringCorners(points[pointBase], points[pointBase + 1], points[pointBase + 2],
                    directions[pointBase], directions[pointBase + 1], directions[pointBase + 2]);
            System.arraycopy(ring, 0, corners, i * FLOATS_PER_RING, FLOATS_PER_RING);
        }
        return corners;
    }

    /**
     * 是否"跨 Sable 子层级"：两端不在同一个空间（载具 A ↔ 载具 B，或载具 ↔ 真实世界）。
     *
     * <p>同一载具内部（{@code A == A}）与真实世界内部（{@code null == null}）都不算跨层级，
     * 保持 CCA 原版细线外观；手持线缆预览由调用方先按「是否已连接的对端节点」过滤。</p>
     */
    public static boolean isCrossSubLevel(@Nullable UUID firstSubLevel, @Nullable UUID secondSubLevel) {
        return !Objects.equals(firstSubLevel, secondSubLevel);
    }

    private CrossSableWireGeometry() {
    }
}
