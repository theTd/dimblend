package dimblend.experience.compat.sable;

import org.joml.Vector3d;

/**
 * 子层级粒子在「渲染位姿」下的坐标读写口。
 *
 * <p>{@code Particle} 的 {@code x/y/z} 与 {@code xo/yo/zo} 都是 {@code protected}
 * （vanilla {@code Particle.java:24-29}），本包无法直接读写；由目标 {@code Particle} 的 mixin
 * 实现本接口，注入点只经接口调用，不再重复声明 shadow 字段。</p>
 *
 * <p>该接口只平移「渲染坐标」，不改速度、包围盒与模拟位置：粒子的模拟仍由 Sable 按
 * {@code logicalPose}（每 tick 位姿）推进，本接口只把最后一帧的绘制位置对齐到子层级网格
 * 实际使用的 {@code renderPose(partialTick)}。</p>
 */
public interface RenderPoseShiftable {

    /** 读出当前渲染用的世界坐标。 */
    void dimblend$readRenderPos(Vector3d dest);

    /** 把渲染坐标整体平移 (dx, dy, dz)。 */
    void dimblend$shiftRenderPos(double dx, double dy, double dz);
}
