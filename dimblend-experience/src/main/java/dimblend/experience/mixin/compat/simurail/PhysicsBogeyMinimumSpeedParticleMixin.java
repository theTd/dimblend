package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.compat.simurail.ClientMinimumSpeedState;
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
 * 「最低速度机制主动推车」期间的转向架暴击粒子（2026-11-25 LOCKED #3）。
 *
 * <p>服务端把「最低速度机制正在主动加速推动」的载具集合经
 * {@link dimblend.experience.compat.simurail.MinimumSpeedActivePayload} 同步到
 * {@link ClientMinimumSpeedState}。命中集合的载具在每个物理转向架下方 y-1、xz 5×5 范围内
 * 散发少量 {@code CRIT}（critical_hit）粒子，提示玩家当前正受最低速度牵引推进。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyMinimumSpeedParticleMixin {

    private static final double PARTICLE_LIFT = 0.3;
    private static final float DENSITY = 0.12F;
    private static final double RISE_SPEED = 0.02;

    @Inject(method = "tick()V", at = @At("RETURN"), remap = false, require = 1, allow = 1)
    private void dimblend$minimumSpeedParticles(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (!self.getLevel().isClientSide()) {
            return;
        }
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return;
        }
        SubLevel subLevel = Sable.HELPER.getContaining(self);
        if (subLevel == null || !ClientMinimumSpeedState.isActive(world, subLevel.getUniqueId())) {
            return;
        }
        BlockPos local = self.getBlockPos();
        Vec3 center = subLevel.logicalPose()
                .transformPosition(new Vec3(local.getX() + 0.5, local.getY() + 0.5, local.getZ() + 0.5));
        double baseY = center.y - 1.0 + PARTICLE_LIFT;
        int baseX = (int) Math.floor(center.x);
        int baseZ = (int) Math.floor(center.z);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (world.random.nextFloat() >= DENSITY) {
                    continue;
                }
                world.addParticle(ParticleTypes.CRIT,
                        baseX + dx + 0.5 + (world.random.nextDouble() - 0.5) * 0.6,
                        baseY,
                        baseZ + dz + 0.5 + (world.random.nextDouble() - 0.5) * 0.6,
                        0.0, RISE_SPEED, 0.0);
            }
        }
    }
}
