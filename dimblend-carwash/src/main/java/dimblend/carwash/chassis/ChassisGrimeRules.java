package dimblend.carwash.chassis;

/**
 * 车架脏值规则：常量与纯计算（无 Minecraft 依赖，单测直接覆盖）。
 *
 * <p>脏值 0–511。外观档位 = 脏值 / 64（0–7）；档位每次变化都换一张新的随机镂空泥贴图
 * （“更新/替换”，不叠层），镂空比例随档位从 90% 线性降到 20%（见图集声明）。
 * 档位对应的 64 倍数大于 256（第 5–7 档，即 320/384/448）时上方一格再贴一张 50% 镂空的碎石。
 * 每次换图掷 1 字节变体号：低 4 位选泥贴图、高 4 位选碎石贴图。</p>
 */
public final class ChassisGrimeRules {

    public static final int MAX_DIRT = 511;
    /** 每到此值的倍数换一次泥贴图。 */
    public static final int LEVEL_STEP = 64;
    /** 最高外观档位（7）。 */
    public static final int MAX_LEVEL = MAX_DIRT / LEVEL_STEP;
    /** 档位对应的倍数大于此值时额外贴碎石。 */
    public static final int GRAVEL_THRESHOLD = 256;
    /** 第一个带碎石的档位（5）。 */
    public static final int FIRST_GRAVEL_LEVEL = GRAVEL_THRESHOLD / LEVEL_STEP + 1;

    /** 水桶/湿海绵/喷淋单次清洗量。 */
    public static final int WASH_AMOUNT = 32;
    /** 手持泥土右键单次增加量。 */
    public static final int SOIL_AMOUNT = 32;
    /** 下雨时每秒清洗量：511 约 102 秒降到 0（要求 2 分钟内）。 */
    public static final int RAIN_WASH_PER_SECOND = 5;

    /** 车厢速度须严格大于此值（m/s）才会积灰。 */
    public static final double MIN_SOILING_SPEED = 4.0;
    /** 积灰概率 = 速度 / 此值（封顶 100%）。 */
    public static final double FULL_CHANCE_SPEED = 12.0;
    /** 行驶积灰与雨水清洗的结算间隔：每秒一次，同一刻集中结算，换图同步合并进一次网格重建。 */
    public static final int UPDATE_INTERVAL_TICKS = 20;
    /** 喷淋批量结算间隔：0.5 秒内被命中的车架各算一次清洗。 */
    public static final int SPRAY_BATCH_INTERVAL_TICKS = 10;

    /** 每档镂空贴图的随机变体数（须为 2 的幂，与 4 位变体号对应）。 */
    public static final int VARIANTS_PER_SET = 16;

    public static int clampDirt(int dirt) {
        return Math.max(0, Math.min(MAX_DIRT, dirt));
    }

    /** 外观档位（0 = 干净，1–7）。 */
    public static int dirtLevel(int dirt) {
        return clampDirt(dirt) / LEVEL_STEP;
    }

    public static boolean hasGravel(int level) {
        return level >= FIRST_GRAVEL_LEVEL;
    }

    /** 车厢速度对应的每秒积灰概率（0 = 不积灰）。 */
    public static double soilingChance(double speed) {
        if (!(speed > MIN_SOILING_SPEED)) {
            return 0.0;
        }
        return Math.min(1.0, speed / FULL_CHANCE_SPEED);
    }

    public static int dirtVariant(int variant) {
        return variant & 0x0F;
    }

    public static int gravelVariant(int variant) {
        return (variant >>> 4) & 0x0F;
    }

    private ChassisGrimeRules() {
    }
}
