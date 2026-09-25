package dimblend.experience.client;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlockEntity;
import dev.ryanhcode.sable.sound.MovingSoundInstanceDelegate;
import dev.ryanhcode.sable.sound.SoundInstanceDelegated;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dimblend.experience.ModSounds;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;

/**
 * E2 转向架行进声单次脉冲（纯客户端）。
 *
 * <p>坐标语义：x/y/z 填<b>子层级局部坐标</b>（转向架在子层级内位置固定），
 * 挂 {@link MovingSoundInstanceDelegate} 后由 sable 每帧经
 * {@code subLevel.logicalPose().transformPosition} 变换到世界坐标并施加多普勒；
 * 不在子层级时（getContaining 返回 null）坐标即世界坐标，退化为普通定点声。</p>
 *
 * <p>素材是短促单次音，由 mixin 每 {@link #PLAY_INTERVAL_TICKS}（1 秒）起一条，
 * 间隔不随音调拉伸。音调仍随速度（2026-09-22 拍板三点）：2m/s→0.5、10m/s→1.0、
 * 16m/s→1.5 分段线性；低于 2m/s 静音、高于 16m/s 钳制 1.5。音量由共享
 * {@link Driver} 包络驱动（满幅 {@code FULL_VOLUME}；注意引擎侧钳制：超出 1.0
 * 的部分只扩大可听距离，不提高近场峰值响度）。整车只有 leader 起脉冲（见
 * {@link TrainLoopLeader}）。</p>
 *
 * <p>停播要快：淡入保持 5 秒（100 tick，spec E2），淡出只走 1 秒
 * （{@code RELEASE_TICKS}，2026-09-24 用户条目"车架停止时停止播放"），
 * 完全静默再确认 1 秒后自停并由 mixin 回收；BE 卸载导致 mixin 不再 tick 时，
 * 孤儿看门狗（{@code ORPHAN_TIMEOUT} 内无 {@link Driver#dimblend$update} 即判孤儿）
 * 同样走快速淡出，避免幽灵循环。</p>
 *
 * <p>淡入式起播必须允许零音量启动：vanilla {@code SoundEngine.play} 在起播计算音量为 0
 * 且 {@code !canStartSilent} 时直接跳过（"Skipped playing sound, volume was zero"），
 * 实例永不进入播放集合——故 {@link #canStartSilent} 恒返 true。</p>
 */
public class BogeyTrackSound extends AbstractTickableSoundInstance implements SoundInstanceDelegated {

    /** 可闻速度下限（m/s，低于此静音）。 */
    public static final double MIN_AUDIBLE_SPEED = 2.0;
    /** 脉冲间隔：1 秒 = 20 tick（起播时刻，与音调无关）。 */
    public static final int PLAY_INTERVAL_TICKS = 20;
    /** 音调映射点：10m/s→1.0（标准）。 */
    private static final double MID_SPEED = 10.0;
    /** 音调映射点：16m/s→1.5（上限，超出钳制）。 */
    private static final double MAX_SPEED = 16.0;
    private static final float LOW_PITCH = 0.5F;
    private static final float MID_PITCH = 1.0F;
    private static final float HIGH_PITCH = 1.5F;
    /** 满音量（v1.2 行进声为基础三倍；2026-09-23 用户条目行进声减半：3.0→1.5）。 */
    private static final float FULL_VOLUME = 1.5F;
    /** 淡入时长：5 秒 = 100 tick（spec E2 口径，起播防爆音）。 */
    private static final int FADE_TICKS = 100;
    /** 淡出时长：1 秒 = 20 tick（2026-09-24 用户条目"车架停止时停止播放"，停播要快）。 */
    private static final int RELEASE_TICKS = 20;
    /** 完全静默后确认自停时长：1 秒 = 20 tick。 */
    private static final int SILENT_CONFIRM_TICKS = 20;
    /** 孤儿判定：超过此时长未收到 mixin 驱动（BE 已卸载），即按停播处理。 */
    private static final long ORPHAN_TIMEOUT_TICKS = 30L;
    /** 淡入步进（0→满幅走满 100 tick）。 */
    private static final float FADE_IN_STEP = FULL_VOLUME / FADE_TICKS;
    /** 淡出步进（满幅→0 走满 20 tick）。 */
    private static final float FADE_OUT_STEP = FULL_VOLUME / RELEASE_TICKS;
    /** 低于此音量不再起新脉冲（仍允许已在播的脉冲跟包络落到 0）。 */
    static final float PULSE_VOLUME_FLOOR = 0.02F;

    private final PhysicsBogeyBlockEntity bogey;
    private final Driver driver;
    private MovingSoundInstanceDelegate delegate;

    public BogeyTrackSound(PhysicsBogeyBlockEntity bogey, SubLevel subLevel, Driver driver) {
        super(ModSounds.BOGEY_TRACK_LOOP.get(), SoundSource.BLOCKS, SoundInstance.createUnseededRandom());
        this.bogey = bogey;
        this.driver = driver;
        BlockPos pos = bogey.getBlockPos();
        // 局部坐标：子层级内固定，世界位移由 delegate 每帧变换
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        this.looping = false;
        this.volume = driver.volume();
        this.pitch = driver.pitch();
        if (subLevel != null) {
            this.delegate = new MovingSoundInstanceDelegate(this, subLevel);
        }
    }

    @Override
    public void tick() {
        if (driver.isStopped() || bogey.isRemoved()) {
            this.stop();
            return;
        }
        this.volume = driver.volume();
        this.pitch = driver.pitch();
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public MovingSoundInstanceDelegate getDelegate() {
        return delegate;
    }

    @Override
    public void setDelegate(MovingSoundInstanceDelegate delegate) {
        this.delegate = delegate;
    }

    /**
     * 行进声包络（不进 SoundManager）。mixin 每客户端 tick 驱动，
     * 按 1 秒间隔用当前音量/音调起 {@link BogeyTrackSound} 脉冲。
     */
    public static final class Driver {

        private final PhysicsBogeyBlockEntity bogey;
        private float volume;
        private float targetVolume;
        private float pitch = MID_PITCH;
        private int silentTicks;
        private long lastDriveTick;
        private boolean stopped;

        public Driver(PhysicsBogeyBlockEntity bogey) {
            this.bogey = bogey;
            this.lastDriveTick = bogey.getLevel().getGameTime();
        }

        /** mixin 每客户端 tick 调用：speed = 集群最大 |visualSpeed|（b/s），0 表示应淡出。 */
        public void dimblend$update(double speed) {
            this.lastDriveTick = bogey.getLevel().getGameTime();
            if (speed < MIN_AUDIBLE_SPEED) {
                this.targetVolume = 0.0F;
                return;
            }
            this.targetVolume = FULL_VOLUME;
            if (speed <= MID_SPEED) {
                this.pitch = (float) (LOW_PITCH + (MID_PITCH - LOW_PITCH)
                        * (speed - MIN_AUDIBLE_SPEED) / (MID_SPEED - MIN_AUDIBLE_SPEED));
            } else if (speed <= MAX_SPEED) {
                this.pitch = (float) (MID_PITCH + (HIGH_PITCH - MID_PITCH)
                        * (speed - MID_SPEED) / (MAX_SPEED - MID_SPEED));
            } else {
                this.pitch = HIGH_PITCH;
            }
        }

        public void tick() {
            if (this.stopped) {
                return;
            }
            if (bogey.isRemoved()) {
                this.stop();
                return;
            }
            if (bogey.getLevel().getGameTime() - this.lastDriveTick > ORPHAN_TIMEOUT_TICKS) {
                this.targetVolume = 0.0F;
            }
            if (this.volume < this.targetVolume) {
                this.volume = Math.min(this.targetVolume, this.volume + FADE_IN_STEP);
            } else if (this.volume > this.targetVolume) {
                this.volume = Math.max(this.targetVolume, this.volume - FADE_OUT_STEP);
            }
            if (this.targetVolume <= 0.0F && this.volume <= PULSE_VOLUME_FLOOR
                    && ++this.silentTicks > SILENT_CONFIRM_TICKS) {
                this.stop();
            } else if (this.targetVolume > 0.0F) {
                this.silentTicks = 0;
            }
        }

        public boolean shouldPulse() {
            return !this.stopped && this.volume > PULSE_VOLUME_FLOOR;
        }

        public boolean isStopped() {
            return this.stopped;
        }

        float volume() {
            return this.volume;
        }

        float pitch() {
            return this.pitch;
        }

        private void stop() {
            this.stopped = true;
            this.volume = 0.0F;
            this.targetVolume = 0.0F;
        }
    }
}
