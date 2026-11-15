package dimblend.experience.compat.firefightingadd;

import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * 高压水泵（{@code createfirefightingadd:high_pressure_pump}）外部倍率介入：
 * 传输距离与应力需求均放大 {@value #MULTIPLIER} 倍（默认 32 格 → 512 格，8.0 SU/RPM → 128.0 SU/RPM）。
 */
public final class HighPressurePumpTuning {

    public static final int MULTIPLIER = 16;
    /** 高压水泵默认是 Create 普通泵（16 格）的 2 倍，再乘 16 倍后需让未加压管道传播唤醒覆盖 32 倍基础泵距。 */
    public static final int PIPE_WAKE_RANGE_FACTOR = MULTIPLIER * 2;
    public static final ResourceLocation HIGH_PRESSURE_PUMP_ID =
            ResourceLocation.fromNamespaceAndPath("createfirefightingadd", "high_pressure_pump");

    public static int scalePumpRange(int originalRange) {
        if (originalRange <= 0) {
            return MULTIPLIER;
        }
        long scaled = (long) originalRange * MULTIPLIER;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, scaled));
    }

    public static int scalePipeWakeRange(int createBasePumpRange) {
        if (createBasePumpRange <= 0) {
            return 16 * PIPE_WAKE_RANGE_FACTOR;
        }
        long scaled = (long) createBasePumpRange * PIPE_WAKE_RANGE_FACTOR;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(createBasePumpRange, scaled));
    }

    public static double scaleBlockStressImpact(@Nullable ResourceLocation blockId, double originalImpact) {
        if (HIGH_PRESSURE_PUMP_ID.equals(blockId)) {
            return originalImpact * MULTIPLIER;
        }
        return originalImpact;
    }

    private HighPressurePumpTuning() {
    }
}
