package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.weather.RotatingLevelData;
import dimblend.weather.RotatingWeatherData;
import dimblend.weather.ServerGlobalWeatherLock;
import java.util.List;
import java.util.concurrent.Executor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelWeatherMixin {
    // Replace the argument before super(), so Level.levelData and ServerLevel.serverLevelData
    // share the same writable weather and prepareWeather() sees the saved values.
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static ServerLevelData dimblend$ownWeather(
            ServerLevelData data, MinecraftServer server, Executor executor,
            LevelStorageSource.LevelStorageAccess storage, ServerLevelData original,
            ResourceKey<Level> dimension, LevelStem stem, ChunkProgressListener progress,
            boolean debug, long seed, List<CustomSpawner> spawners, boolean tickTime,
            RandomSequences sequences) {
        if (!DimBlendRegistries.ROTATING_LEVEL.equals(dimension)) {
            return data;
        }
        // The Overworld is already constructed here; rotating's own storage is not yet available.
        var weather = server.overworld().getDataStorage().computeIfAbsent(
                RotatingWeatherData.factory(data), "dimblend_rotating_weather");
        return new RotatingLevelData(server.getWorldData(), data, weather);
    }

    @Inject(method = "advanceWeatherCycle", at = @At("HEAD"), cancellable = true)
    private void dimblend$lockBeforeWeather(CallbackInfo ci) {
        if (ServerGlobalWeatherLock.enforceForLevel((ServerLevel) (Object) this)) {
            ci.cancel();
        }
    }
}
