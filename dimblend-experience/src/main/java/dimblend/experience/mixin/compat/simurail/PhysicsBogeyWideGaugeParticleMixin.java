package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E6 幻纱宽轨末地烛粒子（纯客户端视觉，与行进声同粒度：按转向架）。
 *
 * <p>每客户端 tick：取转向架所在子层级，把其局部坐标经
 * {@code logicalPose().transformPosition} 换到世界坐标；脚下（y-1）是
 * 幻纱宽轨（{@code railways:track_phantom_wide}，注册表名精确比对、无 Railways
 * 编译依赖；其他宽轨不播）时，在 y-1 上方 3x3 平面生成下落的末地烛粒子
 * （{@code END_ROD} 加下坠初速，每格每 tick 20% 概率，动静都播）。
 * 世界坐标查询与粒子生成走客户端世界（{@code Minecraft.level}），
 * 转向架 level（子层级门面）只用来取局部坐标与子层级归属。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyWideGaugeParticleMixin {

    /** 粒子抬升：y-1 平面上方，避免埋进轨道里看不见。 */
    private static final double PARTICLE_LIFT = 0.3;
    /** 每格每 tick 生成概率（9 格期望约 1.8 个/tick/车架）。 */
    private static final float DENSITY = 0.2F;
    private static final double FALL_SPEED = -0.25;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void dimblend$wideGaugeParticles(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (!self.getLevel().isClientSide()) {
            return;
        }
        if (!Config.WIDE_GAUGE_PARTICLES.get()) {
            return;
        }
        SubLevel subLevel = Sable.HELPER.getContaining(self);
        if (subLevel == null) {
            return;
        }
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return;
        }
        BlockPos local = self.getBlockPos();
        Vec3 center = subLevel.logicalPose()
                .transformPosition(new Vec3(local.getX() + 0.5, local.getY() + 0.5, local.getZ() + 0.5));
        BlockPos below = BlockPos.containing(center.x, center.y - 1.0, center.z);
        if (!isWideGaugeTrack(world.getBlockState(below).getBlock())) {
            return;
        }
        double baseY = center.y - 1.0 + PARTICLE_LIFT;
        int baseX = (int) Math.floor(center.x);
        int baseZ = (int) Math.floor(center.z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (world.random.nextFloat() >= DENSITY) {
                    continue;
                }
                world.addParticle(ParticleTypes.END_ROD,
                        baseX + dx + 0.5 + (world.random.nextDouble() - 0.5) * 0.6,
                        baseY,
                        baseZ + dz + 0.5 + (world.random.nextDouble() - 0.5) * 0.6,
                        0.0, FALL_SPEED, 0.0);
            }
        }
    }

    /** 幻纱宽轨：{@code railways:track_phantom_wide} 精确匹配（其他宽轨不算，buffer 等非轨不算）。 */
    private static boolean isWideGaugeTrack(net.minecraft.world.level.block.Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return key.getNamespace().equals("railways")
                && key.getPath().equals("track_phantom_wide");
    }
}
