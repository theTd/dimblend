package dimblend.experience.mixin.client;

import dimblend.experience.compat.sable.RenderPoseShiftable;
import net.minecraft.client.particle.Particle;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 给 {@link Particle} 加一个渲染坐标读写口，供 {@link SableParticleEngineRenderPoseMixin}
 * 把子层级粒子对齐到网格实际使用的渲染位姿。
 *
 * <p>只加方法、不注入任何调用点；{@code x/y/z} 与 {@code xo/yo/zo} 都是 {@code protected}
 * （vanilla {@code Particle.java:24-29}），所以必须由目标类自己的代码读写。平移只作用于绘制，
 * 速度与包围盒不动。</p>
 */
@Mixin(Particle.class)
public abstract class SableParticleRenderPoseMixin implements RenderPoseShiftable {

    // 仓库纪律：mixin 成员一律 private（@Shadow 只声明目标字段的存在，实际访问走目标类自身，
    // 可见性不必与 vanilla 的 protected 一致）。
    @Shadow
    private double xo;
    @Shadow
    private double yo;
    @Shadow
    private double zo;
    @Shadow
    private double x;
    @Shadow
    private double y;
    @Shadow
    private double z;

    @Override
    public void dimblend$readRenderPos(Vector3d dest) {
        dest.set(this.x, this.y, this.z);
    }

    @Override
    public void dimblend$shiftRenderPos(double dx, double dy, double dz) {
        this.x += dx;
        this.y += dy;
        this.z += dz;
        // 部分粒子的 render 用 Mth.lerp(partialTick, xo, x) 做帧间插值，上一帧坐标也要同步平移，
        // 否则只会平移 pt 倍，越靠近刻边界偏差越小、永远对不齐。
        this.xo += dx;
        this.yo += dy;
        this.zo += dz;
    }
}
