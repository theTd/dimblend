package dimblend.experience.compat.simurail;

/**
 * I20 刹车车厢的红石刹车力度例外（纯函数）。
 *
 * <p>{@code PhysicsBogeyBrakeSignalCapMixin} 把普通转向架的
 * {@code getControlStrength()} 压到 {@code trainBrakeSignalCap}/15（实例值 1，即 1..15 级
 * 一律变成 1 级）。追逐的刹车车厢（{@code CarPlans} 在车架正上方放红石块、再上方红石灯，
 * 控制模式 {@code BRAKING}、应力 0）要的是「红石块 = 满刹」：只要收到任意正信号就按 1.0
 * 施闸，不跟随玩家调的上限。0 信号仍保持 0——没通电不能变成满刹。</p>
 *
 * <p>识别分两级：{@link dimblend.api.ChaseBrakeBogeyMarker} 持久标记（新组装的车厢，
 * 玩家无法伪造），以及红石块 + 红石灯电路特征（旧存档里已生成的刹车车厢，方块同样由
 * {@code CarPlans} 放置）。标记读取是 {@code volatile} 布尔，物理线程安全；电路特征由
 * 调用方先过 {@code ChunkAvailability} 守卫再读方块，两者都不会同步加载区块。</p>
 */
public final class ChaseBrakeSignalRules {

    /** 刹车车厢满刹：1.0 = 红石 15 级 / 15。 */
    public static final double FULL_BRAKE = 1.0D;

    /**
     * 上限修正后的刹车控制强度。
     *
     * @param original          {@code getControlStrength()} 的原值，0..1
     * @param capSignal         配置的红石等级上限（1..15）
     * @param chaseBrakeBogey   本转向架是否属于追逐的刹车车厢
     */
    public static double applyCap(double original, int capSignal, boolean chaseBrakeBogey) {
        if (original <= 0.0D) {
            return original;
        }
        if (chaseBrakeBogey) {
            return FULL_BRAKE;
        }
        return Math.min(original, capSignal / 15.0D);
    }

    /**
     * 刹车车厢的电路特征：转向架正上方是红石块、其上方是红石灯。
     *
     * <p>单靠红石块不够（普通转向架手动通电也放红石块，仍应受上限约束）；两格同时在场才是
     * 建车流程的独有布置。</p>
     */
    public static boolean isChaseBrakeCircuit(boolean redstoneBlockAbove, boolean redstoneLampAboveThat) {
        return redstoneBlockAbove && redstoneLampAboveThat;
    }

    private ChaseBrakeSignalRules() {
    }
}
