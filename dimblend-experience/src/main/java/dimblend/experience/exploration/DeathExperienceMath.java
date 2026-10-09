package dimblend.experience.exploration;

/** A1 死亡经验的比率缩放纯函数（DeathRules 的事件接线与本类分离以便单测）。 */
public final class DeathExperienceMath {

    /**
     * 按清空比率缩放死亡前经验：保留 (等级+进度) × (1 − clearRatio)，总量同比缩放。
     * clearRatio 取值 0.00–1.00（配置与面板双侧钳制）。
     */
    public static ScaledExperience scale(int level, float progress, int total, double clearRatio) {
        double keep = 1.0D - clearRatio;
        // int + float 会按 float 精度相加（12 + 0.4F = 12.399999618530273），先升 double 再算
        double scaled = (level + (double) progress) * keep;
        int newLevel = (int) scaled;
        float newProgress = (float) (scaled - newLevel);
        // double 乘法向下偏一个 ulp 时 progress 可经 float 强转进位成 1.0f
        // （实测：比率 0.80 + 整 5 级 → scaled 0.9999999999999998），
        // 违反原版 experienceProgress < 1 不变式，回卷一级归正
        if (newProgress >= 1.0F) {
            newLevel++;
            newProgress = 0.0F;
        }
        return new ScaledExperience(newLevel, newProgress, (int) Math.round(total * keep));
    }

    public record ScaledExperience(int level, float progress, int total) {
    }

    private DeathExperienceMath() {
    }
}
