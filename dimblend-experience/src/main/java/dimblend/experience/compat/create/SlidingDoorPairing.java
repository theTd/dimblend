package dimblend.experience.compat.create;

import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlockEntity;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Pure server-safe state, shape, whitelist, and packet-clamp logic for single-cell centered 2-wide
 * Create sliding doors ({@code dimblend_paired=true}).
 */
public final class SlidingDoorPairing {
    public static final BooleanProperty PAIRED = BooleanProperty.create("dimblend_paired");

    /**
     * Maximum per-axis offset from {@code doorPos.getCenter()} allowed inside
     * {@code ServerboundUseItemOnPacket}; {@code ServerGamePacketListenerImpl#handleUseItemOn}
     * enforces {@code < 1.0000001} on all three axes.
     */
    public static final double MAX_HIT_CENTER_OFFSET = 0.999;

    private static final Set<String> TARGET_DOOR_IDS = Set.of(
            "create:andesite_door",
            "create:brass_door",
            "create:copper_door",
            "create:train_door"
    );

    private static final Map<Direction, VoxelShape> CLOSED_PAIRED_SHAPES = buildClosedShapes();
    private static final Map<Direction, VoxelShape> OPEN_SLIDING_PAIRED_SHAPES = buildOpenShapes(false);
    private static final Map<Direction, VoxelShape> OPEN_FOLDING_PAIRED_SHAPES = buildOpenShapes(true);

    private SlidingDoorPairing() {
    }

    public static Set<String> targetDoorIds() {
        return TARGET_DOOR_IDS;
    }

    public static boolean isTargetDoorId(String id) {
        return id != null && TARGET_DOOR_IDS.contains(id);
    }

    public static boolean isTargetDoorId(ResourceLocation id) {
        return id != null && isTargetDoorId(id.toString());
    }

    public static boolean isTargetDoor(Block block) {
        if (!(block instanceof SlidingDoorBlock)) {
            return false;
        }
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        return isTargetDoorId(key);
    }

    public static boolean isTargetDoor(BlockState state) {
        return state != null && isTargetDoor(state.getBlock());
    }

    public static boolean isPaired(BlockState state) {
        return isTargetDoor(state) && state.hasProperty(PAIRED) && state.getValue(PAIRED);
    }

    public static boolean isSameDoorItem(BlockState state, ItemStack stack) {
        return isTargetDoor(state)
                && !stack.isEmpty()
                && stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() == state.getBlock();
    }

    public static BlockPos lowerPos(BlockPos pos, BlockState state) {
        return state.hasProperty(DoorBlock.HALF) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER
                ? pos.below()
                : pos;
    }

    public static BlockPos upperPos(BlockPos pos, BlockState state) {
        return lowerPos(pos, state).above();
    }

    public static boolean canPair(Level level, BlockPos clicked, ItemStack held) {
        BlockState clickedState = level.getBlockState(clicked);
        if (!isSameDoorItem(clickedState, held) || isPaired(clickedState)) {
            return false;
        }
        BlockPos lower = lowerPos(clicked, clickedState);
        BlockState lowerState = level.getBlockState(lower);
        BlockState upperState = level.getBlockState(lower.above());
        return lowerState.is(clickedState.getBlock())
                && lowerState.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                && !isPaired(lowerState)
                && upperState.is(clickedState.getBlock())
                && upperState.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER;
    }

    /**
     * Upgrades a placed target single door into a centered 2-wide single-cell paired door without
     * placing any adjacent block. Sets {@code PAIRED=true} and {@code VISIBLE=false} on both halves
     * and synchronizes the {@link SlidingDoorBlockEntity} animation value.
     */
    public static boolean pair(ServerLevel level, BlockPos clicked) {
        BlockState clickedState = level.getBlockState(clicked);
        if (!isTargetDoor(clickedState) || isPaired(clickedState)) {
            return false;
        }
        BlockPos lower = lowerPos(clicked, clickedState);
        BlockPos upper = lower.above();
        BlockState lowerState = level.getBlockState(lower);
        BlockState upperState = level.getBlockState(upper);
        if (!lowerState.is(clickedState.getBlock())
                || lowerState.getValue(DoorBlock.HALF) != DoubleBlockHalf.LOWER
                || isPaired(lowerState)
                || !upperState.is(clickedState.getBlock())
                || upperState.getValue(DoorBlock.HALF) != DoubleBlockHalf.UPPER) {
            return false;
        }

        BlockState pairedLower = lowerState
                .setValue(PAIRED, true)
                .setValue(SlidingDoorBlock.VISIBLE, false);
        BlockState pairedUpper = upperState
                .setValue(PAIRED, true)
                .setValue(SlidingDoorBlock.VISIBLE, false);

        level.setBlock(lower, pairedLower, Block.UPDATE_ALL);
        level.setBlock(upper, pairedUpper, Block.UPDATE_ALL);

        if (level.getBlockEntity(lower) instanceof SlidingDoorBlockEntity doorBe) {
            float targetAnim = pairedLower.getValue(DoorBlock.OPEN) ? 1.0f : 0.0f;
            if (doorBe instanceof SlidingDoorAnimationAccess animAccess) {
                animAccess.dimblend$setAnimationValue(targetAnim);
            }
            doorBe.setChanged();
        }
        return true;
    }

    public static VoxelShape closedPairedShape(Direction facing) {
        VoxelShape shape = CLOSED_PAIRED_SHAPES.get(facing);
        return shape != null ? shape : CLOSED_PAIRED_SHAPES.get(Direction.SOUTH);
    }

    public static VoxelShape openPairedShape(Direction facing, boolean folds) {
        Map<Direction, VoxelShape> map = folds ? OPEN_FOLDING_PAIRED_SHAPES : OPEN_SLIDING_PAIRED_SHAPES;
        VoxelShape shape = map.get(facing);
        return shape != null ? shape : map.get(Direction.SOUTH);
    }

    public static VoxelShape pairedShape(BlockState state) {
        Direction facing = state.getValue(DoorBlock.FACING);
        if (state.getValue(DoorBlock.OPEN)) {
            boolean folds = state.getBlock() instanceof SlidingDoorBlock door && door.isFoldingDoor();
            return openPairedShape(facing, folds);
        }
        return closedPairedShape(facing);
    }

    /**
     * Clamps a raycast hit location around {@code doorPos}'s center to {@code [-0.999, +0.999]}
     * on each axis so {@code ServerGamePacketListenerImpl#handleUseItemOn} accepts overhang clicks.
     */
    public static Vec3 clampHitLocationToBlockCenter(Vec3 hitLocation, BlockPos doorPos) {
        double cx = doorPos.getX() + 0.5;
        double cy = doorPos.getY() + 0.5;
        double cz = doorPos.getZ() + 0.5;
        return new Vec3(
                cx + Mth.clamp(hitLocation.x - cx, -MAX_HIT_CENTER_OFFSET, MAX_HIT_CENTER_OFFSET),
                cy + Mth.clamp(hitLocation.y - cy, -MAX_HIT_CENTER_OFFSET, MAX_HIT_CENTER_OFFSET),
                cz + Mth.clamp(hitLocation.z - cz, -MAX_HIT_CENTER_OFFSET, MAX_HIT_CENTER_OFFSET)
        );
    }

    private static Map<Direction, VoxelShape> buildClosedShapes() {
        Map<Direction, VoxelShape> map = new EnumMap<>(Direction.class);
        map.put(Direction.SOUTH, pixelBox(-8.0, 0.0, 0.0, 24.0, 16.0, 3.0));
        map.put(Direction.NORTH, pixelBox(-8.0, 0.0, 13.0, 24.0, 16.0, 16.0));
        map.put(Direction.EAST, pixelBox(0.0, 0.0, -8.0, 3.0, 16.0, 24.0));
        map.put(Direction.WEST, pixelBox(13.0, 0.0, -8.0, 16.0, 16.0, 24.0));
        return map;
    }

    private static Map<Direction, VoxelShape> buildOpenShapes(boolean folds) {
        Map<Direction, VoxelShape> map = new EnumMap<>(Direction.class);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Direction leftDir = facing.getCounterClockWise();
            Direction rightDir = facing.getClockWise();
            VoxelShape leftLeaf = singleOpenLeafShape(facing, false, folds)
                    .move(0.5 * leftDir.getStepX(), 0.0, 0.5 * leftDir.getStepZ());
            VoxelShape rightLeaf = singleOpenLeafShape(facing, true, folds)
                    .move(0.5 * rightDir.getStepX(), 0.0, 0.5 * rightDir.getStepZ());
            map.put(facing, Shapes.or(leftLeaf, rightLeaf));
        }
        return map;
    }

    /**
     * Exact single-door open leaf shapes matching {@code SlidingDoorShapes#get(facing, rightHinge, folds)},
     * built directly via {@link Shapes#box} so unit tests can evaluate shapes without bootstrapping registries.
     */
    private static VoxelShape singleOpenLeafShape(Direction facing, boolean rightHinge, boolean folds) {
        if (folds) {
            return switch (facing) {
                case SOUTH -> rightHinge
                        ? pixelBox(-3.0, 0.0, 0.0, 3.0, 16.0, 9.0)
                        : pixelBox(13.0, 0.0, 0.0, 19.0, 16.0, 9.0);
                case WEST -> rightHinge
                        ? pixelBox(7.0, 0.0, -3.0, 16.0, 16.0, 3.0)
                        : pixelBox(7.0, 0.0, 13.0, 16.0, 16.0, 19.0);
                case NORTH -> rightHinge
                        ? pixelBox(13.0, 0.0, 7.0, 19.0, 16.0, 16.0)
                        : pixelBox(-3.0, 0.0, 7.0, 3.0, 16.0, 16.0);
                default -> rightHinge
                        ? pixelBox(0.0, 0.0, 13.0, 9.0, 16.0, 19.0)
                        : pixelBox(0.0, 0.0, -3.0, 9.0, 16.0, 3.0);
            };
        }
        return switch (facing) {
            case SOUTH -> rightHinge
                    ? pixelBox(-13.0, 0.0, 0.0, 3.0, 16.0, 3.0)
                    : pixelBox(13.0, 0.0, 0.0, 29.0, 16.0, 3.0);
            case WEST -> rightHinge
                    ? pixelBox(13.0, 0.0, -13.0, 16.0, 16.0, 3.0)
                    : pixelBox(13.0, 0.0, 13.0, 16.0, 16.0, 29.0);
            case NORTH -> rightHinge
                    ? pixelBox(13.0, 0.0, 13.0, 29.0, 16.0, 16.0)
                    : pixelBox(-13.0, 0.0, 13.0, 3.0, 16.0, 16.0);
            default -> rightHinge
                    ? pixelBox(0.0, 0.0, 13.0, 3.0, 16.0, 29.0)
                    : pixelBox(0.0, 0.0, -13.0, 3.0, 16.0, 3.0);
        };
    }

    private static VoxelShape pixelBox(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        return Shapes.box(
                minX / 16.0, minY / 16.0, minZ / 16.0,
                maxX / 16.0, maxY / 16.0, maxZ / 16.0);
    }
}
