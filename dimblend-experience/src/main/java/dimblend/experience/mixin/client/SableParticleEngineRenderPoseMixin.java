package dimblend.experience.mixin.client;

import java.util.Map;
import java.util.Queue;
import java.util.function.Predicate;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.compat.sable.RenderPoseShiftable;
import dimblend.experience.compat.sable.SableAnimationDiagnostics;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * 把「被 Sable 子层级追踪的粒子」在绘制时对齐到子层级网格的渲染位姿。
 *
 * <p>根因见 {@link dimblend.experience.compat.sable.SubLevelParticleRenderPose}：tick 0 的粒子整团
 * 比 {@code renderPose(pt)} 早一个 tick，tick 1+ 的旧实现又会把粒子反向推一个
 * {@code (1 - pt)} 的列车位移。这里在 {@code ParticleEngine#render} 前后改写/还原这些粒子的绘制
 * 坐标，模拟状态（速度、包围盒、局部锚点）全程不变。</p>
 *
 * <p>整段包在 {@code @WrapMethod} 的 try/finally 里：即便 {@code render} 中途抛异常（粒子
 * {@code render} 抛错会被包装成 {@code ReportedException} 重抛），也一定把每一颗被改写的粒子逐位
 * 还原，绝不让位移留在粒子身上影响下一帧。</p>
 */
@Mixin(ParticleEngine.class)
public abstract class SableParticleEngineRenderPoseMixin {

    @Shadow
    @Final
    private Map<ParticleRenderType, Queue<Particle>> particles;

    @Unique
    private final ObjectArrayList<Particle> dimblend$shiftedParticles = new ObjectArrayList<>();
    @Unique
    private final Vector3d dimblend$beforePos = new Vector3d();
    @Unique
    private final Vector3d dimblend$afterPos = new Vector3d();

    @WrapMethod(
            method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
            require = 1,
            allow = 1)
    private void dimblend$renderWithSubLevelPose(
            LightTexture lightTexture,
            Camera camera,
            float partialTick,
            Frustum frustum,
            Predicate<ParticleRenderType> renderTypePredicate,
            Operation<Void> original) {
        this.dimblend$alignSubLevelParticles(partialTick, renderTypePredicate);
        try {
            original.call(lightTexture, camera, partialTick, frustum, renderTypePredicate);
        } finally {
            this.dimblend$restoreSubLevelParticles();
        }
    }

    @Unique
    private void dimblend$alignSubLevelParticles(
            float partialTick, Predicate<ParticleRenderType> renderTypePredicate) {
        this.dimblend$shiftedParticles.clear();
        double maxShift = 0.0;
        for (Map.Entry<ParticleRenderType, Queue<Particle>> entry : this.particles.entrySet()) {
            if (renderTypePredicate != null && !renderTypePredicate.test(entry.getKey())) {
                continue;
            }
            for (Particle particle : entry.getValue()) {
                if (!(particle instanceof RenderPoseShiftable shiftable)) {
                    continue;
                }
                shiftable.dimblend$readRenderPos(this.dimblend$beforePos);
                if (!shiftable.dimblend$applyRenderPose(partialTick)) {
                    continue;
                }
                shiftable.dimblend$readRenderPos(this.dimblend$afterPos);
                maxShift = Math.max(maxShift, this.dimblend$beforePos.distance(this.dimblend$afterPos));
                this.dimblend$shiftedParticles.add(particle);
            }
        }
        // 采样本身有 2 秒全局限频；这里只避免关诊断时进方法。
        if (!this.dimblend$shiftedParticles.isEmpty()) {
            SableAnimationDiagnostics.particleShift(this.dimblend$shiftedParticles.size(), maxShift);
        }
    }

    @Unique
    private void dimblend$restoreSubLevelParticles() {
        for (int i = 0; i < this.dimblend$shiftedParticles.size(); i++) {
            ((RenderPoseShiftable) this.dimblend$shiftedParticles.get(i)).dimblend$restoreRenderPose();
        }
        this.dimblend$shiftedParticles.clear();
    }
}
