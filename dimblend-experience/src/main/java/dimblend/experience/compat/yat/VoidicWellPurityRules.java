package dimblend.experience.compat.yat;

import dev.minhnh.yetanotherthirst.compat.FluidPurityHelper;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

/**
 * Voidscape's Voidic Well pumps pure water. Yet Another Thirst must see it as dirty
 * ({@code Purity: 0}), including water stored in the well's tank, pushed into pipes/tanks,
 * or poured into adjacent cauldrons.
 *
 * <p>Cauldron block-state purity is stored as {@code BLOCK_PURITY = purity + 1} (0 means
 * "unset, use the config default"). Only cauldrons that were empty or partially filled before
 * the well tick and are full afterwards are marked, so an untouched clean cauldron next to the
 * well is never overwritten.</p>
 */
public final class VoidicWellPurityRules {

    public static final int DIRTY_PURITY = WaterPurity.MIN_PURITY;
    public static final int DIRTY_CAULDRON_BLOCK_PURITY = WaterPurity.MIN_PURITY + 1;
    public static final int FULL_CAULDRON_LEVEL = 3;

    private static final ThreadLocal<Integer> PRE_TICK_CAULDRON_MASK = new ThreadLocal<>();

    public static FluidStack markDirtyWater(FluidStack stack) {
        if (!stack.isEmpty() && stack.is(Fluids.WATER)) {
            FluidPurityHelper.addPurity(stack, WaterPurity.MIN_PURITY);
        }
        return stack;
    }

    public static void capturePreTickCauldrons(Level level, BlockPos wellPos) {
        if (!(level instanceof ServerLevel) || level.hasNeighborSignal(wellPos)) {
            PRE_TICK_CAULDRON_MASK.remove();
            return;
        }
        int mask = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = wellPos.relative(direction);
            BlockState neighbourState = level.getBlockState(neighbour);
            if (neighbourState.is(Blocks.CAULDRON)
                    || (neighbourState.is(Blocks.WATER_CAULDRON)
                    && neighbourState.getValue(LayeredCauldronBlock.LEVEL) < FULL_CAULDRON_LEVEL)) {
                mask |= 1 << direction.get3DDataValue();
            }
        }
        PRE_TICK_CAULDRON_MASK.set(mask);
    }

    public static void finishPostTick(Level level, BlockPos wellPos) {
        Integer mask = PRE_TICK_CAULDRON_MASK.get();
        PRE_TICK_CAULDRON_MASK.remove();
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.hasNeighborSignal(wellPos)) {
            return;
        }
        IFluidHandler handler = serverLevel.getCapability(Capabilities.FluidHandler.BLOCK, wellPos, null);
        if (handler instanceof FluidTank tank && !tank.getFluid().isEmpty()) {
            markDirtyWater(tank.getFluid());
        }
        if (mask != null && mask != 0) {
            markFilledCauldrons(serverLevel, wellPos, mask);
        }
    }

    public static void markFilledCauldrons(Level level, BlockPos wellPos, int cauldronMask) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (Direction direction : Direction.values()) {
            if ((cauldronMask & (1 << direction.get3DDataValue())) == 0) {
                continue;
            }
            BlockPos cauldronPos = wellPos.relative(direction);
            BlockState cauldronState = serverLevel.getBlockState(cauldronPos);
            if (!cauldronState.is(Blocks.WATER_CAULDRON)
                    || !cauldronState.hasProperty(WaterPurity.BLOCK_PURITY)) {
                continue;
            }
            if (cauldronState.getValue(LayeredCauldronBlock.LEVEL) < FULL_CAULDRON_LEVEL) {
                continue;
            }
            if (cauldronState.getValue(WaterPurity.BLOCK_PURITY) != 0) {
                continue;
            }
            serverLevel.setBlockAndUpdate(cauldronPos, cauldronState.setValue(
                    WaterPurity.BLOCK_PURITY, DIRTY_CAULDRON_BLOCK_PURITY));
        }
    }

    private VoidicWellPurityRules() {
    }
}
