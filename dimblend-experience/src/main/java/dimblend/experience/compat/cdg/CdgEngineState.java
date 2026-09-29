package dimblend.experience.compat.cdg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * CDG 柴油发动机运行态（B 板块）：
 * <ul>
 * <li>rampTicks 点火爬梯计时——不持久化亦可（重载重爬），但随整态序列化无代价</li>
 * <li>overloadLatched 过载闩锁——引信进行中隐含置位（出力归零+燃油门控）；
 * 自毁破坏失败的极端情况作回退：维持"重新加油才重启"语义</li>
 * <li>lastFuelAmount 重新加油边沿检测的参照值——持久化保证跨区块闩锁解除
 * 逻辑连续</li>
 * <li>fluctFactor/fluctTicksLeft 波动状态——运行时噪声，重置无碍</li>
 * <li>fuseActive/fuseTicksLeft B6 过载引信（不可中断）：置位后每服务端 tick 递减，
 * 到 0 自毁；红石关停/燃尽/负载恢复都不取消；持久化保证跨区块卸载/存档重启不中断</li>
 * <li>overloadTicks B6 持续过载确认计数（连续"运转中过载" tick 数，满
 * {@link CdgOverloadMath#OVERLOAD_CONFIRM_TICKS} 才点引信；任一正常 tick 清零）。
 * 不序列化：确认进度不跨存档/区块重载携带，存档前半程确认与读档后网络重建
 * 误报相加点引信的路径由此切断（2026-09-28 口径）</li>
 * <li>settleTicks/lastNetworkStress/lastNetworkSize 网络视图稳定探测——重建完成
 * 判据（{@link CdgOverloadMath#NETWORK_SETTLE_TICKS}，2026-09-29 起替代固定
 * 5 秒加载宽限期），不在 codec 内，读档归零 = 未武装</li>
 * </ul>
 */
public class CdgEngineState {

    /** 自点火起累计的运转 tick（发动机未运转时复位为 0）。 */
    public int rampTicks;
    /** B4 过载闩锁（引信进行中隐含置位；自毁失败回退路径：重新加油才解除）。 */
    public boolean overloadLatched;
    /** 上一 tick 油罐是否有有效燃油（用于识别"重新加油"边沿）。 */
    public boolean fuelPresent;
    /** 上一 tick 油罐燃料量（mB）——闩锁期间油量上升即视为重新加油。 */
    public int lastFuelAmount;
    /** B3 当前波动系数（0.8~1.0）。 */
    public float fluctFactor = 1.0F;
    /** 距下次波动取值的剩余 tick（1~3 秒）。 */
    public int fluctTicksLeft;
    /** B6 过载引信进行中（置位即隐含 overloadLatched：出力与燃油门控走闩锁口径）。 */
    public boolean fuseActive;
    /** B6 引信剩余 tick（120 起，到 0 自毁）。 */
    public int fuseTicksLeft;
    /**
     * B6 疑似过载连续计数（运转中过载每 tick +1，任一正常 tick 清零）。
     * 不序列化：确认进度不跨存档/区块重载携带。
     */
    public int overloadTicks;
    /** 网络视图稳定计数：视图连续不变的 tick 数（达 NETWORK_SETTLE_TICKS 判重建完成）。 */
    public int settleTicks;
    /** 上次采样的网络应力视图（重建 churn 探测用）。 */
    public float lastNetworkStress;
    /** 上次采样的网络规模视图（成员并入/合并/拆分探测用）。 */
    public int lastNetworkSize;

    public static final Codec<CdgEngineState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("rampTicks").forGetter(s -> s.rampTicks),
            Codec.BOOL.fieldOf("overloadLatched").forGetter(s -> s.overloadLatched),
            Codec.BOOL.fieldOf("fuelPresent").forGetter(s -> s.fuelPresent),
            Codec.INT.fieldOf("lastFuelAmount").forGetter(s -> s.lastFuelAmount),
            Codec.FLOAT.fieldOf("fluctFactor").forGetter(s -> s.fluctFactor),
            Codec.INT.fieldOf("fluctTicksLeft").forGetter(s -> s.fluctTicksLeft),
            Codec.BOOL.optionalFieldOf("fuseActive", false).forGetter(s -> s.fuseActive),
            Codec.INT.optionalFieldOf("fuseTicksLeft", 0).forGetter(s -> s.fuseTicksLeft))
            .apply(instance, CdgEngineState::new));

    public CdgEngineState(int rampTicks, boolean overloadLatched, boolean fuelPresent, int lastFuelAmount,
            float fluctFactor, int fluctTicksLeft, boolean fuseActive, int fuseTicksLeft) {
        this.rampTicks = rampTicks;
        this.overloadLatched = overloadLatched;
        this.fuelPresent = fuelPresent;
        this.lastFuelAmount = lastFuelAmount;
        this.fluctFactor = fluctFactor;
        this.fluctTicksLeft = fluctTicksLeft;
        this.fuseActive = fuseActive;
        this.fuseTicksLeft = fuseTicksLeft;
    }

    public CdgEngineState() {
    }
}
