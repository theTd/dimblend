package dimblend.experience.compat.create;

/**
 * 「下界太热」附加规则的判定口径（纯函数，零 Minecraft/NeoForge 依赖，可单元测试）。
 *
 * <p>规则（用户 2026-10-31 裁定）：<b>分液池位于下界群系</b>时，其覆盖半径
 * （{@code itemDrainCoverageRadius}，默认 3 = 7x7 同层，覆盖口径与 N1 保湿 /
 * N2 催熟完全一致）内，池子把周围「烤」到不能种、不能长：</p>
 *
 * <ol>
 * <li><b>耕地不干</b>：由 N1 保湿票据保证（票据与维度无关，原版
 * {@code FarmBlock#isNearWater} 认票据），本类不重复实现。</li>
 * <li><b>作物不长</b>（{@link #suppressesGrowth}）：自然 randomTick 生长与池子
 * 自身骨粉催熟（{@link #growthBoostAllowed}）都停。</li>
 * <li><b>种不下去</b>（{@link #suppressesPlanting}）：右键播种/种下界疣被取消并提示。</li>
 * <li><b>管道灌不进</b>（{@link #pipeIntakeAllowed}）：维持原版 {@code forbidInsertion}
 * 与 UP 面无接口；<b>但桶右键倒水照旧可用</b>——桶走的是
 * {@code ItemDrainBlock#useItemOn} 自己开的注水窗口，与管道那条全 tick 门控无关
 * （证据见 {@link ItemDrainPipeRefill} 的说明）。</li>
 * </ol>
 *
 * <p>覆盖判定锚在<b>分液池</b>上（与 N6 蒸发同口径）：群系取池子所在位置的世界坐标，
 * 距离取池子与目标格的整数格差。层高取分液池自身 y 与其上一层 y+1——植物落在池子
 * 所坐的那层之上，与 N2 的 y/y+1 扫描一致。</p>
 */
public final class ItemDrainNetherHeat {

    /**
     * 目标格是否落在分液池的覆盖内：同层或上一层（y 或 y+1），且水平整数格差
     * ≤ {@code radius}（闭区间，与 {@link IrrigationMath#coverageCells} 同口径）。
     */
    public static boolean covers(int drainX, int drainY, int drainZ, int radius,
            int x, int y, int z) {
        if (y != drainY && y != drainY + 1) {
            return false;
        }
        return Math.abs(x - drainX) <= radius && Math.abs(z - drainZ) <= radius;
    }

    /** ② 封禁自然生长：须同时满足下界群系、在覆盖内、属于作物族（作物/茎/下界疣）。 */
    public static boolean suppressesGrowth(boolean nether, boolean inCoverage, boolean cropFamily) {
        return nether && inCoverage && cropFamily;
    }

    /** ③ 封禁种植：须同时满足下界群系、在覆盖内、目标是可承载植物的方块。 */
    public static boolean suppressesPlanting(boolean nether, boolean inCoverage, boolean plantableOnTarget) {
        return nether && inCoverage && plantableOnTarget;
    }

    /** ④ 管道注水是否放开：开关开<b>且</b>不在下界（下界一律维持原版禁止）。 */
    public static boolean pipeIntakeAllowed(boolean switchOn, boolean nether) {
        return switchOn && !nether;
    }

    /** ② 的 N2 侧：下界内池子不再骨粉催熟（也不再抽水）。 */
    public static boolean growthBoostAllowed(boolean nether) {
        return !nether;
    }

    private ItemDrainNetherHeat() {
    }
}