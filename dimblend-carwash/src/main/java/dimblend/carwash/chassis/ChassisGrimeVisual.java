package dimblend.carwash.chassis;

/**
 * 车架脏污的可见快照：泥层数、碎石层数与已贴各层的变体。不可变，客户端渲染线程可直接读。
 */
public record ChassisGrimeVisual(int dirtLayers, int gravelLayers, long layerVariants) {

    public static final ChassisGrimeVisual CLEAN = new ChassisGrimeVisual(0, 0, 0L);

    public static ChassisGrimeVisual of(int dirt, long layerVariants) {
        int dirtLayers = ChassisGrimeRules.dirtLayers(dirt);
        if (dirtLayers == 0) {
            return CLEAN;
        }
        return new ChassisGrimeVisual(dirtLayers, ChassisGrimeRules.gravelLayers(dirt),
                ChassisGrimeRules.keepLayers(layerVariants, dirtLayers));
    }

    public boolean isClean() {
        return dirtLayers == 0;
    }

    /** 第 {@code layer} 层泥（1..dirtLayers）的贴图变体号。 */
    public int dirtVariant(int layer) {
        return ChassisGrimeRules.dirtVariant(ChassisGrimeRules.layerVariant(layerVariants, layer));
    }

    /** 第 {@code gravelLayer} 层碎石（1..gravelLayers）的贴图变体号。 */
    public int gravelVariant(int gravelLayer) {
        int layer = ChassisGrimeRules.FIRST_GRAVEL_LAYER - 1 + gravelLayer;
        return ChassisGrimeRules.gravelVariant(ChassisGrimeRules.layerVariant(layerVariants, layer));
    }
}
