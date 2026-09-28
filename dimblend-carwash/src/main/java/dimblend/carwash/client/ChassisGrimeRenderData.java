package dimblend.carwash.client;

import dimblend.carwash.chassis.ChassisGrimeVisual;

/**
 * 网格构建时算好的车架脏污渲染数据：脏污快照 + 上方一格方块的种类（决定碎石贴法）。
 */
public record ChassisGrimeRenderData(ChassisGrimeVisual visual, Above above) {

    /** 车架上方一格（y+1）的方块种类。 */
    public enum Above {
        /** 空气：不贴碎石。 */
        AIR,
        /** 非透明实心方块：贴其四个侧面与顶面（整张）。 */
        OPAQUE,
        /** 其余（透明或非整方块）：贴四个侧面，去掉上半。 */
        OTHER
    }
}
