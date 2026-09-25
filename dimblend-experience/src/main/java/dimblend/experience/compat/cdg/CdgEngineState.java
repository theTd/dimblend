package dimblend.experience.compat.cdg;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * CDG 柴油发动机运行态（B 板块）：
 * <ul>
 * <li>rampTicks 点火爬梯计时——不持久化亦可（重载重爬），但随整态序列化无代价</li>
 * <li>overloadLatched 过载闩锁——**必须持久化**：仅为爆机失败回退路径
 * （破坏失败的极端情况），闩锁期间维持"重新加油才重启"语义</li>
 * <li>lastFuelAmount 重新加油边沿检测的参照值——持久化保证跨区块闩锁解除
 * 逻辑连续</li>
 * <li>fluctFactor/fluctTicksLeft 波动状态——运行时噪声，重置无碍</li>
 * </ul>
 */
public class CdgEngineState {

    /** 自点火起累计的运转 tick（发动机未运转时复位为 0）。 */
    public int rampTicks;
    /** B4 过载闩锁（爆机失败回退路径）：置位后停机，重新加油才解除。 */
    public boolean overloadLatched;
    /** 上一 tick 油罐是否有有效燃油（用于识别"重新加油"边沿）。 */
    public boolean fuelPresent;
    /** 上一 tick 油罐燃料量（mB）——闩锁期间油量上升即视为重新加油。 */
    public int lastFuelAmount;
    /** B3 当前波动系数（0.8~1.0）。 */
    public float fluctFactor = 1.0F;
    /** 距下次波动取值的剩余 tick（1~3 秒）。 */
    public int fluctTicksLeft;

    public static final Codec<CdgEngineState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("rampTicks").forGetter(s -> s.rampTicks),
            Codec.BOOL.fieldOf("overloadLatched").forGetter(s -> s.overloadLatched),
            Codec.BOOL.fieldOf("fuelPresent").forGetter(s -> s.fuelPresent),
            Codec.INT.fieldOf("lastFuelAmount").forGetter(s -> s.lastFuelAmount),
            Codec.FLOAT.fieldOf("fluctFactor").forGetter(s -> s.fluctFactor),
            Codec.INT.fieldOf("fluctTicksLeft").forGetter(s -> s.fluctTicksLeft))
            .apply(instance, CdgEngineState::new));

    public CdgEngineState(int rampTicks, boolean overloadLatched, boolean fuelPresent, int lastFuelAmount,
            float fluctFactor, int fluctTicksLeft) {
        this.rampTicks = rampTicks;
        this.overloadLatched = overloadLatched;
        this.fuelPresent = fuelPresent;
        this.lastFuelAmount = lastFuelAmount;
        this.fluctFactor = fluctFactor;
        this.fluctTicksLeft = fluctTicksLeft;
    }

    public CdgEngineState() {
    }
}