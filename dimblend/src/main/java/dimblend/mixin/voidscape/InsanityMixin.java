package dimblend.mixin.voidscape;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.compat.VoidscapeBand;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "tamaized.voidscape.data.Insanity", remap = false)
public abstract class InsanityMixin {
    @WrapMethod(method = "tick")
    private void dimblend$voidscapeLane(Entity parent, Operation<Void> original) {
        VoidscapeBand.run(parent.level(), parent.blockPosition(), () -> original.call(parent));
    }

    /**
     * Voidscape's bedrock entry: standing on bedrock within 15 blocks of the world
     * floor rolls {@code shouldTeleport}, fills {@code teleportTick} to 200 and then
     * drops the entity into the standalone {@code voidscape:void}. The roll keys on
     * the level's own world floor ({@code minBuildHeight + 15}), and
     * {@code dimblend:rotating} spans Y-64..319. The surface lane no longer cuts
     * depth: stone/deepslate below Y32 is bedrock, so standable bedrock reaches
     * down to the world floor and crosses its Y-49 threshold; the abyss lane's
     * bedrock islands do reach Y-64 but never reach the roll because that lane
     * already counts as the void. A band whose standable bedrock
     * does reach Y-49 would eject a player out of the rotating world, so refuse the
     * roll for every rotating band. No countdown can start: a {@code teleporting} state
     * carried in from another dimension is cancelled on the first tick
     * ({@code teleportTick} is persisted in NBT, so it drains rather than climbs), and
     * the only path that can raise it to the 200 that {@code Insanity.tick}'s
     * {@code getVoidDimension} branch needs is the portal branch, whose destination
     * {@link dimblend.mixin.voidscape.LevelUtilMixin} empties for rotating.
     */
    @Inject(method = "canTeleport", at = @At("HEAD"), cancellable = true)
    private void dimblend$noRotatingBedrockTravel(Entity parent, CallbackInfoReturnable<Boolean> cir) {
        if (VoidscapeBand.isRotating(parent.level())) {
            cir.setReturnValue(false);
        }
    }
}
