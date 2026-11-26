package dimblend.blocks.compat.deployer;

/**
 * 卸料仪表「短按右键」的分支判定（纯逻辑，便于单测）。
 *
 * <p>Create 6.0.10 的实际调用链（{@code ValueSettingsInputHandler#onBlockActivated} +
 * {@code ValueSettingsClient#tick} + {@code ValueSettingsPacket#applySettings}）：</p>
 * <ol>
 *   <li>{@code FactoryPanelBehaviour#isCountVisible()} 就是 {@code !getFilter().isEmpty()}，
 *       所以过滤槽为空时 {@code acceptsValueSettings()} 为 {@code false}，
 *       右键当刻直接在客户端与服务端都调 {@code onShortInteract}；</li>
 *   <li>过滤槽已设物品后 {@code acceptsValueSettings()} 为 {@code true}，客户端先进入
 *       value-settings 拖拽，短按松开时发 {@code ValueSettingsPacket} 并在本地补调一次
 *       {@code onShortInteract}。</li>
 * </ol>
 *
 * <p>因此「手持物品右键设过滤」必须同时满足：服务端真的写入过滤槽，客户端<b>不</b>趁机把设置页弹出来。
 * 原来 {@code AbstractPanelBehaviour} 的 5 参 {@code onShortInteract} 默认实现是
 * 「客户端就开页面」，手持物品时也照开，于是过滤永远设不上、页面却弹出来——这就是本次修的两个现象。</p>
 */
public final class DumpGaugeInteraction {

    /** 处理动作：谁来干活、谁只做空转。 */
    public enum Action {
        /** 服务端：把手里物品写进过滤槽。 */
        APPLY_FILTER,
        /** 客户端：同一次交互里什么都不做（开页面会抢掉设过滤的操作）。 */
        CONSUME_ONLY,
        /** 客户端：打开「卸料设置」页。 */
        OPEN_SCREEN,
        /** 服务端：不产生任何效果（空手短按、或手里是扳手/机械臂）。 */
        IGNORE
    }

    private DumpGaugeInteraction() {
    }

    /**
     * @param clientSide          当前这一侧
     * @param emptyHand           手里是不是空的
     * @param filterCandidate     手里那件物品能否当过滤物品（非空、非扳手、非机械臂）
     */
    public static Action route(boolean clientSide, boolean emptyHand, boolean filterCandidate) {
        if (emptyHand) {
            // 空手：客户端开设置页，服务端不需要为「只是打开界面」写任何状态
            return clientSide ? Action.OPEN_SCREEN : Action.IGNORE;
        }
        if (!filterCandidate) {
            // 扳手要留给「循环连线走向」、机械臂是要放下的方块：两侧都不该弹设置页
            return Action.IGNORE;
        }
        return clientSide ? Action.CONSUME_ONLY : Action.APPLY_FILTER;
    }

    /**
     * 与 Create {@code FilteringBehaviour#canShortInteract} 同义：
     * 扳手要留给「循环连线走向」，机械臂是要放下的方块，两者都不当过滤物品。
     */
    public static boolean isFilterCandidate(boolean empty, boolean wrench, boolean mechanicalArm) {
        return !empty && !wrench && !mechanicalArm;
    }
}
