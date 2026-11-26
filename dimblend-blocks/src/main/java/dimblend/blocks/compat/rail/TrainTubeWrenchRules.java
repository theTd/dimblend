package dimblend.blocks.compat.rail;

/**
 * 扳手右键加压管道该走哪条分支的纯状态机（{@link TrainTubeLinkInteraction} 只负责落地副作用）。
 *
 * <p>规则（对应用户这一轮的三条要求）：</p>
 * <ol>
 *   <li><b>已连接的端点，不管在不在连接模式里，右键就是断开它</b>——不再走"我没选中啊"的分支；</li>
 *   <li>只有单端管道（本地连接数恰好 1）才能当跨车厢锚点；</li>
 *   <li>再点一次同一个端点 / 潜行右键 = 取消；有挂起选择时点别的端点 = 尝试建链。</li>
 * </ol>
 *
 * <p>潜行右键本身由 Create 的 {@code WrenchItem#useOn} 派发到 {@code onSneakWrenched}（掉落 + 破坏），
 * 到不了这里；但别的扳手类物品或脚本可能直接调 {@code onWrenched}，所以这里仍然把潜行映射成"取消挂起选择"。</p>
 */
public final class TrainTubeWrenchRules {

    /** 一次扳手右键的判定结果。 */
    public enum Action {
        /** 什么都不做（Create 那边已经处理了）。 */
        IGNORE,
        /** 清掉挂起的端点选择。 */
        CANCEL,
        /** 断开锚在该端点上的连接。 */
        DISCONNECT,
        /** 不是单端管道，不能当锚点。 */
        NOT_ENDPOINT,
        /** 方块不在 Sable 子层级（列车结构）上。 */
        NOT_ON_TRAIN,
        /** 建链（调用方再做方位/挂接/距离/占用校验）。 */
        LINK,
        /** 把这一端记为挂起选择。 */
        SELECT
    }

    private TrainTubeWrenchRules() {
    }

    /**
     * @param shiftDown       玩家是否潜行
     * @param linked          该端点当前是否已经有一条跨车厢连接
     * @param endpoint        该方块的本地连接数是否恰好 1
     * @param onSubLevel      方块是否落在 Sable 子层级上
     * @param hasSelection    该玩家是否已有挂起选择
     * @param clickedSelection 点的是不是挂起选择那一个端点
     */
    public static Action decide(boolean shiftDown, boolean linked, boolean endpoint, boolean onSubLevel,
            boolean hasSelection, boolean clickedSelection) {
        if (shiftDown) {
            return hasSelection ? Action.CANCEL : Action.IGNORE;
        }
        if (linked) {
            return Action.DISCONNECT;
        }
        if (!endpoint) {
            return Action.NOT_ENDPOINT;
        }
        if (!onSubLevel) {
            return Action.NOT_ON_TRAIN;
        }
        if (!hasSelection) {
            return Action.SELECT;
        }
        if (clickedSelection) {
            return Action.CANCEL;
        }
        return Action.LINK;
    }

    /**
     * 每 tick 复核挂起选择：切走扳手或超时都退出连接态。
     *
     * @param holdingWrench 玩家主手/副手是否还有扳手
     * @param ageTicks      选择已存在的 tick 数
     * @param timeoutTicks  超时阈值
     */
    public static boolean shouldAbandon(boolean holdingWrench, long ageTicks, long timeoutTicks) {
        return !holdingWrench || ageTicks > timeoutTicks;
    }
}
