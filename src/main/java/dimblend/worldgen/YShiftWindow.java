package dimblend.worldgen;

/**
 * Noise-envelope translation for {@link YShiftedDensity}. Positive {@code yOffset}
 * keeps {@code minY} and grows height so the original terrain can sit higher;
 * negative {@code yOffset} drops {@code minY} so the original terrain can sit lower.
 * Both edges snap to 16-block section alignment, which {@link net.minecraft.world.level.levelgen.NoiseSettings}
 * requires.
 */
public final class YShiftWindow {
    private YShiftWindow() {
    }

    public static int minY(int sourceMinY, int yOffset) {
        return Math.floorDiv(sourceMinY + Math.min(0, yOffset), 16) * 16;
    }

    public static int height(int sourceMinY, int sourceHeight, int yOffset) {
        int minY = minY(sourceMinY, yOffset);
        int maxY = sourceMinY + sourceHeight + Math.max(0, yOffset);
        maxY = (maxY + 15) / 16 * 16;
        return maxY - minY;
    }
}
