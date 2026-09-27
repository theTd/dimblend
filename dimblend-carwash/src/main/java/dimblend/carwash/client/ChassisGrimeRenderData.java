package dimblend.carwash.client;

import dimblend.carwash.chassis.ChassisGrimeVisual;

/**
 * 网格构建时算好的车架脏污渲染数据：脏污快照 + 上方一格是否为实心不透明方块（决定碎石贴法）。
 */
public record ChassisGrimeRenderData(ChassisGrimeVisual visual, boolean aboveSolid) {
}
