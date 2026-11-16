package dimblend.experience.mixin.compat.starlight;

import dimblend.experience.compat.starlight.CrystalbornCatalystRules;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bans placing the crystalborn catalyst on a Sable sub-level in every game mode, including
 * deployers and other fake players. Sable is resolved only behind a mod-loaded check, so
 * {@code BlockItem#place} never loads Sable classes when the mod is absent.
 */
@Mixin(BlockItem.class)
public abstract class CrystalbornCatalystPlacementMixin {

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$banSablePlacement(BlockPlaceContext context,
            CallbackInfoReturnable<InteractionResult> cir) {
        if (CrystalbornCatalystRules.shouldBlockPlacement((BlockItem) (Object) this, context)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
