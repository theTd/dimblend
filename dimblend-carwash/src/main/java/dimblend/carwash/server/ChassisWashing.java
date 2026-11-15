package dimblend.carwash.server;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dimblend.blocks.api.CreativeCopycatHiding;
import dimblend.carwash.chassis.ChassisBlocks;
import dimblend.carwash.chassis.ChassisGrimeBehaviour;
import dimblend.carwash.chassis.ChassisGrimeRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端清洗/弄脏操作，右键与喷淋批次共用。
 * 单体清洗（湿海绵、喷淋）生效时以被洗砖块为中心播放 {@code minecraft:entity.slime.jump}（pitch 0.5）；
 * 水桶面积清洗改播空桶音，见 {@link ChassisHandInteractions}。
 *
 * <p>水桶口径：以右键格为中心的 {@code 5x5x5} 立方体内，每个带脏值的可见车架各洗一次；
 * 单体清洗沿用 {@link #washOnce} 的「本块没脏就洗一个相邻格」兜底。</p>
 */
public final class ChassisWashing {

    /** 水桶清洗半径：dx/dy/dz ∈ [-2, 2]，共 125 格。 */
    public static final int AREA_RADIUS = 2;

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

    /**
     * 水桶：{@code 5x5x5} 内每个带脏值的可见车架各洗一次（−32），不做相邻格兜底，
     * 因此洗到的块数与判定过的脏块一一对应。音效由调用方播（空桶音）。
     */
    public static void washArea5x5x5(ServerLevel level, BlockPos pos) {
        RandomSource random = level.getRandom();
        for (BlockPos cell : cube5x5x5(pos)) {
            if (!isChassisVisibleBlock(level, cell)) {
                continue;
            }
            ChassisGrimeBehaviour grime = chassisAt(level, cell, false);
            if (grime != null && grime.dirt() > 0) {
                grime.changeDirt(-ChassisGrimeRules.WASH_AMOUNT, random);
            }
        }
    }

    /** {@code 5x5x5} 内是否有可见车架仍带脏值：决定水桶这一次右键算不算有效使用（不有效就不扣桶）。 */
    public static boolean canWashArea5x5x5(Level level, BlockPos pos) {
        for (BlockPos cell : cube5x5x5(pos)) {
            if (!isChassisVisibleBlock(level, cell)) {
                continue;
            }
            ChassisGrimeBehaviour grime = grimeAt(level, cell);
            if (grime != null && grime.dirt() > 0) {
                return true;
            }
        }
        return false;
    }

    /** 该格是否可见车架：车架形态且未隐藏（隐藏车架既不吃点击，也不参与水桶面积判定）。 */
    public static boolean isChassisVisibleBlock(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel
                && serverLevel.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return false;
        }
        if (!level.isLoaded(pos)) {
            return false;
        }
        return ChassisBlocks.isChassis(level.getBlockState(pos))
                && !CreativeCopycatHiding.isHidden(level, pos);
    }

    /** 以 center 为中心的 {@code 5x5x5} 全部格（125 个，含中心），顺序 dx → dy → dz。 */
    public static List<BlockPos> cube5x5x5(BlockPos center) {
        int side = AREA_RADIUS * 2 + 1;
        List<BlockPos> cells = new ArrayList<>(side * side * side);
        for (int dx = -AREA_RADIUS; dx <= AREA_RADIUS; dx++) {
            for (int dy = -AREA_RADIUS; dy <= AREA_RADIUS; dy++) {
                for (int dz = -AREA_RADIUS; dz <= AREA_RADIUS; dz++) {
                    cells.add(center.offset(dx, dy, dz));
                }
            }
        }
        return List.copyOf(cells);
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

    /** 只读行为，不补挂；行为表正在被首次读包补挂时读失败按「没脏值」处理。 */
    @Nullable
    private static ChassisGrimeBehaviour grimeAt(Level level, BlockPos pos) {
        try {
            return BlockEntityBehaviour.get(level, pos, ChassisGrimeBehaviour.TYPE);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 该位置若是车架则返回其脏值行为；{@code attach} 为 false 时不补挂（没挂即脏值 0）。
     */
    @Nullable
    static ChassisGrimeBehaviour chassisAt(ServerLevel level, BlockPos pos, boolean attach) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return null;
        }
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
