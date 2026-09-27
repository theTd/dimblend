package dimblend.carwash.server;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dimblend.carwash.chassis.ChassisBlocks;
import dimblend.carwash.chassis.ChassisGrimeBehaviour;
import dimblend.carwash.chassis.ChassisGrimeRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端清洗/弄脏操作，右键与喷淋共用。
 */
public final class ChassisWashing {

    /**
     * 清洗一次：本块脏值非 0 则减 16；为 0 则任选一个脏值非 0 的相邻车架减 16。
     */
    public static void washOnce(ServerLevel level, BlockPos pos) {
        ChassisGrimeBehaviour self = chassisAt(level, pos, true);
        if (self == null) {
            return;
        }
        if (self.dirt() > 0) {
            self.changeDirt(-ChassisGrimeRules.WASH_AMOUNT, level.getRandom());
            return;
        }
        List<ChassisGrimeBehaviour> dirtyNeighbours = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            ChassisGrimeBehaviour neighbour = chassisAt(level, pos.relative(direction), false);
            if (neighbour != null && neighbour.dirt() > 0) {
                dirtyNeighbours.add(neighbour);
            }
        }
        if (!dirtyNeighbours.isEmpty()) {
            dirtyNeighbours.get(level.getRandom().nextInt(dirtyNeighbours.size()))
                    .changeDirt(-ChassisGrimeRules.WASH_AMOUNT, level.getRandom());
        }
    }

    /** 喷淋命中：按被命中方块计 0.25 秒冷却，冷却外视同一次清洗。 */
    public static void sprayWash(ServerLevel level, BlockPos pos) {
        ChassisGrimeBehaviour self = chassisAt(level, pos, true);
        if (self != null && self.tryStartSprayWash(level.getGameTime())) {
            washOnce(level, pos);
        }
    }

    /** 手持泥土右键：本块脏值加 16。 */
    public static void soil(ServerLevel level, BlockPos pos) {
        ChassisGrimeBehaviour self = chassisAt(level, pos, true);
        if (self != null) {
            self.changeDirt(ChassisGrimeRules.SOIL_AMOUNT, level.getRandom());
        }
    }

    /**
     * 该位置若是车架则返回其脏值行为；{@code attach} 为 false 时不补挂（没挂即脏值 0）。
     */
    @Nullable
    static ChassisGrimeBehaviour chassisAt(ServerLevel level, BlockPos pos, boolean attach) {
        if (!ChassisBlocks.isChassis(level.getBlockState(pos))) {
            return null;
        }
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof SmartBlockEntity smart)) {
            return null;
        }
        return attach ? ChassisGrimeBehaviour.obtain(smart) : smart.getBehaviour(ChassisGrimeBehaviour.TYPE);
    }

    private ChassisWashing() {
    }
}
