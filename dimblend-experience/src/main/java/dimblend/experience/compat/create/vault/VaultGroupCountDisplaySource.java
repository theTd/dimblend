package dimblend.experience.compat.create.vault;

/**
 * 读取存量-计位：以"格"为单位。
 *
 * <p>总量 = 整台保险库的总格数；数量 = 非空格数（一件也算一格，与 64/16/不可堆叠无关）。</p>
 */
public final class VaultGroupCountDisplaySource extends VaultStorageDisplaySource {

    @Override
    protected long currentOf(VaultStorageMath.Tally tally) {
        return tally.usedGroups();
    }

    @Override
    protected long totalOf(VaultStorageMath.Tally tally) {
        return tally.totalSlots();
    }
}
