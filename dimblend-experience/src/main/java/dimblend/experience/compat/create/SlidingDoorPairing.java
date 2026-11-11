package dimblend.experience.compat.create;

import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Pure block-state and placement logic for the Create sliding-door pair feature. */
public final class SlidingDoorPairing {
    private SlidingDoorPairing() {
    }

    public static boolean isSupported(BlockState state) {
        if (!(state.getBlock() instanceof SlidingDoorBlock)) {
            return false;
        }
        String id = state.getBlock().builtInRegistryHolder().key().location().toString();
        return id.equals("create:andesite_door") || id.equals("create:brass_door")
                || id.equals("create:copper_door") || id.equals("create:train_door");
    }

    public static BlockPos lowerPos(BlockPos pos, BlockState state) {
        return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER
                ? pos.below() : pos;
    }

    /**
     * Returns the adjacent lower-half position. The existing hinge decides which side is occupied;
     * the caller always writes the new door with the opposite hinge, so the result is a true
     * left/right pair even when the original door was placed with either hinge.
     */
    public static BlockPos partnerLowerPos(BlockPos lowerPos, BlockState lowerState) {
        Direction facing = lowerState.getValue(BlockStateProperties.HORIZONTAL_FACING);
        DoorHingeSide hinge = lowerState.getValue(BlockStateProperties.DOOR_HINGE);
        return lowerPos.relative(hinge == DoorHingeSide.LEFT ? facing.getClockWise() : facing.getCounterClockWise());
    }

    public static boolean isAlreadyPaired(Level level, BlockPos lowerPos, BlockState lowerState) {
        BlockState other = level.getBlockState(partnerLowerPos(lowerPos, lowerState));
        return other.is(lowerState.getBlock())
                && other.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                && other.getValue(BlockStateProperties.HORIZONTAL_FACING)
                == lowerState.getValue(BlockStateProperties.HORIZONTAL_FACING)
                && other.getValue(BlockStateProperties.DOOR_HINGE)
                != lowerState.getValue(BlockStateProperties.DOOR_HINGE);
    }

    public static boolean canPlacePartner(Level level, BlockPos partnerLower) {
        return level.getBlockState(partnerLower).canBeReplaced()
                && level.getBlockState(partnerLower.above()).canBeReplaced();
    }

    public static boolean canPair(Level level, BlockPos clicked, ItemStack held) {
        BlockState clickedState = level.getBlockState(clicked);
        if (!isSupported(clickedState) || held.isEmpty() || Block.byItem(held.getItem()) != clickedState.getBlock()) {
            return false;
        }
        BlockPos lower = lowerPos(clicked, clickedState);
        BlockState lowerState = level.getBlockState(lower);
        if (!isSupported(lowerState) || lowerState.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) != DoubleBlockHalf.LOWER) {
            return false;
        }
        return !isAlreadyPaired(level, lower, lowerState) && canPlacePartner(level, partnerLowerPos(lower, lowerState));
    }

    /** Places the second 1x2 door and normalizes both halves to the same animation state. */
    public static boolean pair(ServerLevel level, BlockPos clicked, ItemStack held) {
        if (!canPair(level, clicked, held)) {
            return false;
        }
        BlockState clickedState = level.getBlockState(clicked);
        BlockPos lower = lowerPos(clicked, clickedState);
        BlockState source = level.getBlockState(lower);
        BlockPos partner = partnerLowerPos(lower, source);
        DoorHingeSide opposite = source.getValue(BlockStateProperties.DOOR_HINGE) == DoorHingeSide.LEFT
                ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;

        BlockState partnerLowerState = source
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.DOOR_HINGE, opposite);
        BlockState partnerUpperState = partnerLowerState
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS | Block.UPDATE_IMMEDIATE;
        level.setBlock(partner, partnerLowerState, flags);
        level.setBlock(partner.above(), partnerUpperState, flags);
        return true;
    }
}
