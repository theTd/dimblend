package dimblend.weather;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;
import org.junit.jupiter.api.Test;

class RotatingLevelDataTest {
    @Test
    void clearsDerivedWeatherWithoutWritingToOverworld() {
        ServerLevelData overworld = rainingOverworld();
        ServerLevelData derived = new DerivedLevelData(null, overworld);
        // Reproduce the original bug: vanilla custom-dimension setters silently do nothing.
        derived.setRaining(false);
        assertTrue(derived.isRaining());

        RotatingWeatherData weather = RotatingWeatherData.copyOf(derived);
        ServerLevelData rotating = new RotatingLevelData(null, derived, weather);
        weather.setDirty(false);
        setWeather(rotating, 6000, 0, false, false);

        assertWeather(rotating, 6000, 0, 0, false, false);
        assertTrue(weather.isDirty());
        assertWeather(overworld, 0, 12000, 18000, true, true);
        assertEquals(12345L, rotating.getDayTime());
        rotating.setDayTime(42L);
        assertEquals(12345L, rotating.getDayTime(), "Non-weather derived behavior must stay intact");
    }

    @Test
    void weatherRemainsWritableAfterSaveAndReload() {
        ServerLevelData derived = new DerivedLevelData(null, rainingOverworld());
        RotatingWeatherData weather = RotatingWeatherData.copyOf(derived);
        ServerLevelData rotating = new RotatingLevelData(null, derived, weather);
        setWeather(rotating, 6000, 0, false, false);

        RotatingWeatherData loaded = RotatingWeatherData.load(weather.save(new CompoundTag(), null));
        ServerLevelData reloaded = new RotatingLevelData(null, derived, loaded);
        assertWeather(reloaded, 6000, 0, 0, false, false);
        assertFalse(loaded.isDirty());

        // Vanilla's surface weather cycle can update each timer and flag independently.
        reloaded.setClearWeatherTime(0);
        reloaded.setRainTime(700);
        reloaded.setThunderTime(900);
        reloaded.setRaining(true);
        reloaded.setThundering(true);
        assertTrue(loaded.isDirty());
        RotatingWeatherData rainy = RotatingWeatherData.load(loaded.save(new CompoundTag(), null));
        assertWeather(new RotatingLevelData(null, derived, rainy), 0, 700, 900, true, true);
    }

    @Test
    void repeatedClearDoesNotDirtyUnchangedSavedWeather() {
        ServerLevelData derived = new DerivedLevelData(null, rainingOverworld());
        RotatingWeatherData weather = RotatingWeatherData.copyOf(derived);
        ServerLevelData rotating = new RotatingLevelData(null, derived, weather);
        setWeather(rotating, 6000, 0, false, false);
        weather.setDirty(false);
        setWeather(rotating, 6000, 0, false, false);
        assertFalse(weather.isDirty());
    }

    private static ServerLevelData rainingOverworld() {
        return (ServerLevelData) Proxy.newProxyInstance(ServerLevelData.class.getClassLoader(),
                new Class<?>[]{ServerLevelData.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getClearWeatherTime" -> 0;
                    case "getRainTime" -> 12000;
                    case "getThunderTime" -> 18000;
                    case "isRaining", "isThundering" -> true;
                    case "getDayTime" -> 12345L;
                    default -> throw new AssertionError("Unexpected access to Overworld: " + method.getName());
                });
    }

    private static void setWeather(ServerLevelData data, int clearTime, int time, boolean rain, boolean thunder) {
        data.setClearWeatherTime(clearTime);
        data.setRainTime(time);
        data.setThunderTime(time);
        data.setRaining(rain);
        data.setThundering(thunder);
    }

    private static void assertWeather(ServerLevelData data, int clearTime, int rainTime, int thunderTime,
                                      boolean raining, boolean thundering) {
        assertEquals(clearTime, data.getClearWeatherTime());
        assertEquals(rainTime, data.getRainTime());
        assertEquals(thunderTime, data.getThunderTime());
        assertEquals(raining, data.isRaining());
        assertEquals(thundering, data.isThundering());
    }
}
