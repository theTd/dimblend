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
 * <li>boostConsumed B9 boost 启动已消费标志（每次点火会话仅一次；rampTicks 复位=
 * 会话结束时清除）——持久化防读档中途重置多送一次</li>
 * <li>overloadTicks B6 持续过载确认计数（连续"运转中过载" tick 数，满
 * {@link CdgOverloadMath#OVERLOAD_CONFIRM_TICKS} 才点引信；任一正常 tick 清零）。
 * 不序列化：确认进度不跨存档/区块重载携带，存档前半程确认与读档后网络重建
 * 误报相加点引信的路径由此切断（2026-09-28 口径）</li>
 * <li>过载确认不看缓存 overStressed 是否稳定：加载期该位会粘住，
 * 实时复核见 {@link CdgKineticOverload}，本类不存探测态</li>
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
    /** B6 引信剩余 tick（{@link CdgOverloadFuse#FUSE_TICKS} 起，到 0 自毁）。 */
    public int fuseTicksLeft;
    /** B9 boost 启动已消费标志（每次点火会话仅一次；rampTicks 复位=会话结束时清除）。 */
    public boolean boostConsumed;
    /**
     * B6 疑似过载连续计数（运转中过载每 tick +1，任一正常 tick 清零）。
     * 不序列化：确认进度不跨存档/区块重载携带。
     */
    public int overloadTicks;
    /**
     * B6'（2026-11-25）活塞损坏：引信到 0 不再炸掉整机，而是把引擎打成"已损坏"——
     * 出力 0、燃油不再消耗、客户端声音停、冷热兼容不再当热源，直到玩家用
     * {@code createdieselgenerators:engine_piston} 右键更换活塞。
     *
     * <p><b>序列化</b>（跨区块卸载/存档重启保持）并且随 {@code write(clientPacket=true)}
     * 同步到客户端（护目镜红字要靠它）。只有"换活塞"能清掉它：重新加油、红石、燃尽都不行。</p>
     */
    public boolean pistonDamaged;
    /**
     * 损坏期间偶发灭火音的下次触发倒计时（tick）。
     * <b>不序列化</b>：纯运行时噪声，读档后从头随机无任何语义损失（见 {@link CdgDamageAudioMath}）。
     */
    public int extinguishCooldown;

    public boolean clearOverload() {
        boolean changed = overloadLatched || fuseActive || fuseTicksLeft != 0 || overloadTicks != 0;
        overloadLatched = false;
        fuseActive = false;
        fuseTicksLeft = 0;
        overloadTicks = 0;
        return changed;
    }

    /**
     * 标记"活塞损坏"：清掉引信与闩锁（损坏本身接管了"出力归零"的语义）。
     *
     * @return 是否发生了状态变化（false = 本来就是坏的）
     */
    public boolean markPistonDamaged() {
        boolean changed = !pistonDamaged;
        pistonDamaged = true;
        overloadLatched = false;
        fuseActive = false;
        fuseTicksLeft = 0;
        overloadTicks = 0;
        rampTicks = 0;
        extinguishCooldown = 0;
        return changed;
    }

    /**
     * 换活塞修复：损坏、闩锁、引信、爬梯、波动、boost 全部归零，等于一次全新点火。
     *
     * @return 是否发生了状态变化
     */
    public boolean repairPiston() {
        boolean changed = pistonDamaged || overloadLatched || fuseActive || fuseTicksLeft != 0
                || overloadTicks != 0 || rampTicks != 0 || boostConsumed;
        pistonDamaged = false;
        overloadLatched = false;
        fuseActive = false;
        fuseTicksLeft = 0;
        overloadTicks = 0;
        rampTicks = 0;
        fluctTicksLeft = 0;
        boostConsumed = false;
        extinguishCooldown = 0;
        return changed;
    }

    public static final Codec<CdgEngineState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("rampTicks").forGetter(s -> s.rampTicks),
            Codec.BOOL.fieldOf("overloadLatched").forGetter(s -> s.overloadLatched),
            Codec.BOOL.fieldOf("fuelPresent").forGetter(s -> s.fuelPresent),
            Codec.INT.fieldOf("lastFuelAmount").forGetter(s -> s.lastFuelAmount),
            Codec.FLOAT.fieldOf("fluctFactor").forGetter(s -> s.fluctFactor),
            Codec.INT.fieldOf("fluctTicksLeft").forGetter(s -> s.fluctTicksLeft),
            Codec.BOOL.fieldOf("fuseActive").forGetter(s -> s.fuseActive),
            Codec.INT.fieldOf("fuseTicksLeft").forGetter(s -> s.fuseTicksLeft),
            Codec.BOOL.fieldOf("boostConsumed").forGetter(s -> s.boostConsumed),
            Codec.BOOL.optionalFieldOf("pistonDamaged", false).forGetter(s -> s.pistonDamaged))
            .apply(instance, CdgEngineState::new));

    public CdgEngineState(int rampTicks, boolean overloadLatched, boolean fuelPresent, int lastFuelAmount,
            float fluctFactor, int fluctTicksLeft, boolean fuseActive, int fuseTicksLeft, boolean boostConsumed,
            boolean pistonDamaged) {
        this.rampTicks = rampTicks;
        this.overloadLatched = overloadLatched;
        this.fuelPresent = fuelPresent;
        this.lastFuelAmount = lastFuelAmount;
        this.fluctFactor = fluctFactor;
        this.fluctTicksLeft = fluctTicksLeft;
        this.fuseActive = fuseActive;
        this.fuseTicksLeft = fuseTicksLeft;
        this.boostConsumed = boostConsumed;
        this.pistonDamaged = pistonDamaged;
    }

    public CdgEngineState(int rampTicks, boolean overloadLatched, boolean fuelPresent, int lastFuelAmount,
            float fluctFactor, int fluctTicksLeft, boolean fuseActive, int fuseTicksLeft, boolean boostConsumed) {
        this(rampTicks, overloadLatched, fuelPresent, lastFuelAmount, fluctFactor, fluctTicksLeft,
                fuseActive, fuseTicksLeft, boostConsumed, false);
    }

    public CdgEngineState() {
    }
}
