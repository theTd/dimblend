package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.createmod.catnip.data.Pair;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;
import dev.minhnh.yetanotherthirst.compat.FluidPurityHelper;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Create's built-in potion emptying runs before recipes and otherwise discards YAT purity. */
@Mixin(ItemDrainBlockEntity.class)
public abstract class WaterBottleDrainPurityMixin {
    @WrapOperation(method = "continueProcessing()Z",
            at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/fluids/transfer/GenericItemEmptying;emptyItem(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Z)Lnet/createmod/catnip/data/Pair;"),
            require = 2, allow = 2)
    private Pair<FluidStack, ItemStack> dimblend$keepBottlePurity(Level level, ItemStack stack, boolean simulate,
            Operation<Pair<FluidStack, ItemStack>> original) {
        boolean waterBottle = WaterPurity.isWaterBottle(stack);
        int purity = waterBottle ? WaterPurity.getPurity(stack) : 0;
        var result = original.call(level, stack, simulate);
        if (waterBottle && result.getFirst().is(Fluids.WATER)) {
            FluidPurityHelper.addPurity(result.getFirst(), purity);
        }
        return result;
    }
}
