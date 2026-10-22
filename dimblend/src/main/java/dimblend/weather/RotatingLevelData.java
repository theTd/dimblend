package dimblend.weather;

import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.level.storage.WorldData;

/** Keeps vanilla derived rules/spawn behavior: weather is local and writable, and the
 *  shared day clock becomes writable too — vanilla {@link DerivedLevelData#setDayTime} is a
 *  no-op, which silently killed sleeping-through-the-night and /time in rotating. Writes go
 *  straight to the overworld primary data, so {@code wrapped} must be that primary instance
 *  (not another DerivedLevelData, whose own setter is equally dead). rotating never advances
 *  the clock on its own: MinecraftServer.createLevels constructs non-overworld levels with
 *  tickTime=false, so ServerLevel.tickTime() never runs there — the shared clock moves only
 *  via the overworld's own tick. The one remaining double-write path, /time add's per-level
 *  relative write, is skipped for rotating in TimeCommandMixin. */
public final class RotatingLevelData extends DerivedLevelData {
    private final ServerLevelData wrappedPrimary;
    private final RotatingWeatherData weather;

    public RotatingLevelData(WorldData worldData, ServerLevelData wrapped, RotatingWeatherData weather) {
        super(worldData, wrapped);
        this.wrappedPrimary = wrapped;
        this.weather = weather;
    }

    @Override
    public void setDayTime(long time) {
        wrappedPrimary.setDayTime(time);
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
