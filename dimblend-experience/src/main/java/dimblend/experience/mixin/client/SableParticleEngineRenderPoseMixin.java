package dimblend.experience.mixin.client;

import java.util.Map;
import java.util.Queue;

import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.compat.sable.RenderPoseShiftable;
import dimblend.experience.compat.sable.SableAnimationDiagnostics;
import dimblend.experience.compat.sable.SubLevelParticleRenderPose;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把「被 Sable 子层级追踪的粒子」在绘制时对齐到子层级网格的渲染位姿。
 *
 * <p>根因见 {@link SubLevelParticleRenderPose}：Sable 生成粒子时用 {@code logicalPose}（tick 位姿）
 * 换算世界坐标，而网格用 {@code renderPose(partialTick)} 绘制，移动/转向中的载具上整团粒子相对
 * 模型前移最多一个 tick 的位移，且随 partialTick 每帧抖动。这里在 {@code ParticleEngine#render}
 * 前后临时平移这些粒子的绘制坐标，还原后模拟状态不变。</p>
 *
 * <p>只处理 {@code ParticleExtension#sable$getTrackingSubLevel()} 非空的粒子；地面粒子、未被
 * 子层级追踪的粒子完全不受影响。静止载具两姿态相等，位移为 0，不会进入平移分支。</p>
 */
@Mixin(ParticleEngine.class)
public abstract class SableParticleEngineRenderPoseMixin {

    @Shadow
    @Final
    private Map<ParticleRenderType, Queue<Particle>> particles;

    @Unique
    private final ObjectArrayList<Particle> dimblend$shiftedParticles = new ObjectArrayList<>();
    @Unique
    private final DoubleArrayList dimblend$shiftAmounts = new DoubleArrayList();
    @Unique
    private final Vector3d dimblend$shift = new Vector3d();

    @Inject(method = "render", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$alignSubLevelParticles(LightTexture lightTexture, Camera camera, float partialTick,
            CallbackInfo ci) {
        this.dimblend$shiftedParticles.clear();
        this.dimblend$shiftAmounts.clear();
        double maxShift = 0.0;
        for (Queue<Particle> queue : this.particles.values()) {
            for (Particle particle : queue) {
                if (!(particle instanceof ParticleExtension extension)) {
                    continue;
                }
                SubLevel tracked = extension.sable$getTrackingSubLevel();
                if (!(tracked instanceof ClientSubLevel subLevel) || tracked.isRemoved()) {
                    continue;
                }
                if (!(particle instanceof RenderPoseShiftable shiftable)) {
                    continue;
                }
                if (!SubLevelParticleRenderPose.shiftFor(shiftable, subLevel, partialTick, this.dimblend$shift)) {
                    continue;
                }
                double dx = this.dimblend$shift.x;
                double dy = this.dimblend$shift.y;
                double dz = this.dimblend$shift.z;
                shiftable.dimblend$shiftRenderPos(dx, dy, dz);
                this.dimblend$shiftedParticles.add(particle);
                this.dimblend$shiftAmounts.add(dx);
                this.dimblend$shiftAmounts.add(dy);
                this.dimblend$shiftAmounts.add(dz);
                maxShift = Math.max(maxShift, this.dimblend$shift.length());
            }
        }
        if (!this.dimblend$shiftedParticles.isEmpty()) {
            SableAnimationDiagnostics.particleShift(this.dimblend$shiftedParticles.size(), maxShift);
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 1, allow = 1)
    private void dimblend$restoreSubLevelParticles(LightTexture lightTexture, Camera camera, float partialTick,
            CallbackInfo ci) {
        for (int i = 0, amount = 0; i < this.dimblend$shiftedParticles.size(); i++, amount += 3) {
            ((RenderPoseShiftable) this.dimblend$shiftedParticles.get(i)).dimblend$shiftRenderPos(
                    -this.dimblend$shiftAmounts.getDouble(amount),
                    -this.dimblend$shiftAmounts.getDouble(amount + 1),
                    -this.dimblend$shiftAmounts.getDouble(amount + 2));
        }
        this.dimblend$shiftedParticles.clear();
        this.dimblend$shiftAmounts.clear();
    }
}
