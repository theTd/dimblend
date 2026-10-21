package dimblend.experience.exploration;

/**
 * A12 旋转维度怪物密度压制的上限换算（纯函数，供 mixin 与单测共用）。
 * 原版怪物全局上限 = 类别每区块上限(MONSTER=70) × 可刷区块数 / 289；
 * 压制先把每区块上限乘倍率取整，再套原版同一公式（保留原版整数除法截断口径），
 * 倍率 1.0 时结果与原版逐项一致。
 */
public final class RotatingMonsterCap {

    // NaturalSpawner.MAGIC_NUMBER（17×17 可刷区块基准）是包私有常量，无法引用，只能在此复刻
    static final int SPAWNABLE_CHUNK_BASELINE = 289;

    public static int scaledCap(int maxInstancesPerChunk, int spawnableChunkCount, double multiplier) {
        return (int) (maxInstancesPerChunk * multiplier) * spawnableChunkCount / SPAWNABLE_CHUNK_BASELINE;
    }

    private RotatingMonsterCap() {
    }
}
