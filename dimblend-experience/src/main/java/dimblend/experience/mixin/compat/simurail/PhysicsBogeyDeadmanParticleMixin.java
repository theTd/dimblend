package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.compat.simurail.ClientDeadmanLatch;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E7' 无人值守刹车锁存粒子（纯客户端视觉，与 E6 同粒度：按转向架）。
 *
 * <p>锁存是服务端状态，经 {@code DeadmanLatchPayload} S2C 同步到
 * {@link ClientDeadmanLatch}；当前世界维度处于锁存时，每个转向架按 E6
 * 幻纱宽轨粒子的同一组参数（y-1 上方 3x3 平面、每格每 tick 20% 概率、
 * 下坠初速）生成 {@code PORTAL} 粒子——锁存期间全维度常播，与列车动停无关。
 * 未组装（不在子层级）的转向架直接取方块中心。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyDeadmanParticleMixin {

    // 以下三参数与 E6（PhysicsBogeyWideGaugeParticleMixin）保持一致，仅粒子 id 不同
    /** 粒子抬升：y-1 平面上方，避免埋进轨道里看不见。 */
    private static final double PARTICLE_LIFT = 0.3;
    /** 每格每 tick 生成概率（9 格期望约 1.8 个/tick/车架）。 */
    private static final float DENSITY = 0.2F;
    private static final double FALL_SPEED = -0.25;

    @Inject(method = "tick()V", at = @At("RETURN"), remap = false)
    private void dimblend$deadmanLatchParticles(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (!self.getLevel().isClientSide()) {
            return;
        }
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null || !ClientDeadmanLatch.isLatched(world.dimension())) {
            return;
        }
        SubLevel subLevel = Sable.HELPER.getContaining(self);
        Vec3 center;
        if (subLevel != null) {
            BlockPos local = self.getBlockPos();
            center = subLevel.logicalPose()
                    .transformPosition(new Vec3(local.getX() + 0.5, local.getY() + 0.5, local.getZ() + 0.5));
        } else {
            center = self.getBlockPos().getCenter();
        }
        double baseY = center.y - 1.0 + PARTICLE_LIFT;
        int baseX = (int) Math.floor(center.x);
        int baseZ = (int) Math.floor(center.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (world.random.nextFloat() >= DENSITY) {
                    continue;
                }
                world.addParticle(ParticleTypes.PORTAL,
                        baseX + dx + 0.5 + (world.random.nextDouble() - 0.5) * 0.6,
                        baseY,
                        baseZ + dz + 0.5 + (world.random.nextDouble() - 0.5) * 0.6,
                        0.0, FALL_SPEED, 0.0);
            }
        }
    }
}
