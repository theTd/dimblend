package dimblend.experience.chassis;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * 新装配标准车架的身份登记入口。
 *
 * <p>只在装配成功后调用。标称高度来自装配锚点方块 y（蓝图块层），不是当前沉车
 * {@code pos}：标准车架锚点 y=65，deck 标称 66；轨道块 y=64。已有登记不覆盖，
 * 避免重新装配/重载把手工校准的数值冲掉。</p>
 *
 * <p>登记只写身份数据；轨锁由 {@code compat.sable.ChassisRailLock} 在后续稳定检查
 * 通过后建立。登记失败不会回滚已经装配好的车体，但会在下一次扫描里保持未锁定。</p>
 */
public final class ChassisAssemblyRegistration {
    /**
     * @return 是否新写入了一条登记；已有身份、非标准蓝图或参数无效时返回 false
     */
    public static boolean register(ServerLevel level, ServerSubLevel car, ChassisBlueprint blueprint,
            BlockPos anchor) {
        if (level == null || car == null || blueprint == null || anchor == null
                || car.isRemoved() || !ChassisRailLockRules.standardBlueprint(blueprint)) {
            return false;
        }
        ChassisRegistryData data = ChassisRegistryData.get(level);
        if (data.registry().get(car.getUniqueId()) != null) {
            return false;
        }
        double nominalHeight = ChassisRailLockRules.nominalDeckHeight(anchor.getY());
        if (!data.registry().register(car.getUniqueId(), blueprint, nominalHeight)) {
            return false;
        }
        data.setDirty();
        return true;
    }

    private ChassisAssemblyRegistration() {
    }
}
