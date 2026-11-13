package dimblend.experience.mixin.client;

import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.compat.sable.CrushingWheelParticleScope;
import dimblend.experience.compat.sable.RenderPoseShiftable;
import dimblend.experience.compat.sable.SubLevelParticleRenderPose;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 给 {@link Particle} 加一个渲染坐标读写口，供 {@link SableParticleEngineRenderPoseMixin}
 * 把子层级粒子对齐到网格实际使用的渲染位姿。
 *
 * <p>只加方法、不注入任何绘制调用点；{@code x/y/z} 与 {@code xo/yo/zo} 都是 {@code protected}
 * （vanilla {@code Particle.java:24-29}），所以必须由目标类自己的代码读写。</p>
 *
 * <p>唯一的注入点是 {@code Particle#tick} 的 TAIL：记下「这一 tick 是否已在被追踪的子层级下跑过」。
 * tick 0（刚生成、还没被 tick 过）与 tick 1+ 的 {@code xo} 分属不同位姿（{@code logicalPose} vs
 * {@code lastPose}），见 {@link SubLevelParticleRenderPose} 的类注释。若粒子换了追踪子层级
 * （被另一辆载具接管），这里把标记清零，下一帧按 tick 0 口径重算。</p>
 *
 * <p>坐标改写是「这一帧的临时值」：{@link #dimblend$applyRenderPose} 先把六个原始 double 逐位存下，
 * 把 {@code x/y/z} 与 {@code xo/yo/zo} 都设成同一个目标值（{@code Mth.lerp} 于是零漂移），
 * {@link #dimblend$restoreRenderPose} 再逐位还原——模拟位置、速度与包围盒全程不动。</p>
 */
@Mixin(Particle.class)
public abstract class SableParticleRenderPoseMixin implements RenderPoseShiftable, ParticleSubLevelKickable {

    // 仓库纪律：mixin 成员一律 private（@Shadow 只声明目标字段的存在，实际访问走目标类自身，
    // 可见性不必与 vanilla 的 protected 一致）。
    @Shadow
    private double x;
    @Shadow
    private double y;
    @Shadow
    private double z;
    @Shadow
    private double xo;
    @Shadow
    private double yo;
    @Shadow
    private double zo;

    @Unique
    private final Vector3d dimblend$targetPos = new Vector3d();

    /** 上一次观察到 {@code sable$getTrackingSubLevel()} 的对象（按引用比较，只用来判断「换车了没」）。 */
    @Unique
    private Object dimblend$trackedSubLevelRef;
    /** 当前追踪子层级下是否已跑过 {@code Particle#tick}：决定 {@code xo} 属于哪个位姿。 */
    @Unique
    private boolean dimblend$tickedInSubLevel;
    /** 这六个坐标是否正被 {@link #dimblend$applyRenderPose} 改写（还原必须成对）。 */
    @Unique
    private boolean dimblend$shifted;
    @Unique
    private double dimblend$savedX;
    @Unique
    private double dimblend$savedY;
    @Unique
    private double dimblend$savedZ;
    @Unique
    private double dimblend$savedXo;
    @Unique
    private double dimblend$savedYo;
    @Unique
    private double dimblend$savedZo;
    /** Sable 子层级粉碎轮内部生成的粒子：不与粉碎轮控制器碰撞盒互挤，也不因扩散超过 0.5 格脱钩。 */
    @Unique
    private boolean dimblend$crushingWheelParticle;

    @Inject(
            method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDD)V",
            at = @At("TAIL"),
            require = 1,
            allow = 1)
    private void dimblend$markCrushingWheelParticle(
            ClientLevel level, double x, double y, double z, CallbackInfo ci) {
        if (CrushingWheelParticleScope.isActive()) {
            this.dimblend$crushingWheelParticle = true;
        }
    }

    @Override
    public boolean sable$shouldCollideWithTrackingSubLevel() {
        return !this.dimblend$crushingWheelParticle;
    }

    @Override
    public boolean sable$shouldKickFromTracking() {
        return !this.dimblend$crushingWheelParticle;
    }

    @Inject(method = "tick", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$trackSubLevelTick(CallbackInfo ci) {
        final SubLevel tracked = this.dimblend$currentTrackingSubLevel();
        if (tracked != null) {
            this.dimblend$trackedSubLevelRef = tracked;
            this.dimblend$tickedInSubLevel = true;
        } else {
            this.dimblend$trackedSubLevelRef = null;
            this.dimblend$tickedInSubLevel = false;
        }
    }

    @Override
    public boolean dimblend$applyRenderPose(float partialTick) {
        // 防御：上一次若因异常没还原，先把坐标还原回模拟口径再重算，避免在临时值上叠加。
        if (this.dimblend$shifted) {
            this.dimblend$restoreRenderPose();
        }

        final SubLevel tracked = this.dimblend$currentTrackingSubLevel();
        if (tracked == null) {
            this.dimblend$trackedSubLevelRef = null;
            this.dimblend$tickedInSubLevel = false;
            return false;
        }
        if (this.dimblend$trackedSubLevelRef != tracked) {
            // 换了追踪子层级：新载具下这颗粒子一次都还没 tick 过，xo 仍在 logicalPose 里。
            this.dimblend$trackedSubLevelRef = tracked;
            this.dimblend$tickedInSubLevel = false;
        }

        if (!SubLevelParticleRenderPose.applyToParticle((Particle) (Object) this, partialTick,
                this.dimblend$tickedInSubLevel, this.dimblend$targetPos)) {
            return false;
        }

        this.dimblend$savedX = this.x;
        this.dimblend$savedY = this.y;
        this.dimblend$savedZ = this.z;
        this.dimblend$savedXo = this.xo;
        this.dimblend$savedYo = this.yo;
        this.dimblend$savedZo = this.zo;

        // x 与 xo 设成同一个值：粒子 render 里 Mth.lerp(partialTick, xo, x) 于是恒等于目标值，
        // 不随 partialTick 漂移（这正是 tick 0/1+ 不再抖动的关键）。
        this.x = this.dimblend$targetPos.x;
        this.y = this.dimblend$targetPos.y;
        this.z = this.dimblend$targetPos.z;
        this.xo = this.dimblend$targetPos.x;
        this.yo = this.dimblend$targetPos.y;
        this.zo = this.dimblend$targetPos.z;

        this.dimblend$shifted = true;
        return true;
    }

    @Override
    public void dimblend$restoreRenderPose() {
        if (!this.dimblend$shifted) {
            return;
        }
        this.x = this.dimblend$savedX;
        this.y = this.dimblend$savedY;
        this.z = this.dimblend$savedZ;
        this.xo = this.dimblend$savedXo;
        this.yo = this.dimblend$savedYo;
        this.zo = this.dimblend$savedZo;
        this.dimblend$shifted = false;
    }

    @Override
    public void dimblend$readRenderPos(Vector3d dest) {
        dest.set(this.x, this.y, this.z);
    }

    @Override
    public void dimblend$readPreviousRenderPos(Vector3d dest) {
        dest.set(this.xo, this.yo, this.zo);
    }

    @Unique
    private SubLevel dimblend$currentTrackingSubLevel() {
        return this instanceof ParticleExtension extension ? extension.sable$getTrackingSubLevel() : null;
    }
}
