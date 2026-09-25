package dimblend.mixin.twilight;

import dimblend.worldgen.YShiftScope;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.world.components.structures.stronghold.StrongholdUpperAscenderComponent;

@Mixin(value = StrongholdUpperAscenderComponent.class, remap = false)
public abstract class StrongholdUpperAscenderMixin {
    @Shadow
    boolean exitTop;

    @Inject(method = "generateBoundingBox", at = @At("HEAD"), cancellable = true)
    private void dimblend$shiftedSeaThreshold(
            Direction facing,
            int x,
            int y,
            int z,
            CallbackInfoReturnable<BoundingBox> cir
    ) {
        int offset = YShiftScope.current();
        if (offset == 0) {
            return;
        }
        if (y < 5 + offset) {
            this.exitTop = true;
            cir.setReturnValue(BoundingBox.orientBox(x, y, z, -2, -1, 0, 5, 10, 10, facing));
        } else {
            this.exitTop = false;
            cir.setReturnValue(BoundingBox.orientBox(x, y, z, -2, -6, 0, 5, 10, 10, facing));
        }
    }
}
