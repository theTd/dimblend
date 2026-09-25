package dimblend.experience.mixin.compat.cca;

import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.ModSounds;
import dimblend.experience.client.MotorLoopSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * D4 自定义马达音效（客户端三态：启动 → 运转脉冲 → 停转）。
 *
 * <p>挂在 {@code tickAudio()V} HEAD（Create KineticBlockEntity 客户端分支每 tick
 * 调用，纯客户端方法，既有复核结论）。运转判据：{@code |实际转速| > 0 且 |面板| > 64rpm}，
 * 挂 {@link Config#ELECTRIC_MOTOR_BEHAVIOR}。
 * 进入运转态：单次 {@code electric_motor_startup} 立即播放，
 * 延迟 60 tick（3 秒）后起运转音，此后<b>每 5 tick（{@code PULSE_INTERVAL_TICKS}）
 * 起一条单次 {@link MotorLoopSound}</b>，间隔与素材时长无关（2026-09-24 用户要求
 * "忽略音效长度、素材不变"，间隔由每秒一次改为每 5 tick：1.32 秒素材约 5-6 条同时叠播，
 * 不截断、代码无淡入淡出）；
 * 计时由本状态机每 tick 推进，延迟内信号消失则取消并按停机处理；
 * 离开运转态：掐断所有在播运转音 + 单次 {@code electric_motor_stopping}。
 * 三处音量均为 0.5（减半），可听范围约 16 格（one-shot 靠 BlockPos 构造的
 * 线性衰减 + sounds.json {@code attenuation_distance=16}；运转音靠
 * {@link MotorLoopSound} 的显式 LINEAR + 同名条目；素材须单声道，立体声不做距离衰减）。
 * 原版 CCA 音效在 common 侧 {@link ElectricMotorMixin}（静音替代）恢复/屏蔽。</p>
 *
 * <p><b>one-shot 句柄（startup/stopping 长素材治标）</b>：两者均为
 * {@code SimpleSoundInstance} 一次性实例，播出后无自停——长素材（startup 约 10 秒）
 * 会在"启动 1 秒就停"时放满全长并与 stopping 叠加、信号抖动时多重叠加。
 * 故两边沿实例均留句柄：离边时掐断对侧残余（停机掐 startup 残余、再启动掐
 * stopping 残余），BE 移除与配置热关闭时两边全掐。掐断是立即的，无淡出。</p>
 *
 * <p><b>运转判据为何读实际转速而非 CCA 的 {@code active} 字段</b>
 * （2026-09-23 实机回标"未实现"的根因，双 jar 字节码核实）：CCA 1.5.10
 * 电动马达只覆写 {@code writeSafe}（原理图 PartialSafeNBT 路径）写入 active，
 * <b>不覆写 {@code write}</b>；而客户端同步链为 {@code sendData() →
 * SyncedBlockEntity.getUpdateTag → writeClient → write(tag, true)}
 * （Create 6.0.10-281 字节码）——同步包里永远没有 "active" 键，客户端
 * {@code read} 里 {@code tag.getBoolean("active")} 恒得 false。原实现读
 * shadow 的 {@code active} 导致状态机永不进入运转态，三个音效全不播。
 * {@code motorSpeed} 字段同样不在同步包内；唯一按实际值同步的是
 * KineticBlockEntity.write 的 "Speed" 键（读回 {@code speed = getFloat("Speed")}），
 * 即 D2 映射后的实际输出转速（含方向换算）：信号在场=非零、信号消失=归零，
 * 恰为三态音效所需边沿。读取走 public 的 {@code getTheoreticalSpeed()}
 * （直接返回同步字段，不经 getSpeed() 的过载/冻结归零）——不用 @Shadow：
 * mixin 的 findAliasedField 只解析目标类自声明字段，父类字段 shadow
 * 会在 apply 期抛 InvalidMixinException（运行库 sponge-mixin
 * 0.15.4+mixin.0.8.7 fork 及上游 0.8.x 字节码核实）。</p>
 *
 * <p><b>接受的边界</b>：① 马达被更强动力源反拖（hasSource 且非本机出力）时
 * 实际转速非零而 active=false——轴确在转，运转音照播，不视为缺陷；
 * ② 过载/冻结网络 {@code getSpeed()=0} 但理论转速非零——服务端
 * active 仍 true（照常耗电），运转音继续，与服务端状态一致。</p>
 *
 * <p>清理顺序：BE 移除优先于开关早退——config 运行中热关闭时须先停自定义运转音
 * 并复位状态（否则 MEV 恢复原版声会与遗留运转音双声叠加）。</p>
 */
@Mixin(ElectricMotorBlockEntity.class)
public abstract class ElectricMotorSoundClientMixin {

    private static final float MIN_PANEL_RPM = 64.0F;
    /** 启动音后延迟起运转音的时长：3 秒 = 60 tick（tickAudio 每客户端 tick 调用）。 */
    private static final int STARTUP_LOOP_DELAY_TICKS = 60;
    /** 运转音起播间隔：5 tick（0.25 秒），与素材时长无关。 */
    private static final int PULSE_INTERVAL_TICKS = 5;
    /** one-shot 音量：满幅减半（2026-09-23 用户要求；运转音见 MotorLoopSound.LOOP_VOLUME）。 */
    private static final float ONE_SHOT_VOLUME = 0.5F;

    @Shadow(remap = false)
    protected com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour generatedSpeed;

    /** 仍在播的运转音（素材长于间隔时同时存在多条；停机/移除/热关闭时全部掐断）。 */
    @Unique
    private final List<MotorLoopSound> dimblend$pulses = new ArrayList<>();

    @Unique
    private boolean dimblend$running;

    /** 进行中的启动 one-shot（被掐断或被覆盖时归 null；自然播完的陈旧引用残留无害，下次边沿 stop 为空转）。 */
    @Unique
    private SoundInstance dimblend$startup;

    /** 进行中的停机 one-shot（被掐断或被覆盖时归 null；自然播完的陈旧引用残留无害，下次边沿 stop 为空转）。 */
    @Unique
    private SoundInstance dimblend$stopping;

    /** 距下一条运转音的剩余 tick（首条含 3 秒启动延迟）；0 表示未运转。 */
    @Unique
    private int dimblend$nextPulse;

    @Inject(method = "tickAudio()V", at = @At("HEAD"), remap = false)
    private void dimblend$motorSoundStateMachine(CallbackInfo ci) {
        ElectricMotorBlockEntity self = (ElectricMotorBlockEntity) (Object) this;
        if (self.isRemoved()) {
            dimblend$stopPulses();
            dimblend$startup = dimblend$stopped(dimblend$startup);
            dimblend$stopping = dimblend$stopped(dimblend$stopping);
            dimblend$running = false;
            return;
        }
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            // 热关闭：停自定义运转音并复位，原版声由 common 侧 MEV 恢复，避免双声叠加
            dimblend$stopPulses();
            dimblend$startup = dimblend$stopped(dimblend$startup);
            dimblend$stopping = dimblend$stopped(dimblend$stopping);
            dimblend$running = false;
            return;
        }
        // getTheoreticalSpeed() = 同步的 "Speed" 字段原值（无过载/冻结归零）
        boolean want = self.getTheoreticalSpeed() != 0.0F
                && Math.abs(this.generatedSpeed.getValue()) > MIN_PANEL_RPM;
        if (want != dimblend$running) {
            dimblend$running = want;
            BlockPos pos = self.getBlockPos();
            var soundManager = Minecraft.getInstance().getSoundManager();
            if (want) {
                // 再启动时先掐 stopping 残余；startup 理论上已空，防御性同掐
                dimblend$stopping = dimblend$stopped(dimblend$stopping);
                dimblend$startup = dimblend$stopped(dimblend$startup);
                dimblend$startup = new SimpleSoundInstance(ModSounds.ELECTRIC_MOTOR_STARTUP.get(),
                        SoundSource.BLOCKS, ONE_SHOT_VOLUME, 1.0F, SoundInstance.createUnseededRandom(), pos);
                soundManager.play(dimblend$startup);
                // 运转音不立即起：延迟 3 秒，计时在下文每 tick 推进
                dimblend$nextPulse = STARTUP_LOOP_DELAY_TICKS;
            } else {
                dimblend$stopPulses();
                // 停机时掐 startup 残余（长素材治标核心），再播 stopping
                dimblend$startup = dimblend$stopped(dimblend$startup);
                dimblend$stopping = dimblend$stopped(dimblend$stopping);
                dimblend$stopping = new SimpleSoundInstance(ModSounds.ELECTRIC_MOTOR_STOPPING.get(),
                        SoundSource.BLOCKS, ONE_SHOT_VOLUME, 1.0F, SoundInstance.createUnseededRandom(), pos);
                soundManager.play(dimblend$stopping);
            }
            return;
        }
        // 运转维持：计时每 tick 推进，到点起一条运转音并重置为 1 秒间隔；
        // 延迟内信号消失会走上分支（want 翻转）取消待起运转音并播停机音。
        if (want && dimblend$nextPulse > 0 && --dimblend$nextPulse <= 0) {
            var soundManager = Minecraft.getInstance().getSoundManager();
            // 自然播完的实例已离开 SoundEngine，只留仍在播的句柄
            dimblend$pulses.removeIf(p -> !soundManager.isActive(p));
            MotorLoopSound pulse = new MotorLoopSound(ModSounds.ELECTRIC_MOTOR_LOOP.get(), self);
            soundManager.play(pulse);
            dimblend$pulses.add(pulse);
            dimblend$nextPulse = PULSE_INTERVAL_TICKS;
        }
    }

    @Unique
    private void dimblend$stopPulses() {
        dimblend$nextPulse = 0;
        for (MotorLoopSound pulse : dimblend$pulses) {
            pulse.dimblend$stop();
        }
        dimblend$pulses.clear();
    }

    /**
     * 掐断进行中的 one-shot 并归 null（幂等，null 直通）。
     * 经 {@code SoundManager.stop} 按实例精确停止，不影响同事件其他实例。
     */
    @Unique
    private static SoundInstance dimblend$stopped(SoundInstance inst) {
        if (inst != null) {
            Minecraft.getInstance().getSoundManager().stop(inst);
        }
        return null;
    }
}
