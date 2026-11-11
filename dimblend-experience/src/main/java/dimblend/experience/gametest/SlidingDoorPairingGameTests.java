package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import dimblend.experience.compat.create.SlidingDoorPairing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Focused state tests for the optional Create door pairing logic. */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class SlidingDoorPairingGameTests {
    private SlidingDoorPairingGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 40)
    public static void pairsUpperClickIntoOppositeHinge(GameTestHelper helper) {
        BlockPos lower = new BlockPos(2, 1, 2);
        BlockState source = AllBlocks.ANDESITE_DOOR.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.RIGHT)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(lower, source);
        helper.setBlock(lower.above(), source.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));

        boolean paired = SlidingDoorPairing.pair(helper.getLevel(), lower.above(),
                new ItemStack(AllBlocks.ANDESITE_DOOR.asItem()));
        helper.assertTrue(paired, "upper-half click must pair");

        BlockPos other = SlidingDoorPairing.partnerLowerPos(lower, source);
        BlockState otherState = helper.getBlockState(other);
        helper.assertTrue(otherState.is(AllBlocks.ANDESITE_DOOR.get()), "partner lower must be Create door");
        helper.assertTrue(otherState.getValue(BlockStateProperties.DOOR_HINGE) == DoorHingeSide.LEFT,
                "partner hinge must be opposite");
        helper.assertTrue(helper.getBlockState(other.above()).getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                == DoubleBlockHalf.UPPER, "partner upper must be present");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "dimblend_chassis", timeoutTicks = 40)
    public static void occupiedPartnerDoesNotPair(GameTestHelper helper) {
        BlockPos lower = new BlockPos(2, 1, 2);
        BlockState source = AllBlocks.BRASS_DOOR.getDefaultState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        helper.setBlock(lower, source);
        helper.setBlock(lower.above(), source.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        BlockPos other = SlidingDoorPairing.partnerLowerPos(lower, source);
        helper.setBlock(other, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());

        helper.assertFalse(SlidingDoorPairing.canPair(helper.getLevel(), lower,
                new ItemStack(AllBlocks.BRASS_DOOR.asItem())), "occupied partner must reject pairing");
        helper.succeed();
    }
}
