package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.compat.simurail.MinimumSpeedBridge;
import dimblend.experience.compat.simurail.PhysicsBogeyBrakeHoldRules;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 制动强度 &gt; 0 且编组速度 &lt; 1 m/s 时把载具钉在原地（2026-11-25 LOCKED #2）。
 *
 * <p>在转向架的物理步 {@code sable$physicsTick} 尾部把线速度/角速度整体清零一次。
 * 放在尾部而不是改刹車力：物理步里"{@code Math.clamp(speed,-1,1)} 的刹车力"与速度同阶，
 * 低速段会被牵引力顶开成 0~0.9 m/s 的锯齿；清零是确定性的"停住"。
 * 判据、阈值与豁免全在 {@link PhysicsBogeyBrakeHoldRules}。</p>
 *
 * <p>不豁免载具（例如玩家显式允许的调度/停放）一律交回 Simurail 原物理。
 * 单测只覆盖规则本体；物理步清零必须实机看车才算验证完。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyZeroHoldMixin {

    @Inject(method = "sable$physicsTick", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$holdAtZero(ServerSubLevel subLevel, RigidBodyHandle handle, double timeStep,
            CallbackInfo ci) {
        if (subLevel == null || handle == null) {
            return;
        }
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        Vector3dc linear = handle.getLinearVelocity();
        if (linear == null) {
            return;
        }
        boolean exempt = MinimumSpeedBridge.isExemptFromBrakeHold(serverLevel, subLevel.getUniqueId());
        if (!PhysicsBogeyBrakeHoldRules.shouldHold(self.getBrakeStrength(), exempt, linear.length())) {
            return;
        }
        handle.addLinearAndAngularVelocity(new Vector3d(linear).negate(), negateAngular(handle));
    }

    @Unique
    private static Vector3d negateAngular(RigidBodyHandle handle) {
        Vector3dc angular = handle.getAngularVelocity();
        return angular == null ? new Vector3d() : new Vector3d(angular).negate();
    }
}
