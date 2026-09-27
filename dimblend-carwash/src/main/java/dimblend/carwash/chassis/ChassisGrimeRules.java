package dimblend.carwash.chassis;

/**
 * 车架脏值规则：常量与纯计算（无 Minecraft 依赖，单测直接覆盖）。
 *
 * <p>脏值 0–255。每到 32 的倍数贴一层泥（层数 = 脏值 / 32，0–7）；达到该倍数时脏值
 * 大于 128 的层（第 5–7 层，即 160/192/224）额外在上方一格贴一层碎石。每层的随机镂空
 * 图案用 1 字节变体号记录：低 4 位选泥贴图、高 4 位选碎石贴图。</p>
 */
public final class ChassisGrimeRules {

    public static final int MAX_DIRT = 255;
    /** 每到此值的倍数贴一层泥。 */
    public static final int LAYER_STEP = 32;
    /** 脏值大于此值时，新达到的层额外贴碎石。 */
    public static final int GRAVEL_THRESHOLD = 128;
    /** 水桶/湿海绵/喷淋单次清洗量。 */
    public static final int WASH_AMOUNT = 16;
    /** 手持泥土右键单次增加量。 */
    public static final int SOIL_AMOUNT = 16;
    /** 下雨时每分钟清洗量。 */
    public static final int RAIN_WASH_AMOUNT = 16;

    /** 车厢速度须严格大于此值（m/s）才会积灰。 */
    public static final double MIN_SOILING_SPEED = 4.0;
    /** 积灰概率 = 速度 / 此值（封顶 100%）。 */
    public static final double FULL_CHANCE_SPEED = 12.0;
    /** 积灰掷骰间隔：每秒一次。 */
    public static final int SOILING_ROLL_INTERVAL_TICKS = 20;
    /** 雨水清洗间隔：每分钟一次。 */
    public static final int RAIN_WASH_INTERVAL_TICKS = 1200;
    /** 喷淋清洗冷却：0.25 秒。 */
    public static final int SPRAY_WASH_COOLDOWN_TICKS = 5;

    /** 每套镂空贴图的变体数（须为 2 的幂，与 4 位变体号对应）。 */
    public static final int VARIANTS_PER_SET = 16;
    /** 第一个带碎石的层号。 */
    public static final int FIRST_GRAVEL_LAYER = GRAVEL_THRESHOLD / LAYER_STEP + 1;

    public static int clampDirt(int dirt) {
        return Math.max(0, Math.min(MAX_DIRT, dirt));
    }

    /** 当前贴着的泥层数（0–7）。 */
    public static int dirtLayers(int dirt) {
        return clampDirt(dirt) / LAYER_STEP;
    }

    /** 当前贴着的碎石层数（0–3）。碎石第 j 层对应泥层 {@code FIRST_GRAVEL_LAYER - 1 + j}。 */
    public static int gravelLayers(int dirt) {
        return Math.max(0, dirtLayers(dirt) - FIRST_GRAVEL_LAYER + 1);
    }

    /** 车厢速度对应的单次积灰概率（0 = 不积灰）。 */
    public static double soilingChance(double speed) {
        if (!(speed > MIN_SOILING_SPEED)) {
            return 0.0;
        }
        return Math.min(1.0, speed / FULL_CHANCE_SPEED);
    }

    /** 第 {@code layer} 层（从 1 起）的变体字节。 */
    public static int layerVariant(long variants, int layer) {
        return (int) (variants >>> variantShift(layer)) & 0xFF;
    }

    public static long withLayerVariant(long variants, int layer, int variant) {
        int shift = variantShift(layer);
        return (variants & ~(0xFFL << shift)) | ((long) (variant & 0xFF) << shift);
    }

    /** 只保留前 {@code layers} 层的变体（更高层未贴，不参与比较）。 */
    public static long keepLayers(long variants, int layers) {
        if (layers <= 0) {
            return 0L;
        }
        if (layers >= 8) {
            return variants;
        }
        return variants & ((1L << (layers * 8)) - 1);
    }

    public static int dirtVariant(int layerVariant) {
        return layerVariant & 0x0F;
    }

    public static int gravelVariant(int layerVariant) {
        return (layerVariant >>> 4) & 0x0F;
    }

    private static int variantShift(int layer) {
        if (layer < 1 || layer > 8) {
            throw new IllegalArgumentException("layer out of range: " + layer);
        }
        return (layer - 1) * 8;
    }

    private ChassisGrimeRules() {
    }
}
