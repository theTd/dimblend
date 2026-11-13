package dimblend.experience.mixin.compat.create;

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

    @Inject(
            method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDDIFFFLnet/minecraft/core/Direction$Axis;IZLnet/minecraft/client/particle/SpriteSet;)V",
            at = @At("TAIL"),
            require = 1,
            allow = 1)
    private void dimblend$detectCrushingWheelOrigin(
            ClientLevel world,
            double x,
            double y,
            double z,
            int color,
            float radius1,
            float radius2,
            float speed,
            Direction.Axis axis,
            int lifeSpan,
            boolean isVisible,
            SpriteSet sprite,
            CallbackInfo ci) {
        if (this.origin == null) {
            return;
        }
        BlockPos originBlock = BlockPos.containing(this.origin);
        if (Sable.HELPER.getContainingClient(originBlock) != null
                && world.getBlockState(originBlock).getBlock() instanceof CrushingWheelBlock) {
            this.dimblend$crushingWheelIndicator = true;
            this.dimblend$readyForWorldProjection = true;
            // 构造器内 move(0,0,0) 只改了 x/y/z 没同步包围盒，先在 plot 局部空间对齐一次，供随后 sable$initialKickOut 转换
            this.setPos(this.x, this.y, this.z);
        }
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
