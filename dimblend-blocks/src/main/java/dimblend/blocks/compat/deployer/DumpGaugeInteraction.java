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
 *       value-settings 拖拽：短按松开时发 {@code ValueSettingsPacket}（{@code interactHand} 非空），
 *       服务端在 {@code ValueSettingsPacket#applySettings} 里看到非空 {@code interactHand} 就转调
 *       {@code onShortInteract}，客户端也在本地补调一次——所以<b>两侧都会到</b>这里；
 *       真拖动时才走 {@code interactHand == null} 的 {@code setValueSettings} 路径。</li>
 * </ol>
 *
 * <p>本类的全部判据都是布尔量，故意不碰 {@code Item}/{@code ItemStack}：
 * 一是单测不需要游戏引导，二是把「谁是过滤候选」这类语义收敛在一处。</p>
 */
public final class DumpGaugeInteraction {

    /** 处理动作：谁来干活、谁只做空转。 */
    public enum Action {
        /** 服务端：把手里物品写进过滤槽。 */
        APPLY_FILTER,
        /** 客户端：打开「卸料设置」页（只可能在过滤槽<b>已</b>设物品时）。 */
        OPEN_SCREEN,
        /** 服务端：打开 Create 的 {@code FactoryPanelSetItemMenu}（换过滤物品）。 */
        OPEN_SET_ITEM_MENU,
        /** 服务端：把手里那张物流网络物品的频率写到面板（{@code LogisticallyLinkedBlockItem#assignFrequency}）。 */
        ASSIGN_FREQUENCY,
        /** 客户端：同一次交互里什么都不做（真正的动作在服务端，或交给网络包开后一个界面）。 */
        CONSUME_ONLY,
        /** 两侧都不产生效果（扳手、机械臂等）。 */
        IGNORE
    }

    /**
     * 一次短按右键的全部输入。
     *
     * @param clientSide      当前这一侧
     * @param filterEmpty     过滤槽是否为空（= {@code isCountVisible()} 的反面）
     * @param heldEmpty       手里是不是空的
     * @param frequencyItem   手里是不是物流网络物品（{@code LogisticallyLinkedBlockItem} / {@code LogisticallyLinked}）
     * @param filterCandidate 手里那件物品能否当过滤物品（非空、非扳手、非机械臂、非网络物品）
     */
    public record Click(boolean clientSide, boolean filterEmpty, boolean heldEmpty, boolean frequencyItem,
            boolean filterCandidate) {
    }

    private DumpGaugeInteraction() {
    }

    public static Action route(Click click) {
        if (click.frequencyItem()) {
            // 网络物品是「绑频率」用的：Create 自己也在 filter 非空时优先处理它，
            // 这里连 filter 为空的情况也优先绑频率——否则玩家会误把物流网络物品设成过滤物品。
            return click.clientSide() ? Action.CONSUME_ONLY : Action.ASSIGN_FREQUENCY;
        }
        if (click.heldEmpty()) {
            if (click.filterEmpty()) {
                // Create 口径：过滤槽为空时开「选择物品」界面，而不是配置页
                return click.clientSide() ? Action.CONSUME_ONLY : Action.OPEN_SET_ITEM_MENU;
            }
            return click.clientSide() ? Action.OPEN_SCREEN : Action.IGNORE;
        }
        if (click.filterCandidate()) {
            return click.clientSide() ? Action.CONSUME_ONLY : Action.APPLY_FILTER;
        }
        // 扳手要留给「循环连线走向」、机械臂是要放下的方块：两侧都不该弹设置页
        return Action.IGNORE;
    }

    /**
     * 与 Create {@code FilteringBehaviour#canShortInteract} 同义：
     * 扳手要留给「循环连线走向」，机械臂是要放下的方块，两者都不当过滤物品。
     */
    public static boolean isFilterCandidate(boolean empty, boolean wrench, boolean mechanicalArm) {
        return !empty && !wrench && !mechanicalArm;
    }
}
