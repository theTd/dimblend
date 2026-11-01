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
        // A save written before the switch was removed still carries a "switches" key.
        // Nothing reads it any more and nothing writes it back, so it simply disappears
        // on the next save; ignoring it here keeps old worlds loadable.
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
        tag.putLongArray("curves", curvesAttached.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("curvesWarned", curvesWarned.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    /**
     * Blueprint of the car parked on a boundary's positive siding.
     *
     * <p><b>Why POWER and not CARRIAGE.</b> A user reported on 2026-11-01 that the spawn
     * chassis drove as soon as a motor was fitted while the siding one never moved. The
     * difference was the blueprint: {@link ChassisBlueprint#CARRIAGE} tunes every bogey to
     * stress 0, and SimuRail multiplies the drive force by that stress, so a carriage can
     * never propel itself no matter what is bolted to it. Asking for both cars to be the
     * same car — with the spawn one as the reference — makes the siding car drivable.
     */
    static ChassisBlueprint sidingBlueprint() {
        return ChassisBlueprint.POWER;
    }

    /** Blueprint of the one power chassis every new world gets. */
    static ChassisBlueprint powerBlueprint() {
        return ChassisBlueprint.POWER;
    }

    /** Centre of the car on the positive siding of this boundary. */
    public static BlockPos sidingCenter(int boundaryX) {
        return new BlockPos(StationDepotSites.carriageChassisX(boundaryX),
                StationDepotSites.CHASSIS_Y, StationDepotSites.carriageChassisZ());
    }

    /** Centre of the one power chassis every new world gets. */
    public static BlockPos powerCenter() {
        return new BlockPos(StationDepotSites.POWER_CHASSIS_X, StationDepotSites.CHASSIS_Y,
                StationDepotSites.POWER_CHASSIS_Z);
    }

    /**
     * Whether the depot places its own track switch. It does not, and this is a
     * decision, not a gap.
     *
     * <p><b>Why there is no switch.</b> A user reported on 2026-11-01 that "a
     * direction-changing thing stood at the junction of the three tracks and blocked the
     * car". Railways' andesite switch is a solid block: {@code CRShapes.ANDESITE_SWITCH}
     * fills the whole 16x16 footprint at 0..5px and adds a post up to 15px, and
     * {@code TrackSwitchBlock#getCollisionShape} returns that shape whenever
     * {@code hasCollision} is set — which it is, because the block registers with
     * {@code noOcclusion()} but never {@code noCollission()}. The depot's two merge
     * columns are one block apart ({@code POSITIVE_MERGE_DX} 43, {@code NEGATIVE_MERGE_DX}
     * 44), so one switch could never have covered both forks either. The main line stays
     * plain track and clear.
     */
    static boolean fitsSwitch() {
        return false;
    }

    /** True when this site is the one that builds the depot's junction-adjacent car. */
    static boolean carriesJunction(BlockPos center) {
        return center.getZ() == StationDepotSites.carriageChassisZ();
    }
}