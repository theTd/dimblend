package dimblend.worldgen;

import com.mojang.logging.LogUtils;
import dimblend.DimBlendRegistries;
import dimblend.api.RegionOverrides;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Persistence and the live {@link RegionOverrides} state: which rotating regions another mod
 * forced to a lane, stored in the <b>overworld</b> data storage as {@value #DATA_NAME}.
 *
 * <p>Read path (worldgen worker threads) is lock-free: {@code overrideOf} reads one volatile
 * reference to an immutable {@link Table} and then a plain map lookup. The write path swaps in a
 * new table and marks the SavedData dirty. Already generated chunks are never rewritten, so a
 * write only affects chunks generated after it, which is exactly what requirement §4 asks for.
 *
 * <p><b>Load hook.</b> {@code RegionOverrides} must be populated before any rotating chunk can
 * generate, and the data storage only exists once the overworld {@code ServerLevel} is
 * constructed. In the NeoForge-patched server
 * ({@code neoforge-21.1.249-sources.jar}) the ordering is:
 * <ul>
 *   <li>{@code ServerAboutToStartEvent} — DedicatedServer.java:191 / IntegratedServer.java:76,
 *       fired <i>before</i> {@code loadLevel()}, i.e. before any level (and therefore the
 *       overworld data storage) exists; unusable.</li>
 *   <li>{@link LevelEvent.Load} for the overworld — MinecraftServer.java:375, fired inside
 *       {@code createLevels} right after the overworld level is constructed, <b>before</b> the
 *       remaining dimension levels are constructed (MinecraftServer.java:425) and before
 *       {@code prepareLevels} (MinecraftServer.java:493 in that patched source) — the first
 *       worldgen of the server.
 *       This is the earliest hook where the overworld data storage is readable and no chunk
 *       has generated yet, so it is the one installed in {@code DimBlend}.</li>
 *   <li>{@code ServerStartingEvent} — DedicatedServer.java:230 / IntegratedServer.java:81, fired
 *       <i>after</i> {@code loadLevel()}, i.e. after spawn-area and forced chunks generated:
 *       too late.</li>
 * </ul>
 * The static state is cleared on {@link ServerStoppedEvent} so a second world in the same
 * client session never inherits the previous world's overrides.
 *
 * <p>Requirement: dimblend-dragonchase/docs/requirements.md §3.3, §4.
 */
public final class RegionOverrideStore extends SavedData implements RegionOverrides.Impl {
    /** Data storage file name in the overworld. */
    public static final String DATA_NAME = "dimblend_region_overrides";

    /**
     * First region index that is a random draw rather than the fixed script, i.e. the only
     * regions an override may target ({@code BandLaneAssigner.FAR_RANDOM_START}). Scripted
     * regions keep their authored lane; negative regions are never forced (requirement §2.1).
     */
    public static final int FIRST_RANDOM_REGION = BandLaneAssigner.FAR_RANDOM_START;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int VERSION = 1;
    private static final String TAG_VERSION = "version";
    private static final String TAG_ENTRIES = "entries";
    private static final String TAG_REGION = "region";
    private static final String TAG_OVERRIDE = "override";

    /** The store of the running world; null before the overworld loaded or after shutdown. */
    private static volatile RegionOverrideStore active;

    /** Installed implementation: routes every call to the running world's store. */
    private static final RegionOverrides.Impl ROUTER = new RegionOverrides.Impl() {
        @Override
        public boolean forceLane(MinecraftServer server, int region, RegionOverrides.Override override) {
            if (server == null || override == null) {
                return false;
            }
            if (!server.isSameThread()) {
                // Contract: writes are server-thread only; a worldgen thread must never touch
                // the data storage (it would also race the save).
                LOGGER.warn("RegionOverrides.forceLane called off the server thread; ignored");
                return false;
            }
            if (!server.levelKeys().contains(DimBlendRegistries.ROTATING_LEVEL)) {
                return false;
            }
            return ensureLoaded(server).forceLane(server, region, override);
        }

        @Override
        public Optional<RegionOverrides.Override> overrideOf(int region) {
            RegionOverrideStore store = active;
            return store == null ? Optional.empty() : store.table.get(region);
        }
    };

    private volatile Table table;

    public RegionOverrideStore() {
        this(Table.EMPTY);
    }

    RegionOverrideStore(Table table) {
        this.table = table;
    }

    /** Installs the implementation. Called once from {@code DimBlend}'s constructor, before any server exists. */
    public static void install() {
        RegionOverrides.Impl.install(ROUTER);
    }

    /** {@link LevelEvent.Load} listener: the overworld load is the earliest readable data storage. */
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !level.dimension().equals(Level.OVERWORLD)) {
            return;
        }
        load(level.getServer());
    }

    /** {@link ServerStoppedEvent} listener: never let a later world see this one's overrides. */
    public static void onServerStopped(ServerStoppedEvent event) {
        active = null;
    }

    /**
     * Reads (or creates) the overworld store and publishes it for worldgen threads.
     * Server thread only; the callers are the level-load hook and {@link #forceLane}.
     */
    public static RegionOverrideStore load(MinecraftServer server) {
        RegionOverrideStore store = server.overworld().getDataStorage().computeIfAbsent(factory(), DATA_NAME);
        active = store;
        return store;
    }

    private static RegionOverrideStore ensureLoaded(MinecraftServer server) {
        RegionOverrideStore store = active;
        return store != null ? store : load(server);
    }

    /**
     * No {@code DataFixTypes}: NeoForge patches the SavedData factory to accept a null type and
     * then reads the tag as-is (SavedData.java:49-55, DimensionDataStorage#readTagFromDisk in the
     * 21.1.249 patched source skips the DFU update when the type is null). Our tag is versioned by
     * {@link #VERSION} and never passed through DFU.
     */
    public static SavedData.Factory<RegionOverrideStore> factory() {
        return new SavedData.Factory<>(RegionOverrideStore::new, RegionOverrideStore::read);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This store is already bound to one server's overworld (the installed router resolves it
     * per call), so the server argument is only there to satisfy the api; the region guard is the
     * real contract here: {@code region} must be a random-drawn region — 34 or larger, never
     * negative — and the call is idempotent.
     */
    @Override
    public boolean forceLane(MinecraftServer server, int region, RegionOverrides.Override override) {
        return force(region, override);
    }

    /** The write itself, shared with tests; server thread only. */
    boolean force(int region, RegionOverrides.Override override) {
        if (override == null || !isRandomRegion(region)) {
            return false;
        }
        Table current = this.table;
        if (current.get(region).filter(existing -> existing == override).isPresent()) {
            return true;
        }
        this.table = current.with(region, override);
        this.setDirty();
        return true;
    }

    /** Lock-free read used on worldgen threads. */
    @Override
    public Optional<RegionOverrides.Override> overrideOf(int region) {
        return this.table.get(region);
    }

    /** True for the region indexes an override may target (the assigner's random draws, positive side only). */
    public static boolean isRandomRegion(int region) {
        return region >= FIRST_RANDOM_REGION;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt(TAG_VERSION, VERSION);
        ListTag entries = new ListTag();
        for (Map.Entry<Integer, RegionOverrides.Override> entry : this.table.entries().entrySet()) {
            CompoundTag saved = new CompoundTag();
            saved.putInt(TAG_REGION, entry.getKey());
            saved.putString(TAG_OVERRIDE, entry.getValue().name());
            entries.add(saved);
        }
        tag.put(TAG_ENTRIES, entries);
        return tag;
    }

    static RegionOverrideStore read(CompoundTag tag, HolderLookup.Provider registries) {
        Map<Integer, RegionOverrides.Override> entries = new HashMap<>();
        ListTag saved = tag.getList(TAG_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < saved.size(); i++) {
            CompoundTag entry = saved.getCompound(i);
            int region = entry.getInt(TAG_REGION);
            String name = entry.getString(TAG_OVERRIDE);
            if (!isRandomRegion(region)) {
                LOGGER.warn("dropping region override for non-random region {} ({})", region, name);
                continue;
            }
            RegionOverrides.Override override = parse(name);
            if (override == null) {
                LOGGER.warn("dropping unknown region override '{}' for region {}", name, region);
                continue;
            }
            entries.put(region, override);
        }
        return new RegionOverrideStore(new Table(entries));
    }

    @Nullable
    private static RegionOverrides.Override parse(String name) {
        for (RegionOverrides.Override candidate : RegionOverrides.Override.values()) {
            if (candidate.name().equals(name)) {
                return candidate;
            }
        }
        return null;
    }

    /** Immutable region → override map; replaced wholesale on write so readers never lock. */
    static final class Table {
        static final Table EMPTY = new Table(Map.of());

        private final Map<Integer, RegionOverrides.Override> byRegion;

        Table(Map<Integer, RegionOverrides.Override> byRegion) {
            this.byRegion = Collections.unmodifiableMap(new TreeMap<>(byRegion));
        }

        Optional<RegionOverrides.Override> get(int region) {
            return Optional.ofNullable(this.byRegion.get(region));
        }

        Table with(int region, RegionOverrides.Override override) {
            Map<Integer, RegionOverrides.Override> copy = new HashMap<>(this.byRegion);
            copy.put(region, override);
            return new Table(copy);
        }

        Map<Integer, RegionOverrides.Override> entries() {
            return this.byRegion;
        }
    }
}
