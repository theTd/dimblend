package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyAxle;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.compat.simurail.MinimumSpeedBridge;
import dimblend.experience.compat.simurail.PhysicsBogeyBrakeHoldRules;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 制动保持的<b>车轴力层</b>：施闸且速度 &lt; 1 m/s 时切断驱动力的来源项（Item 7.1，2026-11-25）。
 *
 * <p>为什么单靠 {@code PhysicsBogeyZeroHoldMixin}（物理步末尾清速度）还不够看：车轴在
 * 轨上施力时会把反作用冲量也打给<b>轨道子层级</b>
 * （{@code PhysicsBogeyAxle#updateTrackForces} 字节码偏移 399 的第二条
 * {@code applyImpulseAtPoint}），清速度只清得掉自己那具刚体，清不掉被推的轨道体；
 * 而且低速时 {@code Math.clamp(speed,-1,1)} 的制动力与驱动力同阶，驱动项会让每次物理步
 * 都在 0 附近顶一下。把驱动项归零之后，"刹住"就不必靠事后对冲。</p>
 *
 * <p>实现选点（运行 jar {@code simurail-1.21.1-0.0.0-a+ecd2dd3.jar} 的
 * {@code javap -p -c} 核实）：两条施力路径 {@code updateTrackForces} 与
 * {@code updateWorldForces} 里各只有<b>一处</b>
 * {@code invokevirtual PhysicsBogeyBlockEntity.calculateStressApplied()F}
 * （偏移 213 / 582），它唯一落点就是 {@code driveMag}：
 * {@code min(|diffSpeed|, |targetSpeed|) * stress * axleDriveForceFactor}。
 * 把 {@code stress} 改为 0 即 {@code driveForce = 0}，而
 * {@code brakeForce}、目标速度与 {@code visualSpeed} 全部不受影响——
 * 比包 {@code Math.min} 稳（{@code updateWorldForces} 里还有两处区块边界用的
 * {@code Math.min}），也不依赖任何 LVT 名字。</p>
 *
 * <p>判据与 {@code PhysicsBogeyZeroHoldMixin} 完全同源（{@link PhysicsBogeyBrakeHoldRules}
 * +{@link MinimumSpeedBridge} 豁免），因此两层不会出现"一边钉住一边给力"的分歧。</p>
 *
 * <p>单测只覆盖规则本体；本混入必须在实机看车才算验证（见 AGENTS.md 的 mixin 纪律）。</p>
 */
@Mixin(PhysicsBogeyAxle.class)
public abstract class PhysicsBogeyAxleBrakeHoldMixin {

    private static final String TRACK_FORCES =
            "updateTrackForces(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V";
    private static final String WORLD_FORCES =
            "updateWorldForces(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V";
    private static final String STRESS_CALL =
            "Lcom/crystaelix/simurail/content/bogey/PhysicsBogeyBlockEntity;calculateStressApplied()F";

    @WrapOperation(
            method = TRACK_FORCES,
            at = @At(value = "INVOKE", target = STRESS_CALL),
            require = 1,
            allow = 1)
    private float dimblend$cutTrackDrive(PhysicsBogeyBlockEntity bogey, ServerSubLevel subLevel,
                                         RigidBodyHandle handle, double timeStep, Operation<Float> original) {
        return dimblend$driveStress(bogey, subLevel, handle, original.call(bogey));
    }

    @WrapOperation(
            method = WORLD_FORCES,
            at = @At(value = "INVOKE", target = STRESS_CALL),
            require = 1,
            allow = 1)
    private float dimblend$cutWorldDrive(PhysicsBogeyBlockEntity bogey, ServerSubLevel subLevel,
                                         RigidBodyHandle handle, double timeStep, Operation<Float> original) {
        return dimblend$driveStress(bogey, subLevel, handle, original.call(bogey));
    }

    @Unique
    private float dimblend$driveStress(PhysicsBogeyBlockEntity bogey, ServerSubLevel subLevel,
                                       RigidBodyHandle handle, float originalStress) {
        if (bogey == null || subLevel == null || handle == null) {
            return originalStress;
        }
        Vector3dc linear = handle.getLinearVelocity();
        if (linear == null) {
            return originalStress;
        }
        ServerLevel serverLevel = subLevel.getLevel();
        boolean exempt = MinimumSpeedBridge.isExemptFromBrakeHold(serverLevel, subLevel.getUniqueId());
        if (!PhysicsBogeyBrakeHoldRules.shouldHold(bogey.getBrakeStrength(), exempt, linear.length())) {
            return originalStress;
        }
        return 0.0F;
    }
}
