package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.automatic_coupler.AutomaticCouplerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.simurail.CouplerChargeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E10 扳手充能车钩的红石粒子（纯客户端视觉）。
 *
 * <p>只有<b>扳手</b>充能发粒子，红石信号解锁不发——用户口径「此种类型的充能会使车钩
 * 散发红石粒子效果」。充能态本身由服务端经 BE 更新包同步到客户端（
 * {@code AutomaticCouplerChargeMixin} 的 write/read），这里只读那份同步值。</p>
 *
 * <p>取子层级位姿把绘图区坐标换成世界坐标，与 {@code PhysicsBogeyWideGaugeParticleMixin}
 * 同口径；未组装的散放车钩没有子层级，直接用方块中心。</p>
 */
@Mixin(AutomaticCouplerBlockEntity.class)
public abstract class AutomaticCouplerChargeParticleMixin {

    /** 每 tick 生成概率：约 7 个/秒，够看出"在放电"，又不至于糊住车钩。 */
    private static final float PARTICLE_CHANCE = 0.34F;
    /** 粒子散布半径（格）。 */
    private static final double SPREAD = 0.22D;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void dimblend$chargeParticles(CallbackInfo ci) {
        AutomaticCouplerBlockEntity self = (AutomaticCouplerBlockEntity) (Object) this;
        if (self.getLevel() == null || !self.getLevel().isClientSide()) {
            return;
        }
        if (!(self instanceof CouplerChargeAccess access) || !access.dimblend$wrenchCharged()) {
            return;
        }
        if (!Config.COUPLER_CHARGE_PARTICLES.get()) {
            return;
        }
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return;
        }
        if (world.random.nextFloat() >= PARTICLE_CHANCE) {
            return;
        }
        Vec3 center = self.getBlockPos().getCenter();
        SubLevel subLevel = Sable.HELPER.getContaining(self);
        if (subLevel != null) {
            center = subLevel.logicalPose().transformPosition(center);
        }
        world.addParticle(DustParticleOptions.REDSTONE,
                center.x + (world.random.nextDouble() - 0.5D) * SPREAD,
                center.y + (world.random.nextDouble() - 0.5D) * SPREAD,
                center.z + (world.random.nextDouble() - 0.5D) * SPREAD,
                0.0D, 0.0D, 0.0D);
    }
}