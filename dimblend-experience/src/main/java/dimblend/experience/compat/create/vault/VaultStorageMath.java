package dimblend.experience.compat.create.vault;

/**
 * 物品保险库存量读数口径（纯数学，不碰 {@code ItemStack}/世界，见 {@code VaultStorageMathTest}）。
 *
 * <p>两种口径共用"格"这个概念：保险库每个槽位算 1 格（"位"），一格最多放一件物品的一整堆。</p>
 *
 * <ul>
 *   <li><b>计位</b>：总量 = 总格数；数量 = 非空格数。一件物品哪怕只有 1 个、哪怕它不可堆叠或
 *       只能堆 16 个，都同样只占 1 格，所以数量恒为 {@code 非空格数}。</li>
 *   <li><b>计件</b>：总量 = 总格数 × 64；数量 = Σ 每格 {@code count × (64 / maxStackSize)}。
 *       即一整堆（不论 64/16/1）都折算成 64 件：64 堆叠算 1 件/个、16 堆叠算 4 件/个、
 *       不可堆叠算 64 件/个。</li>
 * </ul>
 *
 * <p>计件口径是"以 64 为一件组"的加权件数，一格里的物品可能超过 64 件（例如 64 把剑），
 * 因此 {@link #progress(long, long)} 会压到 [0,1]；直接展示的"数量/总量"则按原始数给出。</p>
 */
public final class VaultStorageMath {

    /** 一格折算的件数：一整堆 = 64 件。 */
    public static final int GROUP_PIECES = 64;

    /**
     * 计件：单个堆叠折算的件数。
     *
     * <p>{@code maxStackSize <= 0} 按 1 处理（不除零）；{@code count <= 0} 折算 0。
     * 非 64 整除的怪异 maxStackSize 一律向下取整，保证返回值单调且不溢出。</p>
     */
    public static long pieces(int count, int maxStackSize) {
        if (count <= 0) {
            return 0L;
        }
        int max = Math.max(1, maxStackSize);
        return (long) count * GROUP_PIECES / max;
    }

    /** 进度：{@code current / total} 压到 [0,1]；{@code total <= 0} 返回 0。 */
    public static float progress(long current, long total) {
        if (total <= 0) {
            return 0.0F;
        }
        double level = (double) current / (double) total;
        return (float) Math.min(1.0D, Math.max(0.0D, level));
    }

    /** 逐格累加器；与 {@code ItemStack} 解耦，便于纯单测。 */
    public static final class Tally {
        private int totalSlots;
        private int usedSlots;
        private long usedPieces;

        /** 记一格：{@code count <= 0} 表示空格；{@code maxStackSize} 只对非空格有意义。 */
        public void addSlot(int count, int maxStackSize) {
            totalSlots++;
            if (count <= 0) {
                return;
            }
            usedSlots++;
            usedPieces += pieces(count, maxStackSize);
        }

        /** 总格数（每个保险库方块 {@code vaultCapacity} 格，整台多结构求和）。 */
        public int totalSlots() {
            return totalSlots;
        }

        /** 计位 · 数量：非空格数，一件也算一格。 */
        public int usedGroups() {
            return usedSlots;
        }

        /** 计件 · 数量：按 {@code 64 / maxStackSize} 折算后的件数总和。 */
        public long usedPieces() {
            return usedPieces;
        }

        /** 计件 · 总量：总格数 × 64。 */
        public long totalPieces() {
            return (long) totalSlots * GROUP_PIECES;
        }
    }

    private VaultStorageMath() {
    }
}
