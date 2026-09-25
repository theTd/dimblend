package dimblend.experience.mixin.compat.cca;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mrh0.createaddition.blocks.electric_motor.ElectricMotorBlockEntity;
import dimblend.experience.Config;
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
 * {@code 信号强度<=0}——信号在场才满足原"无 POWERED"分支。Config 关闭透传原版。</li>
 * <li>D2 映射：全部传播点前 recompute——panel 取 {@code generatedSpeed.getValue()}
 * （面板权威设定值，CC setRPM 亦先 setValue 落到面板，全路径一致）；
 * signal 1~15 → sign×(4 + (signal-1)×(|panel|-4)/14)，封顶 |panel|，无 cancel
 * 无递归；|panel|≤4 直通面板；signal≤0 → motorSpeed=0。</li>
 * <li>D3 纯线性能耗：getEnergyConsumptionRate 内 Math.max(DD) 的 MINIMUM_
 * CONSUMPTION 分支重定向为返回线性项（"低于 8 按 8 计"消除），受 Config 门控。</li>
 * <li>D4 原版音效静音替代（素材已到位，2026-09-19 接入）：行为开关开启时
 * tickAudio 的 active 读取压为 false——CCA 自带运转声整体不播，由 client 侧
 * {@code ElectricMotorSoundClientMixin} 三态自定义音效（startup/loop/stopping，
 * |面板|&gt;64rpm 门控）取代；开关关闭透传原版（经 CASoundScapes 每 tick
 * 重新贡献，恢复即自然恢复，无内部状态机残留——复核字节码核实）。</li>
 * </ul>
 */
@Mixin(ElectricMotorBlockEntity.class)
public abstract class ElectricMotorMixin {

    @Unique
    private static final int dimblend$MIN_RPM = 4;
    @Unique
    private static final int dimblend$RATED_SIGNAL = 15;

    @Shadow
    protected float motorSpeed;

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
     */
    @Unique
    private static final float dimblend$PROPAGATE_EPSILON = 1.0E-3F;

    @Inject(method = "tick()V", at = @At("HEAD"))
    public void dimblend$followSignalLive(CallbackInfo ci) {
        ElectricMotorBlockEntity self = (ElectricMotorBlockEntity) (Object) this;
        if (!(self.getLevel() instanceof ServerLevel)) {
            return;
        }
        if (!Config.ELECTRIC_MOTOR_BEHAVIOR.get()) {
            return;
        }
        float before = this.motorSpeed;
        dimblend$recompute();
        if (Math.abs(this.motorSpeed - before) > dimblend$PROPAGATE_EPSILON) {
            self.updateGeneratedRotation();
        }
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