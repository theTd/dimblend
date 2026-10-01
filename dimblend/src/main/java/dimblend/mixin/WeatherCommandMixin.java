package dimblend.mixin;

import dimblend.DimBlendRegistries;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.commands.WeatherCommand;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(WeatherCommand.class)
public abstract class WeatherCommandMixin {
    @Redirect(method = {"setClear", "setRain", "setThunder"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;overworld()Lnet/minecraft/server/level/ServerLevel;"))
    private static ServerLevel dimblend$weatherTarget(MinecraftServer server, CommandSourceStack source, int duration) {
        ServerLevel level = source.getLevel();
        return DimBlendRegistries.ROTATING_LEVEL.equals(level.dimension()) ? level : server.overworld();
    }
}
