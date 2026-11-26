package dimblend.experience.compat.connected.mobdisplay;

import javax.annotation.Nullable;
import net.minecraft.nbt.CompoundTag;

/**
 * 生物展示催化剂的<b>纯规则层</b>（无方块实体 / 无渲染器 / 无注册表依赖，可单测）：
 *
 * <ul>
 * <li>{@code creatorsword:capture_box} 的三个约定键解析（{@code HasCapturedEntity} /
 *     {@code CapturedEntityType} / {@code CapturedEntityNbt}）；</li>
 * <li>展示缩放：最长边塞进 {@link #MAX_DIMENSION} 格，且永不放大超过 {@code 0.8}；</li>
 * <li>展示自转角度：按墙钟毫秒线性推进（暂停游戏也继续转）。</li>
 * </ul>
 *
 * <p>之所以单独抽出来：这三条口径以前分别散在 {@link MobDisplayCatalystBlockEntity}
 * 与 {@link MobDisplayCatalystRenderer} 里，单测跑不起来（要方块实体注册表 / GL 上下文）。</p>
 */
public final class MobDisplayCatalystRules {

    /** 盒子里"是否装过生物"的布尔键（creatorsword 的抓捕盒格式）。 */
    public static final String TAG_HAS_CAPTURED = "HasCapturedEntity";

    /** 盒子里生物类型 id 的字符串键，如 {@code "minecraft:cow"}。 */
    public static final String TAG_CAPTURED_TYPE = "CapturedEntityType";

    /** 盒子里生物 NBT 的复合键。 */
    public static final String TAG_CAPTURED_NBT = "CapturedEntityNbt";

    /** 展示用的最大占位（格）：最大的那只生物也不许超过这个边长。 */
    public static final float MAX_DIMENSION = 0.8F;

    /** 自转：每 25 ms 一度 ≈ 2.5 秒一圈。 */
    public static final double DEGREES_PER_MILLISECOND = 1.0D / 25.0D;

    /** 尺寸下界，避免 0 宽高实体（如被清空渲染的标记实体）除零。 */
    private static final float MIN_DIMENSION = 0.001F;

    /** 盒子自定义数据里是否真的装了一只生物。 */
    public static boolean hasCapturedEntity(@Nullable CompoundTag boxData) {
        return boxData != null && boxData.getBoolean(TAG_HAS_CAPTURED);
    }

    /** 盒子记录的生物注册 id；没记录/没装时为 {@code ""}（**不是** null，方便直接喂给 {@code EntityType.byString}）。 */
    public static String capturedEntityTypeId(@Nullable CompoundTag boxData) {
        return boxData == null ? "" : boxData.getString(TAG_CAPTURED_TYPE);
    }

    /** 盒子记录的那份生物 NBT 副本；没有该键时为 {@code null}。 */
    @Nullable
    public static CompoundTag capturedEntityTag(@Nullable CompoundTag boxData) {
        if (boxData == null || !boxData.contains(TAG_CAPTURED_NBT, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        return boxData.getCompound(TAG_CAPTURED_NBT).copy();
    }

    /**
     * 展示缩放：{@code min(0.8 / max(bbW, bbH, 0.001), 0.8)}。
     *
     * <p>把最长的边压进 {@link #MAX_DIMENSION}；比 0.8 格还小的生物<b>不放大</b>（保证小鸡、蝙蝠等
     * 不会变成巨型），所以公式外面还要再取一次 {@code min}。</p>
     */
    public static float displayScale(float boundingBoxWidth, float boundingBoxHeight) {
        float maxDimension = Math.max(boundingBoxWidth, boundingBoxHeight);
        return Math.min(MAX_DIMENSION / Math.max(maxDimension, MIN_DIMENSION), MAX_DIMENSION);
    }

    /** 展示自转角度（度，{@code [0, 360)}），由墙钟毫秒唯一决定。 */
    public static float rotationDegrees(long wallClockMillis) {
        double degrees = (wallClockMillis * DEGREES_PER_MILLISECOND) % 360.0D;
        return (float) degrees;
    }

    private MobDisplayCatalystRules() {
    }
}
