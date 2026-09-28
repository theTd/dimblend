package dimblend.weather;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.ServerLevelData;

/** Weather owned by rotating, persisted separately from the Overworld's weather. */
public final class RotatingWeatherData extends SavedData {
    int clearTime;
    int rainTime;
    int thunderTime;
    boolean raining;
    boolean thundering;

    public static Factory<RotatingWeatherData> factory(ServerLevelData initial) {
        return new Factory<>(() -> copyOf(initial), (tag, registries) -> load(tag), null);
    }

    static RotatingWeatherData copyOf(ServerLevelData initial) {
        RotatingWeatherData data = new RotatingWeatherData();
        data.clearTime = initial.getClearWeatherTime();
        data.rainTime = initial.getRainTime();
        data.thunderTime = initial.getThunderTime();
        data.raining = initial.isRaining();
        data.thundering = initial.isThundering();
        data.setDirty();
        return data;
    }

    static RotatingWeatherData load(CompoundTag tag) {
        RotatingWeatherData data = new RotatingWeatherData();
        data.clearTime = tag.getInt("clearTime");
        data.rainTime = tag.getInt("rainTime");
        data.thunderTime = tag.getInt("thunderTime");
        data.raining = tag.getBoolean("raining");
        data.thundering = tag.getBoolean("thundering");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("clearTime", clearTime);
        tag.putInt("rainTime", rainTime);
        tag.putInt("thunderTime", thunderTime);
        tag.putBoolean("raining", raining);
        tag.putBoolean("thundering", thundering);
        return tag;
    }
}
