package dimblend.mixin.twilight;

import dimblend.worldgen.YShiftScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import twilightforest.world.components.structures.finalcastle.FinalCastleEntranceTowerComponent;

@Mixin(value = FinalCastleEntranceTowerComponent.class, remap = false)
public abstract class FinalCastleEntranceTowerMixin {
    @ModifyConstant(method = "addChildren", constant = @Constant(intValue = 127))
    private int dimblend$shiftedPlateau(int original) {
        return original + YShiftScope.current();
    }
}
