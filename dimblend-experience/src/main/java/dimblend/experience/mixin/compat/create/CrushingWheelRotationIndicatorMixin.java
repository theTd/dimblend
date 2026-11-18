package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.base.RotationIndicatorParticle;
import com.simibubi.create.content.kinetics.crusher.CrushingWheelBlock;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.particle.ParticleSubLevelKickable;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SimpleAnimatedParticle;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 修复 Sable 子层级上粉碎轮（含 Design n' Decor 粉碎轮）的旋转指示粒子：
 * {@link RotationIndicatorParticle#move} 直接把 {@code x/y/z} 写回 plot 局部 {@code origin}，
 * 导致第 1 tick 起粒子飞回 {@code ~1.6e7} 的 plot 虚空并脱离载具追踪。
 *
 * <p>构造器在字节码 {@code 173} 处调用 {@code this.move(0, 0, 0)}，此时 Sable 的
 * {@code Particle.<init>} TAIL 刚按 plot 局部坐标挂好 {@code trackingSubLevel}，而 {@code bounds}
 * 也仍是 plot 局部点。这个构造阶段的 {@code move} 会在 {@code readyForWorldProjection == false}
 * 时把局部坐标留给随后的 {@code ParticleEngine#add -> sable$initialKickOut()}，由它一次性把
 * {@code x} 与 {@code xo} 都投到世界空间；同时先把 tracking 暂空，避免任何提前的
 * {@code sable$moveWithSubLevels} 把 plot 坐标当世界坐标解释并触发 {@code sable$kickFromTracking()}。</p>
 *
 * <p>构造完成后的每次 {@code tick -> move} 仍由 TAIL 钩子按 {@code logicalPose()} 投到世界，
 * 与 {@code xo}（上一 tick 的世界坐标）保持插值一致。</p>
 */
@Mixin(RotationIndicatorParticle.class)
public abstract class CrushingWheelRotationIndicatorMixin extends SimpleAnimatedParticle
        implements ParticleSubLevelKickable {

    @Shadow(remap = false)
    private Vec3 origin;

    @Unique
    private boolean dimblend$crushingWheelIndicator;
    @Unique
    private boolean dimblend$readyForWorldProjection;

    private CrushingWheelRotationIndicatorMixin(
            ClientLevel level, double x, double y, double z, SpriteSet sprites, float gravity) {
        super(level, x, y, z, sprites, gravity);
    }

    /**
     * 包住构造器里的 {@code this.move(0, 0, 0)}：先识别粉碎轮原点，再把 {@code trackingSubLevel}
     * 暂空后执行，最后按局部坐标恢复 tracking 与包围盒，让 {@code sable$initialKickOut()} 只投影一次。
     */
    @WrapOperation(
            method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDDIFFFLnet/minecraft/core/Direction$Axis;IZLnet/minecraft/client/particle/SpriteSet;)V",
            at = @At(value = "INVOKE",
                    target = "Lcom/simibubi/create/content/kinetics/base/RotationIndicatorParticle;move(DDD)V"),
            require = 1,
            allow = 1)
    private void dimblend$localConstructorMove(
            RotationIndicatorParticle instance,
            double dx,
            double dy,
            double dz,
            Operation<Void> original) {
        if (!this.dimblend$isCrushingWheelOrigin()) {
            original.call(instance, dx, dy, dz);
            return;
        }
        this.dimblend$crushingWheelIndicator = true;
        this.dimblend$readyForWorldProjection = false;
        final ParticleExtension extension = this instanceof ParticleExtension particle ? particle : null;
        final ClientSubLevel tracked = extension != null
                && extension.sable$getTrackingSubLevel() instanceof ClientSubLevel subLevel
                && !subLevel.isRemoved()
                ? subLevel
                : null;
        if (extension != null && tracked != null) {
            extension.sable$setTrackingSubLevel(null, Vec3.ZERO);
        }
        try {
            original.call(instance, dx, dy, dz);
        } finally {
            if (extension != null && tracked != null) {
                extension.sable$setTrackingSubLevel(tracked, new Vec3(this.x, this.y, this.z));
            }
            this.setPos(this.x, this.y, this.z);
            this.dimblend$readyForWorldProjection = true;
        }
    }

    @Unique
    private boolean dimblend$isCrushingWheelOrigin() {
        if (this.origin == null || this.level == null) {
            return false;
        }
        BlockPos originBlock = BlockPos.containing(this.origin);
        return Sable.HELPER.getContainingClient(originBlock) != null
                && this.level.getBlockState(originBlock).getBlock() instanceof CrushingWheelBlock;
    }

    @Inject(method = "move(DDD)V", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$projectCrushingWheelIndicatorToWorld(
            double dx, double dy, double dz, CallbackInfo ci) {
        if (!this.dimblend$crushingWheelIndicator || !this.dimblend$readyForWorldProjection) {
            return;
        }
        if (!(this instanceof ParticleExtension extension)) {
            return;
        }
        SubLevel tracked = extension.sable$getTrackingSubLevel();
        if (!(tracked instanceof ClientSubLevel subLevel) || subLevel.isRemoved()) {
            return;
        }
        Vec3 worldPos = subLevel.logicalPose().transformPosition(new Vec3(this.x, this.y, this.z));
        this.setPos(worldPos.x, worldPos.y, worldPos.z);
    }

    @Override
    public boolean sable$shouldCollideWithTrackingSubLevel() {
        return !this.dimblend$crushingWheelIndicator;
    }

    @Override
    public boolean sable$shouldKickFromTracking() {
        return !this.dimblend$crushingWheelIndicator;
    }
}
