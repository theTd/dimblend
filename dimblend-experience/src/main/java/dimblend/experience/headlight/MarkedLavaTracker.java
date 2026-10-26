package dimblend.experience.headlight;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dimblend.experience.Config;
import dimblend.experience.compat.dndecor.FrontlightSpotlight;
import dimblend.experience.compat.sable.SableWorldPosition;
import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.TuningSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.joml.Vector3d;

/**
 * Per-{@link ServerLevel} "marked lava" detector for the observer blaze, and the only server-side answer to
 * "is there lava the client would outline near here". It performs no work of its own: everything runs inside
 * {@link #markedNear}, which the observer block entity calls on its own tick, so a level without a loaded
 * observer costs nothing.
 *
 * <p>"Marked lava" is defined by exactly the client's test -- an exposed lava top face lit by an enabled and lit
 * dndecor frontlight, gated by the same tuning options (sableHeadlight and sableHeadlightLavaHighlight, plus range,
 * angle, intensity and occlusion), with the same lit threshold and voxel budget. Lamps on Sable vehicles and lamps
 * placed in the world both count, because both light and outline on the client ({@code HeadlightCollector} emits a
 * beam for a world lamp too); vehicle lamps get their lens and direction from the vehicle pose, world lamps use
 * their own block centre and facing.</p>
 *
 * <p>Cost: at most one full {@link HeadlightLavaScan#scan} per {@value #RECOMPUTE_TICKS} ticks for the whole
 * tracker, shared by every registered observer; answering one observer is then a distance test against that one
 * list ({@link HeadlightLavaMath#anyWithin}), so more observers never mean more scans. Lamp positions are cached
 * for at most {@value #LAMP_REFRESH_TICKS} ticks (per vehicle and per world chunk) while whether a lamp is
 * activated and lit is read fresh on every recompute, so switching a lamp needs no rescan.</p>
 *
 * <p>No chunk is ever loaded or generated here: world lamps are enumerated only in already-loaded chunks, and every
 * block read goes through {@link LoadedChunks}. A lamp in a chunk that unloads is simply skipped until it is back.</p>
 *
 * <p>Accepted divergences from the client (documented in the requirements spec): vehicle blocks are not occluders
 * here -- {@link HeadlightLavaScan} traces world blocks only -- because a plot block's occlusion would have to be
 * projected through the vehicle pose, and a lamp on a vehicle rarely shines through its own hull; the client's
 * eight-nearest-lamp render cap does not apply (there is no camera), the per-scan voxel budget and the
 * {@link HeadlightLavaMath#MAX_SURFACES} count bound the work instead; the client's camera-distance face cut cannot
 * be mirrored, so the server keeps the first faces in scan order; and world lamps come from the chunks around an
 * observer (range + radius) rather than the client's render-distance index.</p>
 */
public final class MarkedLavaTracker {
    /** One scan for the whole tracker is this many ticks old at most: ten answers a second, as on the client. */
    public static final int RECOMPUTE_TICKS = 10;
    /** A vehicle's or a world chunk's lamp positions are re-scanned this often; lamp state is read fresh always. */
    public static final int LAMP_REFRESH_TICKS = 40;

    /** Margin on the world-chunk search box, so a lamp whose centre is just inside {@code range + radius} is kept. */
    private static final double LAMP_RADIUS_MARGIN = 1.0D;

    /**
     * Keyed by level <em>instance</em>, not by dimension key: two levels of one dimension (an integrated server
     * restarted into another save) must not share lamp tables or scan results. Weak keys are a backstop only; the
     * lifecycle listener below drops the entries on server stop and on level unload.
     */
    private static final Map<ServerLevel, MarkedLavaTracker> BY_LEVEL = new WeakHashMap<>();

    private final Set<BlockPos> observers = new HashSet<>();
    private final Map<UUID, LampCache> lampsPerVehicle = new HashMap<>();
    private final Map<Long, LampCache> lampsPerChunk = new HashMap<>();
    private List<LavaSurface> marked = List.of();
    private long markedAt = -1L;

    public static MarkedLavaTracker of(ServerLevel level) {
        return BY_LEVEL.computeIfAbsent(level, key -> new MarkedLavaTracker());
    }

    /**
     * Drops every tracker, caches included. Called when the server stops and when a level unloads: every cache here
     * is stamped with that level's game time, so nothing may outlive the level it was measured in.
     */
    public static void clearAll() {
        BY_LEVEL.clear();
    }

    /** Registers the server-stop and level-unload hooks; called from the gated Create + Sable registration. */
    public static void registerLifecycle() {
        NeoForge.EVENT_BUS.addListener(MarkedLavaTracker::onServerStopped);
        NeoForge.EVENT_BUS.addListener(MarkedLavaTracker::onLevelUnload);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        clearAll();
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            BY_LEVEL.remove(level);
        }
    }

    /** Called from the block entity's {@code setLevel}: from now on this position can be queried. */
    public void register(BlockPos pos) {
        observers.add(pos.immutable());
    }

    /** Called from the block entity's removal: drops the observer and, once none are left, every cache. */
    public void unregister(BlockPos pos) {
        observers.remove(pos);
        if (observers.isEmpty()) {
            marked = List.of();
            markedAt = -1L;
            lampsPerVehicle.clear();
            lampsPerChunk.clear();
        }
    }

    /** Every registered observer's projected world centre, the frame the lamp search and the scan work in. */
    private List<Vec3> observerCentres(ServerLevel level) {
        List<Vec3> centres = new ArrayList<>(observers.size());
        for (BlockPos pos : observers) {
            centres.add(worldCenter(level, pos));
        }
        return centres;
    }

    /**
     * Whether any marked lava top face lies within {@code radius} blocks (Euclidean) of the observer's projected
     * world centre. One tracker-wide scan feeds this answer; an observer that is not registered never triggers one.
     */
    public boolean markedNear(ServerLevel level, BlockPos observer, Vec3 center, double radius) {
        if (!observers.contains(observer)) {
            return false;
        }
        return HeadlightLavaMath.anyWithin(marked(level), center, radius);
    }

    /** The tracked marked-lava set for the current recompute window, computed at most once per window. */
    private List<LavaSurface> marked(ServerLevel level) {
        long now = level.getGameTime();
        if (fresh(markedAt, now, RECOMPUTE_TICKS)) {
            return marked;
        }
        markedAt = now;
        marked = List.of();
        if (!detectionEnabled()) {
            return marked;
        }
        double range = TuningSettings.get(TuningOption.SABLE_HEADLIGHT_RANGE);
        double angle = TuningSettings.get(TuningOption.SABLE_HEADLIGHT_ANGLE);
        double strength = TuningSettings.get(TuningOption.SABLE_HEADLIGHT_INTENSITY);
        boolean occlusion = TuningSettings.get(TuningOption.SABLE_HEADLIGHT_OCCLUSION) == 1.0D;
        double radius = TuningSettings.get(TuningOption.OBSERVER_BLAZE_RADIUS);
        List<Vec3> centres = observerCentres(level);
        if (centres.isEmpty()) {
            return marked;
        }
        List<HeadlightRay> beams = collect(level, now, centres, range, radius);
        if (beams.isEmpty()) {
            return marked;
        }
        List<LavaSurface> surfaces =
                HeadlightLavaScan.scan(level, beams, range, angle, strength, occlusion).surfaces();
        // The client cuts its outline at MAX_SURFACES by camera distance; the server has no camera, so it keeps the
        // same count in scan order. The cut only ever matters on a lava field far larger than a beam can mark.
        marked = surfaces.size() <= HeadlightLavaMath.MAX_SURFACES
                ? List.copyOf(surfaces)
                : List.copyOf(surfaces.subList(0, HeadlightLavaMath.MAX_SURFACES));
        return marked;
    }

    /** Whether a cache stamped at {@code at} is still usable at {@code now}; a stamp in the future counts as expired. */
    private static boolean fresh(long at, long now, long ticks) {
        long age = now - at;
        return at >= 0L && age >= 0L && age < ticks;
    }

    private static boolean detectionEnabled() {
        return Config.isLoaded()
                && TuningSettings.get(TuningOption.SABLE_HEADLIGHT) == 1.0D
                && TuningSettings.get(TuningOption.SABLE_HEADLIGHT_LAVA_HIGHLIGHT) == 1.0D
                && TuningSettings.get(TuningOption.SABLE_HEADLIGHT_INTENSITY) > 0.0D;
    }

    /** The projected world centre of a block, i.e. where a block in a Sable plot really is. */
    public static Vec3 worldCenter(Level level, BlockPos pos) {
        return SableWorldPosition.project(level, pos.getCenter());
    }

    /**
     * Every lamp that can mark lava near an observer: vehicle lamps in their world pose, plus world lamps from the
     * loaded chunks around the observers. Both lists come from the caches; lens, direction and the lit state are
     * read fresh here, so a lamp that moved or switched needs no rescan.
     */
    private List<HeadlightRay> collect(ServerLevel level, long now, List<Vec3> centres, double range, double radius) {
        List<HeadlightRay> out = new ArrayList<>();
        collectVehicleLamps(level, now, centres, range, radius, out);
        collectWorldLamps(level, now, centres, range, radius, out);
        return out;
    }

    /** Lamps on every loaded Sable vehicle, transformed into world space by the vehicle pose. */
    private void collectVehicleLamps(ServerLevel level, long now, List<Vec3> centres, double range, double radius,
                                     List<HeadlightRay> out) {
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        if (container == null) {
            lampsPerVehicle.clear();
            return;
        }
        Set<UUID> alive = new HashSet<>();
        Vector3d local = new Vector3d();
        Vector3d world = new Vector3d();
        Vector3d direction = new Vector3d();
        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            UUID id = subLevel.getUniqueId();
            alive.add(id);
            LampCache cache = lampsPerVehicle.get(id);
            if (cache == null || !fresh(cache.at(), now, LAMP_REFRESH_TICKS)) {
                cache = new LampCache(scanPlotLamps(subLevel), now);
                lampsPerVehicle.put(id, cache);
            }
            if (cache.lamps().isEmpty()) {
                continue;
            }
            Pose3dc pose = subLevel.logicalPose();
            for (BlockPos lampPos : cache.lamps()) {
                HeadlightRay beam = vehicleLamp(level, pose, lampPos, local, world, direction);
                if (beam != null && reachesAny(beam, centres, range, radius)) {
                    out.add(beam);
                }
            }
        }
        lampsPerVehicle.keySet().retainAll(alive);
    }

    /**
     * Lamps placed in the world, from the loaded chunks within {@code range + radius} of an observer: farther away
     * a beam cannot reach anything the observer asks about. The client instead indexes every loaded chunk in render
     * distance, which is more lamps than an answer here can ever need.
     */
    private void collectWorldLamps(ServerLevel level, long now, List<Vec3> centres, double range, double radius,
                                   List<HeadlightRay> out) {
        double reach = range + radius + LAMP_RADIUS_MARGIN;
        Set<Long> nearby = new HashSet<>();
        for (Vec3 centre : centres) {
            int minChunkX = SectionPos.blockToSectionCoord(Mth.floor(centre.x - reach));
            int maxChunkX = SectionPos.blockToSectionCoord(Mth.floor(centre.x + reach));
            int minChunkZ = SectionPos.blockToSectionCoord(Mth.floor(centre.z - reach));
            int maxChunkZ = SectionPos.blockToSectionCoord(Mth.floor(centre.z + reach));
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    long key = ChunkPos.asLong(chunkX, chunkZ);
                    if (!nearby.add(key)) {
                        continue;
                    }
                    LampCache cache = lampsPerChunk.get(key);
                    if (cache != null && fresh(cache.at(), now, LAMP_REFRESH_TICKS)) {
                        continue;
                    }
                    List<BlockPos> lamps = scanWorldChunk(level, chunkX, chunkZ);
                    if (lamps != null) {
                        lampsPerChunk.put(key, new LampCache(lamps, now));
                    }
                }
            }
        }
        lampsPerChunk.keySet().retainAll(nearby);
        for (LampCache cache : lampsPerChunk.values()) {
            for (BlockPos lampPos : cache.lamps()) {
                HeadlightRay beam = worldLamp(level, lampPos);
                if (beam != null && reachesAny(beam, centres, range, radius)) {
                    out.add(beam);
                }
            }
        }
    }

    /** A beam can mark something near an observer only if its lens is within its own reach of that observer. */
    private static boolean reachesAny(HeadlightRay beam, List<Vec3> centres, double range, double radius) {
        for (Vec3 centre : centres) {
            if (HeadlightLavaMath.coneReaches(beam.lens(), centre, range, radius)) {
                return true;
            }
        }
        return false;
    }

    /** A vehicle lamp in world space, or {@code null} when it is switched off, unlit or its chunk is unloaded. */
    @Nullable
    private static HeadlightRay vehicleLamp(ServerLevel level, Pose3dc pose, BlockPos lampPos,
                                            Vector3d local, Vector3d world, Vector3d direction) {
        BlockState state = LoadedChunks.state(level, lampPos);
        if (!isLitLamp(state)) {
            return null;
        }
        Direction facing = state.getValue(BlockStateProperties.FACING);
        Vec3 localDirection = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        Vec3 localLens =
                HeadlightMath.lens(lampPos.getCenter(), localDirection, FrontlightSpotlight.hasNoBase(state));
        local.set(localLens.x, localLens.y, localLens.z);
        pose.transformPosition(local, world);
        pose.orientation().transform(direction.set(localDirection.x, localDirection.y, localDirection.z));
        return new HeadlightLens(new Vec3(world.x, world.y, world.z),
                new Vec3(direction.x, direction.y, direction.z));
    }

    /** A world lamp in its own frame -- the same lens and facing formula the client uses for a non-vehicle lamp. */
    @Nullable
    private static HeadlightRay worldLamp(ServerLevel level, BlockPos lampPos) {
        BlockState state = LoadedChunks.state(level, lampPos);
        if (!isLitLamp(state)) {
            return null;
        }
        Direction facing = state.getValue(BlockStateProperties.FACING);
        Vec3 direction = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        return new HeadlightLens(
                HeadlightMath.lens(lampPos.getCenter(), direction, FrontlightSpotlight.hasNoBase(state)), direction);
    }

    private static boolean isLitLamp(@Nullable BlockState state) {
        return state != null
                && FrontlightSpotlight.isEnabled(state)
                && state.getValue(BlockStateProperties.LIT);
    }

    /**
     * Palette-pruned walk of a vehicle's loaded plot chunks, indexing every frontlight (lit or not, activated or
     * not): the mirror of the client's {@code FrontlightIndex.scan}, which does the same so that switching a lamp
     * needs no rescan.
     */
    private static List<BlockPos> scanPlotLamps(ServerSubLevel subLevel) {
        LevelPlot plot = subLevel.getPlot();
        List<BlockPos> out = new ArrayList<>();
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            if (chunk != null) {
                collectLamps(chunk, out);
            }
        }
        return out;
    }

    /**
     * Frontlights in one world chunk, or {@code null} when that chunk is not loaded: the query is the non-loading
     * chunk lookup, so a world lamp never pulls a chunk in from the server tick.
     */
    @Nullable
    private static List<BlockPos> scanWorldChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!(level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false) instanceof LevelChunk chunk)) {
            return null;
        }
        List<BlockPos> out = new ArrayList<>();
        collectLamps(chunk, out);
        return out;
    }

    /** Palette-pruned frontlight scan of one chunk; a chunk without a frontlight in any palette costs no reads. */
    private static void collectLamps(LevelChunk chunk, List<BlockPos> out) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        LevelChunkSection[] sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!section.maybeHas(FrontlightSpotlight::isFrontlight)) {
                continue;
            }
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (FrontlightSpotlight.isFrontlight(section.getBlockState(x, y, z))) {
                            out.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
    }

    private record LampCache(List<BlockPos> lamps, long at) {
    }

    private MarkedLavaTracker() {
    }
}
