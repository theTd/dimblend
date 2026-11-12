package dimblend.experience.compat.sable;

import org.joml.Vector3d;

/**
 * 子层级粒子在「渲染位姿」下的坐标读写口。
 *
 * <p>{@code Particle} 的 {@code x/y/z} 与 {@code xo/yo/zo} 都是 {@code protected}
 * （vanilla {@code Particle.java:24-29}），本包无法直接读写；由目标 {@code Particle} 的 mixin
 * 实现本接口，注入点只经接口调用，不再重复声明 shadow 字段。</p>
 *
 * <p>该接口只改「渲染那一帧的坐标」，不改速度、包围盒与模拟位置：粒子的模拟仍由 Sable 按
 * {@code logicalPose}（每 tick 位姿）推进，这里只把这一帧的绘制坐标对齐到子层级网格实际使用的
 * {@code renderPose(partialTick)}，并在 {@link #dimblend$restoreRenderPose()} 里逐位还原。</p>
 *
 * <p>为什么必须逐位还原：{@link #dimblend$applyRenderPose} 会把 {@code x} 与 {@code xo} 设成
 * 同一个值（{@code Mth.lerp(partialTick, xo, x)} 于是恒等于该值，零漂移）。若不还原，下一 tick
 * 的 {@code Particle#tick} 会把 {@code xo = x} 记成「上一帧的渲染坐标」，模拟位置就被永久污染。</p>
 */
public interface RenderPoseShiftable {

    /**
     * 按渲染位姿改写这一帧的绘制坐标。
     *
     * @param partialTick 与 {@code ParticleEngine#render} 同一个部分刻
     * @return 是否真的改写了（未被 Sable 子层级追踪、或该帧无需改写时为 false）
     */
    boolean dimblend$applyRenderPose(float partialTick);

    /** 把 {@link #dimblend$applyRenderPose} 改写的六个坐标逐位还原；未改写时为空操作。 */
    void dimblend$restoreRenderPose();

    /** 读出当前绘制坐标 {@code (x, y, z)}。 */
    void dimblend$readRenderPos(Vector3d dest);

    /** 读出上一帧绘制坐标 {@code (xo, yo, zo)}——tick 0 与 tick 1+ 的位姿口径靠它区分。 */
    void dimblend$readPreviousRenderPos(Vector3d dest);
}
