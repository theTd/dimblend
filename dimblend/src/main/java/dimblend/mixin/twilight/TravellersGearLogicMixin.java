package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import twilightforest.init.TFDimensionData;
import twilightforest.item.travellers_gear.TravellersGearLogic;

@Mixin(value = TravellersGearLogic.class, remap = false)
public abstract class TravellersGearLogicMixin {
    @Redirect(
            method = "getAutoRepairChance",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/Holder;is(Lnet/minecraft/resources/ResourceKey;)Z",
                    remap = true
            )
    )
    private static boolean dimblend$twilightRepair(
            Holder<?> holder,
            ResourceKey<?> key,
            double baseProb,
            Level level,
            BlockPos pos
    ) {
        if (key == TFDimensionData.TWILIGHT_DIM_TYPE && TwilightBand.isTwilightPos(level, pos)) {
            return true;
        }
        @SuppressWarnings("unchecked")
        Holder<Object> typed = (Holder<Object>) holder;
        @SuppressWarnings("unchecked")
        ResourceKey<Object> typedKey = (ResourceKey<Object>) key;
        return typed.is(typedKey);
    }
}
