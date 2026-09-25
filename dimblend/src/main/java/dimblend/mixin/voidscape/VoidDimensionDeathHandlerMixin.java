package dimblend.mixin.voidscape;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.compat.VoidscapeBand;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "tamaized.voidscape.event.VoidDimensionDeathHandler", remap = false)
public abstract class VoidDimensionDeathHandlerMixin {
    @WrapMethod(method = "handlePlayerDeath")
    private void dimblend$voidscapeLanePlayer(LivingDeathEvent event, Operation<Void> original) {
        VoidscapeBand.run(event.getEntity().level(), event.getEntity().blockPosition(), () -> original.call(event));
    }

    @WrapMethod(method = "handleMobDeath")
    private void dimblend$voidscapeLaneMob(LivingDeathEvent event, Operation<Void> original) {
        VoidscapeBand.run(event.getEntity().level(), event.getEntity().blockPosition(), () -> original.call(event));
    }
}
