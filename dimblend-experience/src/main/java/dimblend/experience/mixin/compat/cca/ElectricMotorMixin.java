package dimblend.experience.mixin.compat.cca;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlockEntity;
import dimblend.experience.Config;
import dimblend.experience.compat.cca.MotorOverstressLatch;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * D 板块 CCA 电动马达（字节码基线：运行 jar 1.5.10，证据经复核员独立反汇编
 * 二次核实）。
 *
 * <p><b>字节码事实（复核员第 2 轮修正后的权威口径）</b>：</p>
 * <ul>
 * <li>motorSpeed 的写入路径：无参 {@code updateGeneratedRotation()V}（父类
 * GeneratingKineticBlockEntity 声明、目标类未重写）有 5 个调用点（initialize、
 * tick 首帧、CC setRPM 后传播、active 启动、active 停转），全部在目标类内以
 * invokevirtual 直写；带参 {@code updateGeneratedRotation(I)V}（滚动值回调）
 * 先 putfield motorSpeed=newSpeed 再 invokespecial super 无参版。</li>
 * <li>因此**类级 HEAD 注入 updateGeneratedRotation()V 无法 apply**（该方法是
 * 父类声明，Mixin 0.8.5 只遍历目标类自身 methods）——必须用<b>调用点级注入</b>
 * （INVOKE at 每个传播点前）。</li>
 * <li>原版红石语义：POWERED = hasNeighborSignal（全 6 面，neighborChanged 维护），
 * active 启动/维持均为 {@code !POWERED}——"有信号=停转"。D1 需求为"收到信号才
 * 运转"，故两处 POWERED 读取需<b>反转</b>为"信号在场才运转"。</li>
 * </ul>
 *
 * <p><b>实现</b>：</p>
 * <ul>
 * <li>D1 反转：tick() 内两处 POWERED 读取（启动判据/停转判据）替换为
 * {@code 信号强度<=0}——信号在场才满足原"无 POWERED"分支。Config 关闭透传原版。
 * D7 锁存期维持判据旁路为 false（信号撤除也不停转）。</li>
 * <li>D2 映射：全部传播点前 recompute——panel 取 {@code generatedSpeed.getValue()}
 * （面板权威设定值，CC setRPM 亦先 setValue 落到面板，全路径一致）；
 * signal 1~15 → sign×(4 + (signal-1)×(|panel|-4)/14)，封顶 |panel|，无 cancel
 * 无递归；|panel|≤4 直通面板；signal≤0 → motorSpeed=0。
 * D7 锁存期 recompute 统一旁路为恢复冻结值（面板/信号/CC 全丢弃）。</li>
 * <li>D3 纯线性能耗：getEnergyConsumptionRate 内 Math.max(DD) 的 MINIMUM_
 * CONSUMPTION 分支重定向为返回线性项（"低于 8 按 8 计"消除），受 Config 门控。
 * D7 锁存期 tick 内调用点结果×2（实时 rate×2，冻结值故数值恒定）。</li>
 * <li>D4 原版音效静音替代（素材已到位，2026-09-19 接入）：行为开关开启时
 * tickAudio 的 active 读取压为 false——CCA 自带运转声整体不播，由 client 侧
 * {@code ElectricMotorSoundClientMixin} 三态自定义音效（startup/loop/stopping，
 * |面板|&gt;64rpm 门控）取代；开关关闭透传原版（经 CASoundScapes 每 tick
 * 重新贡献，恢复即自然恢复，无内部状态机残留——复核字节码核实）。</li>
 * <li>D7 过载锁存（2026-09-26 新条目）：kinetic 过载（isOverStressed）且|面板|&gt;64
 * 且运转中（active 且理论转速非零）→ 记录红石强度并冻结输出，期间一切外部更改
 * （信号增减/撤除、面板扳手、CC setRPM）经 recompute 统一旁路直接丢弃；D1 维持判据
 * 旁路（撤信号不停转）；耗电×2；服务端每 tick 中心 enchanted_hit×5
 * （delta 0/speed 0.5）+ 每 20 tick motor_overstress（音量 0.5，与 D4 叠加）。
 * 退出：过载恢复（HEAD 判）或 active=false（FE 耗尽，TAIL 补判）任一即清锁存；
 * 纯内存不写 NBT，区块卸载/重进按新进入重判；开关关闭清锁存透传原版。</li>
 * </ul>
 */
@Mixin(ElectricMotorBlockEntity.class)
public abstract class ElectricMotorMixin {

    @Unique
    private static final int dimblend$MIN_RPM = 4;
    @Unique
    private static final int dimblend$RATED_SIGNAL = 15;

    /** D7 过载锁存（纯内存，不写 NBT；区块卸载/重进按新进入重判）。 */
    @Unique
    private boolean dimblend$overstressLatched;
    /** D7 进入瞬间记录的红石强度（1~15，仅记录；输出冻结已涵盖其效果）。 */
    @Unique
    private int dimblend$latchedSignal;
    /** D7 进入瞬间的冻结输出转速（期间一切外部更改直接丢弃，只恢复此值）。 */
    @Unique
    private float dimblend$latchedSpeed;
    /** D7 锁存持续 tick 数（粒子每 tick，音效在 age%20==0 时播，含进入当 tick）。 */
    @Unique
    private int dimblend$latchAge;

    @Shadow
    protected float motorSpeed;

    @Shadow
    private boolean active;

    /** 面板权威设定值的 Shadow（getRPM 读的是 motorSpeed 已映射值，不能用）。 */
    @Shadow(remap = false)
    protected com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour generatedSpeed;
    /**
     * D1 反转 + D2 映射：在全部传播点前重算 motorSpeed。
     * tick() 内共 4 处无参调用（首帧/CC setRPM 传播/active 启动/active 停转，
     * 字节码 offset 24/67/134/179），initialize() 内 1 处另由独立注入覆盖——
     * 本 selector 一个匹配 tick 内全部 4 处。
     * recompute 为幂等直写：同 tick 多次传播/多调用点无叠加、无递归。
     */
    @Inject(method = "tick()V", at = @At(value = "INVOKE", target = "Lcom/mrh0/createaddition/blocks/electric_motor/ElectricMotorBlockEntity;updateGeneratedRotation()V"))
    public void dimblend$recomputeBeforePropagate(CallbackInfo ci) {
        dimblend$recompute();
    }

    /**
     * D2 实时随动（v1.2）：原实现只在 4 个边沿传播点重算，稳态运行信号变化不跟。
     * 每服务端 tick 重算一次，motorSpeed 变化超 ε 才调 updateGeneratedRotation()
     * 传播——该方法每次调用都 sendData，无门控会每 tick 刷屏。
     *
     * <p>D7 过载锁存驱动（同方法内优先处理）：锁存中 → 退出判据（过载恢复）则清锁存
     * 并落到常规重算（本 tick 即恢复跟随）；否则冻结输出、播粒子/音效并 return
     * （跳过常规重算与传播——CCA 主体仍执行，耗电按冻结值×2见
     * {@code dimblend$doubleConsumptionWhenLatched}，D1 维持判据旁路见
     * {@code dimblend$stayActiveOnSignal}）。未锁存但满足进入判据 → 锁存并冻结，
     * 当 tick 即播副作用。开关关闭清锁存透传原版。active=false 的退出（FE 耗尽）
     * 由 CCA 主体在本 tick 后半执行、HEAD 读到旧值，故在 TAIL
     * （{@code dimblend$resetLatchOnPowerLoss}）补判。
     */
    @Unique
    private static final float dimblend$PROPAGATE_EPSILON = 1.0E-3F;

    @Inject(method = "tick()V", at = @At("HEAD"))
    public void dimblend$followSignalLive(CallbackInfo ci) {
        ElectricMotorBlockEntity self = (ElectricMotorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            dimblend$clearLatch();
            return;
        }
        if (!Config.ELECTRIC_MOTOR_OVERLOAD.get()) {
            dimblend$clearLatch();
        }
        if (dimblend$overstressLatched) {
            if (MotorOverstressLatch.shouldReset(self.isOverStressed(), this.active)) {
                dimblend$clearLatch();
            } else {
                this.motorSpeed = dimblend$latchedSpeed;
                MotorOverstressLatch.tickEffects(serverLevel, self.getBlockPos(), dimblend$latchAge);
                dimblend$latchAge++;
                return;
            }
        } else if (Config.ELECTRIC_MOTOR_OVERLOAD.get() && MotorOverstressLatch.shouldEnter(self.isOverStressed(),
                this.generatedSpeed.getValue(), this.active, self.getTheoreticalSpeed())) {
            dimblend$overstressLatched = true;
            dimblend$latchedSignal = dimblend$analogSignal(self);
            float frozen = this.motorSpeed;
            if (frozen == 0.0F) {
                dimblend$recompute();
                frozen = this.motorSpeed;
            }
            dimblend$latchedSpeed = frozen;
            dimblend$latchAge = 0;
            MotorOverstressLatch.tickEffects(serverLevel, self.getBlockPos(), dimblend$latchAge);
            dimblend$latchAge++;
            return;
        }
        float before = this.motorSpeed;
        dimblend$recompute();
        if (Math.abs(this.motorSpeed - before) > dimblend$PROPAGATE_EPSILON) {
            self.updateGeneratedRotation();
        }
    }

    /**
     * D7 退出 TAIL 补判：CCA 主体在本 tick 内把 active 拉成 false（FE 耗尽断电）
     * 时 HEAD 读到的是旧 active=true，只有 TAIL 能看到新值。过载恢复的退出在 HEAD
     * 已处理（updateFromNetwork 系 tick 外异步事件）；TAIL 只补 active=false。
     */
    @Inject(method = "tick()V", at = @At("TAIL"))
    public void dimblend$resetLatchOnPowerLoss(CallbackInfo ci) {
        ElectricMotorBlockEntity self = (ElectricMotorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return;
        }
        if ((!Config.ELECTRIC_MOTOR_BEHAVIOR.get() || !Config.ELECTRIC_MOTOR_OVERLOAD.get()
                || !this.active) && dimblend$overstressLatched) {
            dimblend$clearLatch();
        }
    }

    /** D7 清锁存（输出/耗电/音/粒子下 tick 即恢复常规；不碰 motorSpeed，常规重算接管）。 */
    @Unique
    private void dimblend$clearLatch() {
        if (!dimblend$overstressLatched) {
            return;
        }
        dimblend$overstressLatched = false;
        dimblend$latchedSignal = 0;
        dimblend$latchedSpeed = 0.0F;
        dimblend$latchAge = 0;
    }

    /** D2：滚动值回调路径（int 版内部 super 传播前的重算，覆盖滚动面板改动）。 */
    @Inject(method = "updateGeneratedRotation(I)V", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/kinetics/base/GeneratingKineticBlockEntity;updateGeneratedRotation()V"))
    public void dimblend$recomputeBeforePanelPropagate(int newSpeed, CallbackInfo ci) {
        dimblend$recompute();
    }

    /**
     * initialize() 的传播点（世界重载路径）——与 tick 同型，保证存档加载后
     * 首次传播即按信号映射。
     */
    @Inject(method = "initialize()V", at = @At(value = "INVOKE", target = "Lcom/mrh0/createaddition/blocks/electric_motor/ElectricMotorBlockEntity;updateGeneratedRotation()V"))
    public void dimblend$recomputeOnInitialize(CallbackInfo ci) {
        dimblend$recompute();
    }

    /**
     * D2 重算（幂等，多调用点安全）：以面板权威设定值为源、红石信号映射、
     * 无信号清零。每次调用点传播前执行一次，结果不叠加。
     */
    @Unique
    private void dimblend$recompute() {
        ElectricMotorBlockEntity self = (ElectricMotorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return; // 客户端转速来自网络同步，仅服务端重算
        }
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return;
        }
        if (dimblend$overstressLatched) {
            // D7 锁存期：一切外部更改直接丢弃，只恢复冻结值（含面板/CC/信号路径，
            // 各传播点注入均经此统一旁路）
            this.motorSpeed = dimblend$latchedSpeed;
            return;
        }
        int signal = dimblend$analogSignal(self);
        if (signal <= 0) {
            this.motorSpeed = 0.0F;
            return;
        }
        float panel = this.generatedSpeed.getValue();
        if (Math.abs(panel) <= dimblend$MIN_RPM) {
            // 面板 ≤4：按面板（含 0 与负小值）
            this.motorSpeed = panel;
            return;
        }
        // D2：sign×(4 + (signal-1)×(|panel|-4)/14)，一次到位、封顶|panel|
        float sign = Math.signum(panel);
        float mapped = dimblend$MIN_RPM * sign
                + (signal - 1) * (Math.abs(panel) - dimblend$MIN_RPM) / (float) (dimblend$RATED_SIGNAL - 1);
        this.motorSpeed = sign * Math.min(Math.abs(mapped), Math.abs(panel));
    }

    /**
     * D3（v1.2 改回）：面板 &lt;4rpm 按 4 计——getEnergyConsumptionRate 入参钳定：
     * 运行中（≠0）且 |rpm|&lt;4 → 4·sign；无信号 0 转保持 0 耗。
     * 下面的 Math.max Redirect（bypass 原 8 下限）保留：4 的线性项仍低于原下限。
     * Config 读取沿用既有判例（本 handler 与 Redirect 同风险面，复核在案）。
     */
    @ModifyVariable(method = "getEnergyConsumptionRate(F)I", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private static float dimblend$floorConsumptionAtFour(float rpm) {
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return rpm;
        }
        if (rpm != 0.0F && Math.abs(rpm) < dimblend$MIN_RPM) {
            return Math.signum(rpm) * dimblend$MIN_RPM;
        }
        return rpm;
    }

    /**
     * D3：去掉 MINIMUM_CONSUMPTION 下限——耗电变为纯线性 FE_RPM/256×|rpm|，
     * 4~7rpm 不再按 8 计（马达"待机基础损耗"语义由规格拍板移除）。
     * 字节码核实：getEnergyConsumptionRate 内的下限钳定是
     * {@code getstatic CommonConfig.ELECTRIC_MOTOR_MINIMUM_CONSUMPTION} +
     * {@code invokestatic Math.max(DD)D}——Redirect 该 max 调用返回第一参
     * （线性项本身）。Config 关闭时返回原值（second arg=MIN 读数，行为不变）。
     */
    @Redirect(
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Math;max(DD)D",
                    remap = false),
            method = "getEnergyConsumptionRate(F)I")
    private static double dimblend$noMinimumConsumption(double linearTerm, double minimumFloor) {
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return Math.max(linearTerm, minimumFloor);
        }
        return linearTerm;
    }

    /**
     * D7 锁存期耗电×2：tick 内唯一的 {@code getEnergyConsumptionRate(F)I} 调用点
     * （CCA 主体 {@code int con = ...(motorSpeed)}，启动判据复用该局部变量、无第二调用点）。
     * handler 为实例方法，直接读本实例锁存标志——多马达互不干扰。
     * 护目镜显示侧（addToGoggleTooltip）不在本 selector 内，由
     * {@code ElectricMotorGoggleMixin} 经锁存访问接口另行翻倍，
     * 与 D6“显示跟实扣” invariant 对齐。
     */
    @ModifyExpressionValue(
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mrh0/createaddition/blocks/electric_motor/ElectricMotorBlockEntity;getEnergyConsumptionRate(F)I",
                    remap = false),
            method = "tick()V")
    private int dimblend$doubleConsumptionWhenLatched(int rate) {
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return rate;
        }
        return dimblend$overstressLatched ? rate * 2 : rate;
    }

    /**
     * D1 反转（半边一）：tick 内第一处 booleanValue 解包（offset 122，启动判据
     * stored > rate*2 && !POWERED）→ 替换为 {@code !hasNeighborSignal}——信号在
     * 场才满足原"无 POWERED"分支。handler 独立重查信号（与 hasNeighborSignal
     * 同源，忽略原读数）。Config 关闭透传原版。
     */
    @ModifyExpressionValue(
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Boolean;booleanValue()Z",
                    ordinal = 0,
                    remap = false),
            method = "tick()V")
    public boolean dimblend$activateOnSignal(boolean originalPowered) {
        ElectricMotorBlockEntity self = (ElectricMotorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return originalPowered;
        }
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return originalPowered;
        }
        // 反转：原逻辑 !POWERED 才可启动 → 新逻辑 "信号在场"（POWERED 的反义在
        // 原判据中的语义位置替换为 signal>0）
        return !dimblend$signalPresent(self);
    }

    /**
     * D1 反转（半边二）：tick 内第二处 POWERED 读取 = active 维持判据。
     * D7 锁存期旁路：返回 false（=原“无 POWERED”分支满足）——信号撤除也不停转，
     * 直到过载恢复或 FE 耗尽重置。启动半边不旁路：锁存只在 active 下进入，
     * 启动判据执行时锁存恒 false。
     */
    @ModifyExpressionValue(
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/Boolean;booleanValue()Z",
                    ordinal = 1,
                    remap = false),
            method = "tick()V")
    public boolean dimblend$stayActiveOnSignal(boolean originalPowered) {
        ElectricMotorBlockEntity self = (ElectricMotorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return originalPowered;
        }
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return originalPowered;
        }
        if (dimblend$overstressLatched) {
            return false;
        }
        return !dimblend$signalPresent(self);
    }

    /**
     * D4 原版音效静音替代：行为开关开启时 CCA 自带运转声整体不播（返回 false），
     * 由 client 侧 {@link ElectricMotorSoundClientMixin} 的三态自定义音效
     * （startup/loop/stopping，|面板|&gt;64rpm 门控）取代；开关关闭透传原版。
     * <b>tickAudio 是 client-only 方法</b>（Create KineticBlockEntity.tick
     * 客户端分支 executeOnClientOnly 实证）——无 ServerLevel 守卫；
     * SERVER config 客户端经 ConfigSync 登录同步已加载（.tooltip 路径已核证）。
     */
    @ModifyExpressionValue(
            at = @At(
                    value = "FIELD",
                    target = "Lcom/mrh0/createaddition/blocks/electric_motor/ElectricMotorBlockEntity;active:Z",
                    remap = false),
            method = "tickAudio()V")
    public boolean dimblend$muteOriginalAudio(boolean originalActive) {
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return originalActive;
        }
        return false;
    }

    /**
     * D1/D2 信号强度：POWERED 同源判据（hasNeighborSignal 全 6 面，与原版
     * ElectricMotorBlock.neighborChanged 一致）+ 具体强度读数（除输出轴外各侧
     * 传入信号取 max）。输出轴面拉杆与原版判定一致地计入 hasNeighborSignal，
     * 但不计入强度读数——映射用。
     */
    @Unique
    private static boolean dimblend$signalPresent(ElectricMotorBlockEntity motor) {
        var level = motor.getLevel();
        return level.hasNeighborSignal(motor.getBlockPos());
    }

    @Unique
    private static int dimblend$analogSignal(ElectricMotorBlockEntity motor) {
        var level = motor.getLevel();
        var pos = motor.getBlockPos();
        var facing = motor.getBlockState().getValue(
                com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlock.FACING);
        int best = 0;
        for (Direction dir : Direction.values()) {
            if (dir == facing) {
                continue; // 输出轴一侧不算输入
            }
            best = Math.max(best, level.getSignal(pos.relative(dir), dir));
        }
        return best;
    }
}
