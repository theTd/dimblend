package dimblend.experience.compat.sophisticated;

/**
 * 升级容器在 wrapper 实例被替换后的刷新回调。
 *
 * <p>{@code UpgradeContainerBase#setUpgradeWrapper} 是客户端模块槽同步的唯一替换入口
 * （{@code updateWrappersAndCheckForReloadNeeded} 对"同物品"走这条路，不重建容器）。
 * 上游实现只换字段，不通知已捕获的槽位/矩阵/供应商。注入点在
 * {@code UpgradeContainerBaseMixin} 的 {@code setUpgradeWrapper} TAIL，
 * 由实现本接口的容器 mixin 自行刷新。</p>
 */
public interface WrapperRefreshHook {

    /** 在 {@code UpgradeContainerBase#setUpgradeWrapper} 返回后调用；此时字段已是新 wrapper。 */
    void dimblend$afterWrapperReplaced();
}
