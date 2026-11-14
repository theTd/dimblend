package dimblend.experience.compat.create.vault;

/**
 * 读取存量-计件：以"件"为单位。
 *
 * <p>总量 = 总格数 × 64；数量 = Σ 每格 {@code count × (64 / maxStackSize)}，
 * 即一整堆折算 64 件：64 堆叠的每个算 1 件、16 堆叠的每个算 4 件、不可堆叠的每个算 64 件。</p>
 */
public final class VaultItemCountDisplaySource extends VaultStorageDisplaySource {

    @Override
    protected long currentOf(VaultStorageMath.Tally tally) {
        return tally.usedPieces();
    }

    @Override
    protected long totalOf(VaultStorageMath.Tally tally) {
        return tally.totalPieces();
    }
}
