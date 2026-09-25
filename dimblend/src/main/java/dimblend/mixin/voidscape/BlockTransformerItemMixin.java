package dimblend.mixin.voidscape;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.compat.VoidscapeBand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "tamaized.voidscape.item.BlockTransformerItem", remap = false)
public abstract class BlockTransformerItemMixin {
    @WrapMethod(method = "useOn")
    private InteractionResult dimblend$voidscapeLane(UseOnContext context, Operation<InteractionResult> original) {
        return VoidscapeBand.call(context.getLevel(), context.getClickedPos(), () -> original.call(context));
    }
}
