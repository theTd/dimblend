package dimblend.experience.compat.sophisticated;

/**
 * 切石机（block converter）配方容器的动态绑定钩子。
 *
 * <p>{@code BlockConverterRecipeContainer} 的 {@code inputSlot}、{@code inputInventory}、
 * {@code get/setLastSelectedRecipeId} 全部在构造时绑定当时的 wrapper（1.4.38 与 1.5.38 相同），
 * 所以模块槽同步替换 wrapper 后，输入格/选中配方仍读写分离库存——这是上游未修的部分。
 *
 * <p>由 {@code BlockConverterRecipeContainerMixin} 实现：
 * {@link #dimblend$bindToOwnerContainer(Object)} 把四处改为"调用时解析当前 wrapper"，
 * {@link #dimblend$refreshFromWrapper()} 在新 wrapper 生效后重算输出。</p>
 */
public interface BlockConverterBindable {

    /**
     * 把输入槽、合成矩阵、配方 id 读写改为跟随 {@code ownerUpgradeContainer} 当前的 wrapper。
     *
     * @param ownerUpgradeContainer 持有本配方容器的 {@code BlockConverterUpgradeContainer}
     */
    void dimblend$bindToOwnerContainer(Object ownerUpgradeContainer);

    /** wrapper 被替换后重算输入变化（等同一次输入格 {@code setChanged}）。 */
    void dimblend$refreshFromWrapper();
}
