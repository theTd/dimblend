package dimblend.carwash.chassis;

/**
 * 车架脏值规则：常量与纯计算（无 Minecraft 依赖，单测直接覆盖）。
 *
 * <p>脏值 0–511。外观档位 = 脏值 / 64（0–7）；档位每次变化都换一张新的随机镂空泥贴图
 * （“更新/替换”，不叠层），镂空比例随档位从 90% 线性降到 20%（见图集声明）。
 * 脏值大于 256 时上方一格再贴一张 80% 镂空的碎石，且此后每次行驶弄脏都重掷更换碎石图。
 * 每次换图掷 1 字节变体号：低 4 位选泥贴图、高 4 位选碎石贴图。</p>
 */
public final class ChassisGrimeRules {

    public static final int MAX_DIRT = 511;
    /** 每到此值的倍数换一次泥贴图。 */
    public static final int LEVEL_STEP = 64;
    /** 最高外观档位（7）。 */
    public static final int MAX_LEVEL = MAX_DIRT / LEVEL_STEP;
    /** 脏值大于此值时上方一格额外贴碎石。 */
    public static final int GRAVEL_THRESHOLD = 256;

    /** 水桶/湿海绵/喷淋单次清洗量。 */
    public static final int WASH_AMOUNT = 32;
    /** 手持泥土右键单次增加量。 */
    public static final int SOIL_AMOUNT = 32;
    /** 下雨单次清洗量：每秒按 {@link #RAIN_WASH_CHANCE} 概率判定一次。 */
    public static final int RAIN_WASH_AMOUNT = 1;
    /** 下雨清洗的每秒触发概率（期望 −0.5/秒）。 */
    public static final double RAIN_WASH_CHANCE = 0.5;
    /** 下雨清洗下限：脏值降到此值后雨不再洗（期望耗时约 (511−64)/0.5 ≈ 15 分钟）。 */
    public static final int RAIN_WASH_FLOOR = 64;

    /** 车厢速度须严格大于此值（m/s）才会积灰。 */
    public static final double MIN_SOILING_SPEED = 4.0;
    /** 积灰概率 = (速度 / 12) × 0.5（封顶 100%），即到此速度（m/s）时满概率。 */
    public static final double FULL_CHANCE_SPEED = 24.0;
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

    /** 脏值大于 {@link #GRAVEL_THRESHOLD} 时上方一格贴碎石。 */
    public static boolean hasGravel(int dirt) {
        return clampDirt(dirt) > GRAVEL_THRESHOLD;
    }

    /** 车厢速度对应的每秒积灰概率（0 = 不积灰）。 */
    public static double soilingChance(double speed) {
        return soilingChance(speed, 0.5D);
    }

    public static double soilingChance(double speed, double multiplier) {
        if (!(speed > MIN_SOILING_SPEED)) {
            return 0.0;
        }
        return Math.max(0.0D, Math.min(1.0D, speed / 12.0D * multiplier));
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
