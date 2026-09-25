package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.Config;
import dimblend.experience.client.BogeyTrackSound;
import dimblend.experience.client.TrainLoopLeader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E2 转向架行进声（客户端，整车单源、每秒一条脉冲）。
 *
 * <p>字节码事实（运行 jar 0.0.0-a 反汇编）：{@code tick()V} 双端执行——
 * 服务端分支从车轴聚合 visualSpeed 并在变化时发 {@code PhysicsBogeyRenderDataPacket}；
 * 客户端分支用同步来的 visualSpeed 推进渲染插值。客户端 visualSpeed 经
 * {@code updateRenderData} 落字段，故客户端 tick 末可直接 @Shadow 读取。</p>
 *
 * <p>整车只由 leader 起脉冲（2026-09-24 实测：此前每架一条 30 秒循环叠播失败；
 * 2026-09-24 改短促素材后间隔改为固定 1 秒、不随音调拉伸）：每客户端 tick 先登记
 * {@link TrainLoopLeader}，只有集群 leader 按<b>集群最大速度</b>驱动包络；
 * 非 leader 压住不起播、已有包络淡出；配置关闭或失速同样淡出自停并回收。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyTrackSoundMixin {

    @Shadow(remap = false)
    protected double visualSpeed;

    @Unique
    private BogeyTrackSound.Driver dimblend$trackDriver;

    @Unique
    private int dimblend$pulseCooldown;

    @Inject(method = "tick()V", at = @At("RETURN"))
    private void dimblend$trackSoundTick(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (!self.getLevel().isClientSide()) {
            return;
        }
        if (dimblend$trackDriver != null && dimblend$trackDriver.isStopped()) {
            dimblend$trackDriver = null;
            dimblend$pulseCooldown = 0;
        }
        SubLevel subLevel = Sable.HELPER.getContaining(self);
        BlockPos local = self.getBlockPos();
        Vec3 localCenter = new Vec3(local.getX() + 0.5, local.getY() + 0.5, local.getZ() + 0.5);
        // 世界坐标只用于集群归属判定；播音定位仍走实例内的移动声委托逐帧变换。
        // 写法与粒子 mixin 同型（logicalPose 变换）；服务端的 projectOutOfSubLevel 与之等价，各守各端惯例。
        Vec3 worldPos = subLevel != null
                ? subLevel.logicalPose().transformPosition(localCenter)
                : localCenter;
        if (!Config.TRAIN_SOUNDS.get()) {
            dimblend$fadeAndPulse(self, subLevel, 0);
            TrainLoopLeader.forget(self);
            return;
        }
        double abs = Math.abs(this.visualSpeed);
        long now = self.getLevel().getGameTime();
        TrainLoopLeader.updateMember(self, worldPos, abs,
                self.getLevel().dimension(), subLevel != null ? subLevel.getUniqueId() : null, now);
        if (abs < BogeyTrackSound.MIN_AUDIBLE_SPEED
                || !TrainLoopLeader.isLeader(self, worldPos, abs,
                        self.getLevel().dimension(), subLevel != null ? subLevel.getUniqueId() : null, now)) {
            dimblend$fadeAndPulse(self, subLevel, 0);
            return;
        }
        double drive = Math.max(abs, TrainLoopLeader.clusterSpeed(worldPos,
                subLevel != null ? subLevel.getUniqueId() : null, self.getLevel().dimension(), now));
        if (dimblend$trackDriver == null) {
            dimblend$trackDriver = new BogeyTrackSound.Driver(self);
            dimblend$pulseCooldown = 0;
        }
        dimblend$trackDriver.dimblend$update(drive);
        dimblend$trackDriver.tick();
        dimblend$tryPulse(self, subLevel);
    }

    @Unique
    private void dimblend$fadeAndPulse(PhysicsBogeyBlockEntity self, SubLevel subLevel, double speed) {
        if (dimblend$trackDriver == null) {
            return;
        }
        dimblend$trackDriver.dimblend$update(speed);
        dimblend$trackDriver.tick();
        dimblend$tryPulse(self, subLevel);
    }

    @Unique
    private void dimblend$tryPulse(PhysicsBogeyBlockEntity self, SubLevel subLevel) {
        if (dimblend$trackDriver == null || dimblend$trackDriver.isStopped()) {
            return;
        }
        if (dimblend$pulseCooldown > 0) {
            dimblend$pulseCooldown--;
        }
        if (dimblend$pulseCooldown > 0 || !dimblend$trackDriver.shouldPulse()) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(
                new BogeyTrackSound(self, subLevel, dimblend$trackDriver));
        dimblend$pulseCooldown = BogeyTrackSound.PLAY_INTERVAL_TICKS;
    }
}
