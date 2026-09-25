package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.weather.ServerBandWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Snow accumulation and {@code handlePrecipitation} consult dimension-wide
 * {@code isRaining()}, not {@code isRainingAt}. Attribute the tick to the
 * column so locked lanes skip rain effects while ice freeze (temperature)
 * still runs.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPrecipitationMixin {
    @WrapMethod(method = "tickPrecipitation")
    private void dimblend$bandWeatherPrecipitation(BlockPos pos, Operation<Void> original) {
        ServerLevel self = (ServerLevel) (Object) this;
        ServerBandWeather.run(self, pos, () -> original.call(pos));
    }
}
