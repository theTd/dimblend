package dimblend.carwash.chassis;

/**
 * 车架脏污的可见快照：外观档位、是否贴碎石与掷出的贴图变体。不可变，客户端网格构建线程可直接读。
 */
public record ChassisGrimeVisual(int level, boolean gravel, int variant) {

    public static final ChassisGrimeVisual CLEAN = new ChassisGrimeVisual(0, false, 0);

    public static ChassisGrimeVisual of(int dirt, int variant) {
        int level = ChassisGrimeRules.dirtLevel(dirt);
        return level == 0 ? CLEAN : new ChassisGrimeVisual(level, ChassisGrimeRules.hasGravel(dirt), variant & 0xFF);
    }

    public boolean isClean() {
        return level == 0;
    }

    public boolean hasGravel() {
        return gravel;
    }

    public int dirtVariant() {
        return ChassisGrimeRules.dirtVariant(variant);
    }

    public int gravelVariant() {
        return ChassisGrimeRules.gravelVariant(variant);
    }
}
