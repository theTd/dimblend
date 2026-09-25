package dimblend.mixin.voidscape;

import dimblend.compat.VoidscapeBand;
import java.util.Optional;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Voidscape keys infusion, fog, lightmap, spawn, and death on
 * {@code level.dimension() == voidscape:void}. The rotating voidscape lane is
 * still {@code dimblend:rotating}; treat that lane as the void so those checks
 * see the same world they would in Voidscape's own dimension.
 *
 * <p>The same lane identity would otherwise route Voidscape's portal funnel the
 * wrong way round: {@code getDimensionForTeleport} answers {@code minecraft:overworld}
 * for a level that counts as the void, which ejects a player standing in a rotating
 * band's portal into the real overworld. Rotating bans Voidscape portal travel
 * instead, see {@link #dimblend$noRotatingPortalTravel}.
 *
 * <p>Client light/fog without a position stack uses {@code LevelUtilClientMixin}.
 */
@Mixin(targets = "tamaized.voidscape.util.LevelUtil", remap = false)
public abstract class LevelUtilMixin {
    @Inject(method = "isInVoidDimension", at = @At("HEAD"), cancellable = true)
    private void dimblend$rotatingVoidscapeLane(@Nullable Level level, CallbackInfoReturnable<Boolean> cir) {
        if (VoidscapeBand.rotatingVoidscapeMatches(level)) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Voidscape's portal funnel. {@code PortalBlock.entityInside} only arms
     * {@code Insanity.inPortal} when this returns a level, and {@code Insanity.tick}
     * teleports to it once the 200-tick countdown is full. Empty for
     * {@code dimblend:rotating}: a void portal standing in a rotating band must not
     * move the player at all. Neither direction is wanted — out to the real overworld
     * (see the class doc) nor into the standalone {@code voidscape:void}, which is a
     * separate world the corridor cannot reach. The portal block, its particles and
     * its sound stay; the abyss band is reached by walking the corridor like every
     * other band. Voidscape's own dimensions keep their portal behaviour untouched.
     */
    @Inject(method = "getDimensionForTeleport", at = @At("HEAD"), cancellable = true)
    private void dimblend$noRotatingPortalTravel(Level currentLevel, CallbackInfoReturnable<Optional<ServerLevel>> cir) {
        if (VoidscapeBand.isRotating(currentLevel)) {
            cir.setReturnValue(Optional.empty());
        }
    }
}
