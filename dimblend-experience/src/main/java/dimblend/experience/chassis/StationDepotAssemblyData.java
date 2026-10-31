package dimblend.experience.chassis;

import dimblend.api.StationDepotSites;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Which depot chassis sites exist, which have their blocks placed, and which have
 * become Sable sub-levels.
 *
 * <p>Persisted, because the alternative is assembling a second car on top of the
 * first one every time the server restarts. A site is identified by its centre, so a
 * save written before this feature simply starts with an empty list and gets its cars
 * on the next visit. The block map is deliberately not persisted: it is re-read from
 * the world when needed, and a block nobody wrote must never be remembered as ours.
 */
public final class StationDepotAssemblyData extends SavedData {
    private static final Factory<StationDepotAssemblyData> FACTORY =
            new Factory<>(StationDepotAssemblyData::new,
                    (tag, registries) -> load(tag), null);

    /** One depot chassis site. */
    static final class Site {
        final BlockPos center;
        final ChassisBlueprint blueprint;
        boolean built;
        boolean assembled;
        int attempts;
        /** Blocks this pass placed for the site, cleared once the sub-level exists. */
        final Map<BlockPos, BlockState> placed = new LinkedHashMap<>();

        Site(BlockPos center, ChassisBlueprint blueprint) {
            this.center = center.immutable();
            this.blueprint = blueprint;
        }
    }

    final List<Site> sites = new ArrayList<>();
    /** Junction positions whose andesite switch has already been fitted. */
    private final java.util.Set<Long> switchFitted = new java.util.HashSet<>();
    /** Curve start positions whose BezierConnection has already been attached. */
    private final java.util.Set<Long> curvesAttached = new java.util.HashSet<>();
    /**
     * Curves already warned about, so a track graph that is slow to register does not
     * write a log line every tick. Persisted: a restart must not re-warn the same site.
     */
    private final java.util.Set<Long> curvesWarned = new java.util.HashSet<>();

    public static StationDepotAssemblyData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, "dimblend_station_depot_assembly");
    }

    boolean switchFitted(BlockPos junction) {
        return switchFitted.contains(junction.asLong());
    }

    void markSwitchFitted(BlockPos junction) {
        if (switchFitted.add(junction.asLong())) {
            setDirty();
        }
    }

    boolean curveAttached(BlockPos curveStart) {
        return curvesAttached.contains(curveStart.asLong());
    }

    void markCurveAttached(BlockPos curveStart) {
        if (curvesAttached.add(curveStart.asLong())) {
            setDirty();
        }
    }

    /**
     * Writes one WARN for this curve, the first time only. Frequency is limited per
     * curve, not per tick: the condition ("the graph has not registered the main line
     * yet") persists for as long as those chunks stay loaded, and repeating it every
     * tick would bury every other message in the log.
     */
    void warnOnceForCurve(BlockPos curveStart, org.slf4j.Logger logger, String format, Object... args) {
        if (curvesWarned.add(curveStart.asLong())) {
            logger.warn(format, args);
            setDirty();
        }
    }

    /** Returns the entry for this centre, creating it on first sight. */
    Site site(BlockPos center, ChassisBlueprint blueprint) {
        for (Site site : sites) {
            if (site.center.equals(center)) {
                return site;
            }
        }
        Site created = new Site(center, blueprint);
        sites.add(created);
        setDirty();
        return created;
    }

    static StationDepotAssemblyData load(CompoundTag tag) {
        StationDepotAssemblyData data = new StationDepotAssemblyData();
        ListTag list = tag.getList("sites", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ChassisBlueprint blueprint;
            try {
                blueprint = ChassisBlueprint.valueOf(entry.getString("kind"));
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            Site site = new Site(BlockPos.of(entry.getLong("center")), blueprint);
            site.built = entry.getBoolean("built");
            site.assembled = entry.getBoolean("assembled");
            site.attempts = entry.getInt("attempts");
            data.sites.add(site);
        }
        var switches = tag.getLongArray("switches");
        for (long junction : switches) {
            data.switchFitted.add(junction);
        }
        for (long curve : tag.getLongArray("curves")) {
            data.curvesAttached.add(curve);
        }
        for (long warned : tag.getLongArray("curvesWarned")) {
            data.curvesWarned.add(warned);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Site site : sites) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("center", site.center.asLong());
            entry.putString("kind", site.blueprint.name());
            entry.putBoolean("built", site.built);
            entry.putBoolean("assembled", site.assembled);
            entry.putInt("attempts", site.attempts);
            list.add(entry);
        }
        tag.put("sites", list);
        tag.putLongArray("switches", switchFitted.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("curves", curvesAttached.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("curvesWarned", curvesWarned.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    /** Centre of the carriage chassis on the positive siding of this boundary. */
    public static BlockPos carriageCenter(int boundaryX) {
        return new BlockPos(StationDepotSites.carriageChassisX(boundaryX),
                StationDepotSites.CHASSIS_Y, StationDepotSites.carriageChassisZ());
    }

    /** Centre of the one power chassis every new world gets. */
    public static BlockPos powerCenter() {
        return new BlockPos(StationDepotSites.POWER_CHASSIS_X, StationDepotSites.CHASSIS_Y,
                StationDepotSites.POWER_CHASSIS_Z);
    }
}