package dimblend.carwash.server;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dimblend.carwash.chassis.ChassisBlocks;
import dimblend.carwash.chassis.ChassisGrimeBehaviour;
import dimblend.carwash.chassis.ChassisGrimeRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端清洗/弄脏操作，右键与喷淋批次共用。
 * 清洗（脏值 -32）生效时以被洗砖块为中心播放 {@code minecraft:entity.slime.jump}（pitch 0.5）。
 */
public final class ChassisWashing {

    /**
     * 清洗一次：本块脏值非 0 则减 32；为 0 则任选一个脏值非 0 的相邻车架减 32。
     */
    public static void washOnce(ServerLevel level, BlockPos pos) {
        ChassisGrimeBehaviour self = chassisAt(level, pos, false);
        if (self != null && self.dirt() > 0) {
            self.changeDirt(-ChassisGrimeRules.WASH_AMOUNT, level.getRandom());
            playWashSound(level, pos);
            return;
        }
        if (!ChassisBlocks.isChassis(level.getBlockState(pos))) {
            return;
        }
        List<BlockPos> dirtyNeighbours = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            BlockPos neighbourPos = pos.relative(direction);
            ChassisGrimeBehaviour neighbour = chassisAt(level, neighbourPos, false);
            if (neighbour != null && neighbour.dirt() > 0) {
                dirtyNeighbours.add(neighbourPos);
            }
        }
        if (!dirtyNeighbours.isEmpty()) {
            BlockPos picked = dirtyNeighbours.get(level.getRandom().nextInt(dirtyNeighbours.size()));
            ChassisGrimeBehaviour neighbour = chassisAt(level, picked, false);
            if (neighbour != null) {
                neighbour.changeDirt(-ChassisGrimeRules.WASH_AMOUNT, level.getRandom());
                playWashSound(level, picked);
            }
        }
    }

    /** 手持泥土右键：本块脏值加 32。 */
    public static void soil(ServerLevel level, BlockPos pos) {
        ChassisGrimeBehaviour self = chassisAt(level, pos, true);
        if (self != null) {
            self.changeDirt(ChassisGrimeRules.SOIL_AMOUNT, level.getRandom());
        }
    }

    /** 清洗生效音效：史莱姆跳跃，音高 0.5，方块中心，广播给附近玩家。 */
    private static void playWashSound(ServerLevel level, BlockPos pos) {
        level.playSound(null, pos, SoundEvents.SLIME_JUMP, SoundSource.BLOCKS, 1.0F, 0.5F);
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
