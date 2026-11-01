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
    /** Switch machines already placed and bound to the track graph. */
    private final java.util.Set<Long> switchesFitted = new java.util.HashSet<>();
    /** Switch machines already reported once, so a slow track graph does not log per tick. */
    private final java.util.Set<Long> switchesReported = new java.util.HashSet<>();

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

    boolean switchFitted(BlockPos machine) {
        return switchesFitted.contains(machine.asLong());
    }

    void markSwitchFitted(BlockPos machine) {
        if (switchesFitted.add(machine.asLong())) {
            setDirty();
        }
    }

    /** True the first time this switch machine is reported; false on every later visit. */
    boolean firstSwitchReport(BlockPos machine) {
        if (switchesReported.add(machine.asLong())) {
            setDirty();
            return true;
        }
        return false;
    }

    /**
     * Every switch machine this depot has ever placed, in either state. The list survives a
     * layout change on purpose: a world that already carries a machine somewhere the depot
     * no longer wants it needs to find that block again to take it away, and the position
     * alone cannot tell an abandoned machine from a player's own.
     */
    java.util.Set<BlockPos> switchMachines() {
        var machines = new java.util.HashSet<BlockPos>();
        for (long packed : switchesFitted) {
            machines.add(BlockPos.of(packed));
        }
        for (long packed : switchesReported) {
            machines.add(BlockPos.of(packed));
        }
        return machines;
    }

    /** Forgets a machine the depot no longer wants, so it is never reported again. */
    void forgetSwitch(BlockPos machine) {
        boolean removed = switchesFitted.remove(machine.asLong());
        removed |= switchesReported.remove(machine.asLong());
        if (removed) {
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
        // Saves written while the depot placed no switch still carry a "switches" key, and
        // the switch machines of an older layout sat elsewhere. Nothing reads it: the
        // current machines are keyed by their own positions, so old entries simply
        // disappear on the next save and old worlds stay loadable.
        for (long curve : tag.getLongArray("curves")) {
            data.curvesAttached.add(curve);
        }
        for (long warned : tag.getLongArray("curvesWarned")) {
            data.curvesWarned.add(warned);
        }
        for (long machine : tag.getLongArray("switchMachines")) {
            data.switchesFitted.add(machine);
        }
        for (long reported : tag.getLongArray("switchMachinesReported")) {
            data.switchesReported.add(reported);
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
        tag.putLongArray("switchMachines", switchesFitted.stream().mapToLong(Long::longValue).toArray());
        tag.putLongArray("switchMachinesReported",
                switchesReported.stream().mapToLong(Long::longValue).toArray());
        return tag;
    }

    /**
     * Blueprint of the car parked on a boundary's positive siding.
     *
     * <p><b>Why CARRIAGE.</b> The depot car originally used {@link ChassisBlueprint#CARRIAGE}
     * and was switched to {@code POWER} on 2026-11-02 because a carriage tunes every bogey to
     * stress 0 and so could never move. A user then asked for the carriage shell back at both
     * sites, so the choice moved out of the blueprint: the shell is CARRIAGE, and
     * {@link ChassisBlocks#configureBogey} is told to drive the end bogeys anyway
     * ({@code driveEnds}). The printed carriage keeps stress 0 — only these two world-built
     * cars pull.
     */
    static ChassisBlueprint sidingBlueprint() {
        return ChassisBlueprint.CARRIAGE;
    }

    /** Blueprint of the one power chassis every new world gets. */
    static ChassisBlueprint powerBlueprint() {
        return ChassisBlueprint.CARRIAGE;
    }

    /**
     * Whether the cars this class builds drive their own end bogeys. They do: the player
     * gets both cars as something to drive away, not as a shell to be towed.
     */
    static boolean drivesEndBogeys() {
        return true;
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
     * Whether the depot places its own andesite track switch. It does, and where the
     * machine stands is the decision.
     *
     * <p><b>The history.</b> The switch first stood one block above the junction column; a
     * user reported on 2026-11-01 that it stopped the car, and it was removed. The user
     * then said on 2026-11-02 that removing it was the wrong reading — the switch is
     * wanted, only its lever must stay out of the vehicles' way. {@link StationDepotSwitch}
     * holds the rest: the machine stands {@code SAFE_Z+1} blocks to the side of the main
     * line and works the carriage siding's merge, the junction the user asked for.
     */
    static boolean fitsSwitch() {
        return true;
    }

    /** True when this site is the one that builds the depot's junction-adjacent car. */
    static boolean carriesJunction(BlockPos center) {
        return center.getZ() == StationDepotSites.carriageChassisZ();
    }
}