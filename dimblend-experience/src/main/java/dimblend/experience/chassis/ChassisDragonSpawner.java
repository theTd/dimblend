package dimblend.experience.chassis;

import dimblend.dragonchase.api.DragonChaseDebugCars;
import net.minecraft.server.level.ServerLevel;

/**
 * 末影龙追逐车厢的唯一接触点，只在 {@code dimblend_dragonchase} 存在时加载
 * （{@link ChassisPresetSpawner#spawnDragon} 的 ModList 判定之后）。
 *
 * <p>与本模块既有的 {@code DragonChaseTuningBridge} 同一惯例：只引用 {@code dimblend.dragonchase.api}，
 * 缺席时 JVM 根本不会加载它，也就没有 {@code NoClassDefFoundError}。
 */
final class ChassisDragonSpawner {

    private ChassisDragonSpawner() {
    }

    static boolean spawn(ServerLevel level, ChassisPreset preset, int centerX) {
        // ChassisPreset 只有 BRAKE / CRYSTAL 两个 DRAGON 项；NEST 不在这张表里。
        return (preset == ChassisPreset.BRAKE
                ? DragonChaseDebugCars.spawnBrake(level, centerX)
                : DragonChaseDebugCars.spawnCrystal(level, centerX)) != null;
    }
}
