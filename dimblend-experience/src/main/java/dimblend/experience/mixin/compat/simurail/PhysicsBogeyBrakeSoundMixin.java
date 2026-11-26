package dimblend.experience.mixin.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.Sable;
import dimblend.experience.Config;
import dimblend.experience.ModSounds;
import dimblend.experience.compat.simurail.TrainBrakeBroadcast;
import dimblend.experience.compat.simurail.TrainBrakeSoundSync;
import dimblend.experience.compat.simurail.TrainBrakeSustainRules;
import dimblend.experience.compat.simurail.TrainDeadmanBrake;
import dimblend.experience.compat.simurail.TrainSpeedGateMath;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * E3/E4 刹车/松闸气阀触发音（服务端，沿触发整车广播）。
 *
 * <p>字节码事实（运行 jar 0.0.0-a 反汇编）：spec 原拟的
 * {@code getGroupBrakeStrength()} 在新版源码中，<b>运行 jar 只有
 * {@code getBrakeStrength()()D}</b>（单车架强度，源自本车架红石信号；
 * 全 jar 仅 {@code PhysicsBogeyAxle} 施力时调用，无编组级传播），故沿只能在
 * 受电车架自身上看到。用户口径要求沿触发时<b>整列车架都播</b>（2026-09-24 实测：
 * 对单架施闸只有该架响，判失败），故沿触发后经 {@link TrainBrakeBroadcast}
 * 取同列车架的世界坐标（半径 120m + 速度窗 1.5m/s，同速邻车误播为接受项）。</p>
 *
 * <p>触发点挂服务端 {@code tick()V} RETURN（每游戏 tick，主线程），而非
 * {@code sable$physicsTick}：未组装/物理未激活的车架收不到物理回调
 * （物理管线只覆盖已激活子层级），其施闸/松闸沿会被漏掉；tick 分支是超集，
 * 每游戏 tick 常跑且与红石采样同线程，无此问题。</p>
 *
 * <p><b>2026-11-25 Slice 4B：从「定长重触发」改为真 PCM 持续流</b>。服务端只负责
 * 「哪些车架该响 / 该停」：施闸且速度高于门（{@code > max(2 m/s, 配置阈值)}）时下发
 * {@link TrainBrakeSoundSync#sendStart}，客户端用 NeoForge 的
 * {@code SoundInstance#getStream} 在 75% 音节上无缝循环；跌破 2 m/s 由<b>客户端</b>
 * 按实时车速淡出（服务端不发停止包，否则就变成瞬停、丢掉素材自带的衰减尾音）；
 * 松闸沿下发 {@link TrainBrakeSoundSync#sendStop} 立刻收声，同时照原参数播一次
 * {@code brake_release}。施闸期间的保活重发（40 tick）让中途进服的玩家也能听到。</p>
 *
 * <p>播放坐标必须是<b>世界坐标</b>：转向架 BE 的 {@code getBlockPos()} 是子层级
 * 局部坐标（plot 区），服务端 {@code level.playSound} 不做局部→世界路由
 * （sable 的 sublevel_sounds 全是客户端混入；simurail 全 jar 唯一的服务端播音
 * {@code AutomaticCouplerBlockEntity} 同样先调
 * {@code Sable.HELPER.projectOutOfSubLevel} 投影到世界坐标再播——此前直接播
 * 局部坐标，声音落在 plot 区、车旁听不见，即"刹车/松闸无声"的根因，
 * 2026-09-23 双 jar 字节码核实）。未组装（不在子层级）时投影恒等，无副作用。</p>
 *
 * <p><b>2026-11-27 Slice 10：音调变化当 tick 重发</b>。列车正轻刹（{@code pitch = 1.0}）穿过
 * 末影龙龙息的那一 tick，末影锁存成立，音调要降到 0.5；但车架仍在持续鸣响，只盯着 40 tick
 * 保活窗会让客户端一直用 1.0 鸣响到下一次刷新。所以本 tick 记住上次音调，一变就立刻下发
 * 开始包（{@code TrainBrakeSoundSync} 的去重签名已含音调，2 tick 合并窗不会把它当重复吞掉）。</p>
 */
@Mixin(PhysicsBogeyBlockEntity.class)
public abstract class PhysicsBogeyBrakeSoundMixin {

    @Shadow(remap = false)
    protected double visualSpeed;

    @Unique
    private static final double EDGE_EPSILON = 1.0E-6;
    /** 松闸音量：维持原参数。 */
    @Unique
    private static final float RELEASE_VOLUME = 1.0F;

    @Unique
    private double dimblend$lastBrake;

    @Unique
    private boolean dimblend$brakeInitialized;

    /** 本车架是否已在持续鸣响（决定何时下发开始包，以及保活重发）。 */
    @Unique
    private boolean dimblend$sustainActive;

    /** 上一次下发开始包用的音调；哨兵值无关，{@link #dimblend$sustainActive} 为假时永不参与比较。 */
    @Unique
    private float dimblend$lastPitch;

    /** 下一次保活重发的游戏 tick。 */
    @Unique
    private long dimblend$nextRefreshTick;

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1, allow = 1)
    private void dimblend$brakeEdgeSound(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        double speed = Math.abs(this.visualSpeed);
        long now = serverLevel.getGameTime();
        TrainBrakeBroadcast.updateMember(self, speed, now);
        double brake = self.getBrakeStrength();
        if (!dimblend$brakeInitialized) {
            // 世界加载瞬间刹车可能已施加：首个服务端 tick 只建立基线不判沿，
            // 避免误播一次刹车音
            dimblend$lastBrake = brake;
            dimblend$brakeInitialized = true;
            dimblend$sustainActive = false;
            return;
        }
        double last = dimblend$lastBrake;
        dimblend$lastBrake = brake;
        Vec3 world = Sable.HELPER.projectOutOfSubLevel(serverLevel, self.getBlockPos().getCenter());

        // E4 松闸：立刻收掉持续鸣响（客户端 5ms 归零 + 引擎瞬停），并照原参数播排气声。
        // 排气声不受速度门控（原口径）。
        if (last > EDGE_EPSILON && brake <= EDGE_EPSILON) {
            dimblend$stopSustain(self, serverLevel, world, speed, now);
            dimblend$broadcast(serverLevel, world, speed, ModSounds.BRAKE_RELEASE.get(),
                    RELEASE_VOLUME, 1.0F, now);
            return;
        }

        // 音效总开关关掉：把已经响着的收掉（否则客户端会一直循环到下一次松闸）。
        if (!Config.TRAIN_SOUNDS.get()) {
            if (dimblend$sustainActive) {
                dimblend$stopSustain(self, serverLevel, world, speed, now);
            }
            dimblend$sustainActive = false;
            dimblend$nextRefreshTick = 0L;
            return;
        }

        // 可闻条件：施闸中、速度高于 max(2 m/s, 配置阈值)。
        boolean audible = TrainBrakeSustainRules.shouldSustain(brake, speed, true)
                && TrainSpeedGateMath.isBrakeAudible(speed, Config.TRAIN_BRAKE_SOUND_SPEED_THRESHOLD.get());
        if (!audible) {
            // 速度跌破 2 m/s：不发停止包，客户端按实时车速自己淡出（素材自带的 25% 尾音
            // 才是用户要的"因车速不同、刹车音长度也不同"）。速度再抬起来会从这里重新起播。
            dimblend$sustainActive = false;
            dimblend$nextRefreshTick = 0L;
            return;
        }

        // 2026-11-04：无人值守锁存引起的那一次施闸是「急刹」，音调降到 0.5；
        // 司机自己施闸仍按原音调（口径见 TrainSpeedGateMath#brakePitch）。
        float pitch = TrainSpeedGateMath.brakePitch(TrainDeadmanBrake.isLatched(self));
        // 2026-11-27 Slice 10：音调变化必须当 tick 生效。轻刹（1.0）穿进末影龙龙息的那一
        // tick 末端影锁存就成立，车架仍处于持续鸣响（sustainActive = true），若只等 40 tick
        // 保活窗重发，客户端会用 1.0 一直鸣响到下一次刷新；服务端这边的去重签名已把音调算进去，
        // 所以这里立刻发一次开始包不会被 2 tick 合并窗吞掉。
        boolean pitchChanged = dimblend$sustainActive && pitch != dimblend$lastPitch;
        boolean refreshDue = dimblend$sustainActive && now >= dimblend$nextRefreshTick;
        if (!dimblend$sustainActive || refreshDue || pitchChanged) {
            BlockPos selfPos = self.getBlockPos();
            List<BlockPos> members =
                    TrainBrakeBroadcast.collectMembers(serverLevel, world, speed, now);
            if (members.isEmpty()) {
                members = List.of(selfPos);
            }
            TrainBrakeSoundSync.sendStart(serverLevel, world, members,
                    TrainBrakeSustainRules.SUSTAIN_VOLUME, pitch);
            dimblend$sustainActive = true;
            dimblend$lastPitch = pitch;
            dimblend$nextRefreshTick = TrainBrakeSustainRules.nextRefreshTick(now);
        }
    }

    /**
     * 车架被移除/区块卸载：{@code SmartBlockEntity#setRemoved} 是 final 且会调
     * {@code invalidate()}，而 {@code PhysicsBogeyBlockEntity} 覆写了它（运行 jar
     * 字节码核实：{@code public void invalidate()V}），所以这里是"车架消失"的确定钩子。
     * 客户端 {@code TrainBrakeSoundInstance} 也会在自己的 tick 里发现 BE 不见了，
     * 两道保险都留着。
     */
    @Inject(method = "invalidate()V", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$stopBrakeSoundOnRemove(CallbackInfo ci) {
        PhysicsBogeyBlockEntity self = (PhysicsBogeyBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        Vec3 world = Sable.HELPER.projectOutOfSubLevel(serverLevel, self.getBlockPos().getCenter());
        TrainBrakeSoundSync.sendStop(serverLevel, world, List.of(self.getBlockPos()));
        TrainBrakeBroadcast.forget(self);
        dimblend$sustainActive = false;
        dimblend$nextRefreshTick = 0L;
    }

    @Unique
    private void dimblend$stopSustain(PhysicsBogeyBlockEntity self, ServerLevel level, Vec3 world,
                                      double speed, long now) {
        BlockPos selfPos = self.getBlockPos();
        List<BlockPos> members = TrainBrakeBroadcast.collectMembers(level, world, speed, now);
        if (members.isEmpty()) {
            members = List.of(selfPos);
        }
        TrainBrakeSoundSync.sendStop(level, world, members);
        dimblend$sustainActive = false;
        dimblend$nextRefreshTick = 0L;
    }

    @Unique
    private static void dimblend$broadcast(ServerLevel level, Vec3 world, double speed,
                                           SoundEvent event, float volume, float pitch, long nowTick) {
        // 车勾同型：局部中心先投影到世界坐标再播，否则声音落在 plot 区
        TrainBrakeBroadcast.broadcast(level, world, speed, event, volume, pitch, nowTick);
    }
}
