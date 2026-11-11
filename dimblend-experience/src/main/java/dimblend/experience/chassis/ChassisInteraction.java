package dimblend.experience.chassis;

/**
 * 底盘驾驶台右键的纯规则。
 *
 * <p>2026-11-10 的用户口径：主手空手或 Create 扳手、普通右键、且驾驶台当前确实可以打开选择页时，
 * 才拦下这次点击并开菜单；潜行一律放行（扳手拆卸仍归 Create），副手一律不触发，其他物品一律放行。
 * 潜行/物品判据必须两端一致，因此这里的输入只有布尔值，客户端与服务端各自用同一份规则求值。</p>
 *
 * <p>适用条件不满足时返回 {@link Action#PASS}，调用方不得取消事件——否则会出现“点了没反应、
 * 也没有菜单”的吞点击。服务端只有在 {@link ChassisSelection#open} 真正建立会话后才取消事件。</p>
 *
 * <p><b>为什么“其他物品放行”要单独成一个具名判定。</b>{@link #decide} 只吃一个已经算好的
 * {@code menuTrigger}，物品到触发器的映射原本写在事件方法里，单测碰不到；一旦有人把映射写成
 * “非空即触发”或“只看副手”，单测照样全绿，而普通方块/食物右键会被吞掉，客户端预测放置
 * 与服务端取消之间就会出现一帧级分叉。把映射抽成 {@link #menuTrigger(boolean, boolean)}
 * 之后，这两条边界都能被纯布尔单测钉死。</p>
 */
public final class ChassisInteraction {
    public enum Action {
        /** 不拦截，继续走原版/Create 的右键逻辑。 */
        PASS,
        /** 拦截并打开底盘选择页。 */
        OPEN
    }

    /**
     * 主手物品是否算“开菜单的触发器”：只有空手与 Create 扳手两种。
     *
     * <p>这里刻意收窄成 Create 扳手本体，不按扳手物品标签放行：其他 mod 的扳手类物品在驾驶台上
     * 没有定义行为，让它们也开菜单会把“扳手永远先作用于方块自身”的直觉改掉。{@code false} 的
     * 情况必须原样放行，交由原版/Create 走放置或使用。</p>
     *
     * @param emptyMainHand       主手物品栈为空
     * @param holdingCreateWrench 主手物品是 {@code create:wrench}
     */
    public static boolean menuTrigger(boolean emptyMainHand, boolean holdingCreateWrench) {
        return emptyMainHand || holdingCreateWrench;
    }

    /**
     * @param mainHand    事件是否来自主手；副手永远返回 PASS
     * @param sneaking    玩家是否潜行；潜行永远返回 PASS
     * @param menuTrigger 主手是否为空或 Create 扳手，见 {@link #menuTrigger(boolean, boolean)}
     * @param applicable  驾驶台、维度、轨道与距离是否都满足
     */
    public static Action decide(boolean mainHand, boolean sneaking, boolean menuTrigger, boolean applicable) {
        if (!mainHand || sneaking || !menuTrigger || !applicable) {
            return Action.PASS;
        }
        return Action.OPEN;
    }

    private ChassisInteraction() {
    }
}
