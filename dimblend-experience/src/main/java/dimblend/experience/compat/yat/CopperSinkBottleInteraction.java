package dimblend.experience.compat.yat;

import com.adonis.fluid.block.CopperSink.CopperSinkBlockEntity;
import dev.minhnh.yetanotherthirst.compat.FluidPurityHelper;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/** The sink uses Create's generic hand exchange, which bypasses filling recipes. */
public final class CopperSinkBottleInteraction {
    public static ItemInteractionResult use(ItemStack held, CopperSinkBlockEntity sink, Player player,
            InteractionHand hand, Level level) {
        boolean emptying = WaterPurity.isWaterBottle(held);
        if (!emptying && !held.is(Items.GLASS_BOTTLE)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        var tank = sink.getTank();
        ItemStack result;
        if (emptying) {
            var water = FluidPurityHelper.addPurity(new FluidStack(Fluids.WATER, 250), WaterPurity.getPurity(held));
            if (tank.fill(water, FluidAction.SIMULATE) != 250) {
                return ItemInteractionResult.SUCCESS;
            }
            if (!level.isClientSide) {
                tank.fill(water, FluidAction.EXECUTE);
            }
            result = new ItemStack(Items.GLASS_BOTTLE);
        } else {
            var water = tank.drain(250, FluidAction.SIMULATE);
            if (!water.is(Fluids.WATER) || water.getAmount() != 250) {
                return ItemInteractionResult.SUCCESS;
            }
            result = WaterPurity.addPurity(WaterPurity.waterPotion(),
                    FluidPurityHelper.hasPurity(water) ? FluidPurityHelper.getPurity(water) : 0);
            if (!level.isClientSide) {
                tank.drain(water, FluidAction.EXECUTE);
            }
        }
        // Match Create's hand exchange: creative exchanges fluid but keeps its held item.
        if (!level.isClientSide && !player.isCreative()) {
            held.shrink(1);
            if (held.isEmpty()) {
                player.setItemInHand(hand, result);
            } else {
                player.getInventory().placeItemBackInInventory(result);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    private CopperSinkBottleInteraction() { }
}
