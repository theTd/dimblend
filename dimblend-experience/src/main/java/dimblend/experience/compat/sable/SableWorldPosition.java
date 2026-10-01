package dimblend.experience.compat.sable;

import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * 把坐标投影到真实世界坐标：Sable 载具（子层级）上的方块与乘客在 level 内记的是子层级
 * 坐标，需经 {@code Sable.HELPER.projectOutOfSubLevel} 换算（与 {@code TrainOffStructureRules}
 * 同型）。不在子层级内的坐标原样返回；未装 Sable 时直接返回原坐标。
 *
 * <p>对 {@code Sable.HELPER} 的引用只在 {@code isLoaded} 守卫通过后触达，JVM 惰性解析，
 * 缺 Sable 时不会加载其类型。</p>
 */
public final class SableWorldPosition {

    public static Vec3 project(Level level, Vec3 position) {
        if (!ModList.get().isLoaded("sable")) {
            return position;
        }
        return Sable.HELPER.projectOutOfSubLevel(level, position);
    }

    /**
     * 方块位置版：按所在方块中心投影后取整。不在子层级内的位置原样返回，对已投影的
     * 世界坐标幂等（重复调用不会二次偏移）。
     */
    public static BlockPos projectBlock(Level level, BlockPos pos) {
        return BlockPos.containing(project(level, pos.getCenter()));
    }

    private SableWorldPosition() {
    }
}
