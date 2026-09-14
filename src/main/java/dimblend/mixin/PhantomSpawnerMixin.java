package dimblend.mixin;

import dimblend.compat.TwilightBand;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PhantomSpawner.class)
public abstract class PhantomSpawnerMixin {
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;players()Ljava/util/List;"
            )
    )
    private List<ServerPlayer> dimblend$skipTwilightPlayers(ServerLevel level) {
        List<ServerPlayer> players = level.players();
        if (!TwilightBand.isRotating(level)) {
            return players;
        }
        return players.stream()
                .filter(player -> !TwilightBand.isTwilightColumn(level, player.getBlockX()))
                .toList();
    }
}
