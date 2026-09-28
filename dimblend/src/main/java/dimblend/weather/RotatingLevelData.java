package dimblend.weather;

import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/** Keeps vanilla derived time/rules/spawn behavior, but makes weather local and writable. */
public final class RotatingLevelData extends DerivedLevelData {
    private final RotatingWeatherData weather;

    public RotatingLevelData(WorldData worldData, ServerLevelData wrapped, RotatingWeatherData weather) {
        super(worldData, wrapped);
        this.weather = weather;
    }

    @Override
    public int getClearWeatherTime() { return weather.clearTime; }

    @Override
    public void setClearWeatherTime(int time) {
        if (weather.clearTime != time) {
            weather.clearTime = time;
            weather.setDirty();
        }
    }

    @Override
    public int getRainTime() { return weather.rainTime; }

    @Override
    public void setRainTime(int time) {
        if (weather.rainTime != time) {
            weather.rainTime = time;
            weather.setDirty();
        }
    }

    @Override
    public int getThunderTime() { return weather.thunderTime; }

    @Override
    public void setThunderTime(int time) {
        if (weather.thunderTime != time) {
            weather.thunderTime = time;
            weather.setDirty();
        }
    }

    @Override
    public boolean isRaining() { return weather.raining; }

    @Override
    public void setRaining(boolean raining) {
        if (weather.raining != raining) {
            weather.raining = raining;
            weather.setDirty();
        }
    }

    @Override
    public boolean isThundering() { return weather.thundering; }

    @Override
    public void setThundering(boolean thundering) {
        if (weather.thundering != thundering) {
            weather.thundering = thundering;
            weather.setDirty();
        }
    }
}
