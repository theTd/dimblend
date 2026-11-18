package dimblend.experience.compat.sophisticated;

/**
 * 由 {@code StorageContainerMenuSyncMixin} 合并进 {@code StorageContainerMenuBase} 的同步状态视图。
 *
 * <p>精妙背包打开后、客户端 {@code SyncContainerStacksPayload}（{@code initializeContents}）
 * 到达之前，槽位里的物品还是空的；此时渲染回退路径会把"记忆槽位/槽位过滤器"的物品画成
 * 半透明无数量图标，看起来像设置模式。客户端渲染 mixin 用本接口判断是否已过初始同步。</p>
 */
public interface InitialStackSyncState {

    /**
     * 初始全量槽位同步是否已完成（或已超过宽限窗口）。
     *
     * <p>服务端菜单也实现本接口；服务端不会收到 {@code initializeContents}，因此服务端调用
     * 方不要依赖它的返回值。</p>
     */
    boolean dimblend$isInitialStackSyncSettled();
}
