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
 */
public final class ChassisInteraction {
    public enum Action {
        /** 不拦截，继续走原版/Create 的右键逻辑。 */
        PASS,
        /** 拦截并打开底盘选择页。 */
        OPEN
    }

    /**
     * @param mainHand    事件是否来自主手；副手永远返回 PASS
     * @param sneaking    玩家是否潜行；潜行永远返回 PASS
     * @param menuTrigger 主手是否为空或 Create 扳手
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
