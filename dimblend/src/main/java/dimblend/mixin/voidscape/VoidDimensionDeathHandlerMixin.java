package dimblend.mixin.voidscape;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.compat.VoidscapeBand;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Voidscape rescues players dying in the void: 10% health, cleansed, teleported
 * to the respawn dimension, death cancelled. Rotating opts out — a void fall in
 * any band, voidscape lane included, kills the player exactly like the vanilla
 * dimensions, so {@code handlePlayerDeath} is skipped for the whole dimension.
 * Mobs keep the lane wrap: {@code handleMobDeath} never cancels death, it only
 * adds Ethereal Essence drops on the voidscape lane.
 */
@Mixin(targets = "tamaized.voidscape.event.VoidDimensionDeathHandler", remap = false)
public abstract class VoidDimensionDeathHandlerMixin {
    @WrapMethod(method = "handlePlayerDeath")
    private void dimblend$vanillaPlayerDeathInRotating(LivingDeathEvent event, Operation<Void> original) {
        if (VoidscapeBand.isRotating(event.getEntity().level())) {
            return;
        }
        original.call(event);
    }

    @WrapMethod(method = "handleMobDeath")
    private void dimblend$voidscapeLaneMob(LivingDeathEvent event, Operation<Void> original) {
        VoidscapeBand.run(event.getEntity().level(), event.getEntity().blockPosition(), () -> original.call(event));
    }
}
