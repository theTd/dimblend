package dimblend.purge;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelLoadingTicket;
import dev.ryanhcode.sable.api.sublevel.ticket.SubLevelTicketInfo;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelOccupancySavedData;
import dev.ryanhcode.sable.sublevel.storage.SubLevelTicketsSavedData;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunk;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelRegionFile;
import dev.ryanhcode.sable.sublevel.storage.region.SubLevelStorageFile;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelStorage;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import dev.ryanhcode.sable.sublevel.tracking_points.SubLevelTrackingPointSavedData;
import dev.ryanhcode.sable.sublevel.tracking_points.TrackingPoint;
import dimblend.api.NonCrewCarriages;
import dimblend.api.purge.SableSubLevelsPurgedEvent;
import dimblend.mixin.DistanceManagerAccessor;
import dimblend.mixin.sable.ServerSubLevelContainerAccessor;
import dimblend.mixin.sable.SubLevelHoldingChunkAccessor;
import dimblend.mixin.sable.SubLevelHoldingChunkMapAccessor;
import dimblend.mixin.sable.SubLevelStorageAccessor;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongPredicate;
import javax.annotation.Nullable;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.util.SortedArraySet;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * Coordinates Sable sub-level lifecycle with rotating-dimension region purging.
 *
 * <p>All {@code dev.ryanhcode.sable.*} imports live here so {@link RegionPurgeController} remains
 * loadable when Sable is absent.
 */
final class RegionSablePurger implements SablePurgeBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double BOUNDING_BOX_MARGIN = 1.0D;
    private static final double PLAYER_SAFETY_MARGIN = 4.0D;
    private static final double TRACKING_PLAUSIBILITY_MARGIN = 32.0D;

    private final SableForceLoadIdleTracker idleTracker = new SableForceLoadIdleTracker();

    private record HoldingCandidateChunk(SubLevelHoldingChunk chunk, boolean inMemory) {
    }

    private record HoldingEntry(
            UUID uuid,
            SubLevelData data,
            @Nullable GlobalSavedSubLevelPointer pointer,
            SubLevelHoldingChunk chunk,
            boolean inMemory) {
    }

    @Override
    public int releaseRemoteForceLoads(
            ServerLevel level,
            int[] centers,
            int keepChunks,
            long nowTick,
            long idleTicks,
            LongPredicate inPlotGrid) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            this.idleTracker.clear();
            return 0;
        }
        ServerSubLevelContainerAccessor containerAcc = (ServerSubLevelContainerAccessor) container;
        Object2ObjectMap<ServerSubLevel, ObjectSet<SubLevelLoadingTicket<?>>> activeTickets =
                containerAcc.dimblend$getActiveTickets();
        if (activeTickets.isEmpty()) {
            this.idleTracker.clear();
            return 0;
        }

        Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> vanillaTickets =
                ((DistanceManagerAccessor) level.getChunkSource().chunkMap.getDistanceManager())
                        .dimblend$getTickets();
        Set<UUID> visitedIds = new HashSet<>();
        Map<UUID, Collection<ServerSubLevel>> chainsByRoot = new LinkedHashMap<>();
        List<UUID> eligibleRoots = new ArrayList<>();

        for (ServerSubLevel start : new ArrayList<>(activeTickets.keySet())) {
            if (!visitedIds.add(start.getUniqueId())) {
                continue;
            }
            Collection<ServerSubLevel> chain = SubLevelHelper.getLoadingDependencyChain(start);
            UUID root = canonicalRoot(chain, visitedIds);
            if (isChainEligibleForUnticket(level, chain, centers, keepChunks, inPlotGrid, vanillaTickets)) {
                chainsByRoot.put(root, chain);
                eligibleRoots.add(root);
            }
        }

        List<UUID> readyRoots = this.idleTracker.update(eligibleRoots, nowTick, idleTicks);
        if (readyRoots.isEmpty()) {
            return 0;
        }

        int unticketedChains = 0;
        for (UUID root : readyRoots) {
            Collection<ServerSubLevel> chain = chainsByRoot.get(root);
            if (chain == null) {
                continue;
            }
            boolean released = false;
            for (ServerSubLevel subLevel : chain) {
                ObjectSet<SubLevelLoadingTicket<?>> tickets = activeTickets.get(subLevel);
                if (tickets != null && !tickets.isEmpty()) {
                    for (SubLevelLoadingTicket<?> ticket : new ArrayList<>(tickets)) {
                        released |= removeTicketUnchecked(container, subLevel, ticket);
                    }
                }
                if (containerAcc.dimblend$getAllTickets().remove(subLevel.getUniqueId()) != null) {
                    SubLevelTicketsSavedData.getOrLoad(level).setDirty();
                    released = true;
                }
            }
            if (released) {
                unticketedChains++;
                LOGGER.info("dimblend purge: released force-load ticket(s) on unmanned Sable chain {} ({} member(s))",
                        root, chain.size());
            }
        }
        return unticketedChains;
    }

    @Override
    public int purgeRegion(
            ServerLevel level,
            int regionX,
            int regionZ,
            LongSet terrainRegionsRemaining,
            @Nullable IOWorker entityWorker,
            @Nullable IOWorker poiWorker) {
        if (!(SubLevelContainer.getContainer(level) instanceof ServerSubLevelContainer container)) {
            return 0;
        }
        SubLevelHoldingChunkMap holdingMap = container.getHoldingChunkMap();
        if (holdingMap == null) {
            return 0;
        }
        ServerSubLevelContainerAccessor containerAcc = (ServerSubLevelContainerAccessor) container;
        SubLevelHoldingChunkMapAccessor mapAcc = (SubLevelHoldingChunkMapAccessor) holdingMap;
        SubLevelStorage storage = holdingMap.getStorage();
        SubLevelStorageAccessor storageAcc = (SubLevelStorageAccessor) storage;
        Path storageFolder = storage.getFolder();

        Map<ChunkPos, HoldingCandidateChunk> candidateChunks = collectCandidateChunks(
                regionX, regionZ, mapAcc, storage, storageAcc, storageFolder);
        Set<HoldingCandidateChunk> modifiedChunks = new LinkedHashSet<>();
        Map<UUID, HoldingEntry> entriesByUuid = collectEntries(
                candidateChunks, storageAcc, storageFolder, containerAcc.dimblend$getAllTickets(),
                container, regionX, regionZ, modifiedChunks);

        Set<HoldingEntry> toDelete = selectEntriesForPurge(
                entriesByUuid, container, containerAcc.dimblend$getAllTickets(),
                regionX, regionZ, terrainRegionsRemaining);
        if (toDelete.isEmpty()) {
            if (modifiedChunks.isEmpty()) {
                pruneTargetEmptyFiles(storage, storageAcc, storageFolder, regionX, regionZ);
            } else {
                // 没有要删的 sable，只剪掉了目标 region 的悬挂指针：照样落盘，并清空随之变空的 .slvls。
                persistModifiedChunks(modifiedChunks, mapAcc, storage);
                flushAndPruneEmptyFiles(storage, storageAcc, storageFolder, regionX, regionZ);
            }
            return 0;
        }

        Set<UUID> deletedUuids = new LinkedHashSet<>();
        boolean occupancyDirty = false;
        boolean ticketsDirty = false;
        SubLevelTrackingPointSavedData tracking = SubLevelTrackingPointSavedData.getOrLoad(level);

        for (HoldingEntry entry : toDelete) {
            UUID uuid = entry.uuid();
            deletedUuids.add(uuid);

            mapAcc.dimblend$getAllHoldingSubLevels().remove(uuid);
            ((SubLevelHoldingChunkAccessor) entry.chunk()).dimblend$getLoadedHoldingSubLevelsMap().remove(uuid);

            GlobalSavedSubLevelPointer ptr = entry.pointer();
            if (ptr != null) {
                entry.chunk().getSubLevelPointers().remove(ptr.local());
                mapAcc.dimblend$getQueuedDeletion().remove(ptr);
                storage.attemptSaveSubLevel(ptr, null);
            }

            if (containerAcc.dimblend$getAllTickets().remove(uuid) != null) {
                ticketsDirty = true;
            }

            if (clearPlotStateAndChunks(level, container, entry.data(), entityWorker, poiWorker)) {
                occupancyDirty = true;
            }

            detargetTrackingPoints(tracking, entry, ptr);
            modifiedChunks.add(new HoldingCandidateChunk(entry.chunk(), entry.inMemory()));
        }

        persistModifiedChunks(modifiedChunks, mapAcc, storage);
        if (occupancyDirty) {
            SubLevelOccupancySavedData.getOrLoad(level).setDirty();
        }
        if (ticketsDirty) {
            SubLevelTicketsSavedData.getOrLoad(level).setDirty();
        }

        flushAndPruneEmptyFiles(storage, storageAcc, storageFolder, regionX, regionZ);

        if (!deletedUuids.isEmpty()) {
            NeoForge.EVENT_BUS.post(new SableSubLevelsPurgedEvent(level, regionX, regionZ, deletedUuids));
        }
        return deletedUuids.size();
    }

    @Override
    public void clear() {
        this.idleTracker.clear();
    }

    private static UUID canonicalRoot(Collection<ServerSubLevel> chain, Set<UUID> visitedIds) {
        UUID min = null;
        for (ServerSubLevel subLevel : chain) {
            UUID id = subLevel.getUniqueId();
            visitedIds.add(id);
            if (min == null || id.compareTo(min) < 0) {
                min = id;
            }
        }
        return min;
    }

    private static boolean isChainEligibleForUnticket(
            ServerLevel level,
            Collection<ServerSubLevel> chain,
            int[] centers,
            int keepChunks,
            LongPredicate inPlotGrid,
            Long2ObjectOpenHashMap<SortedArraySet<Ticket<?>>> vanillaTickets) {
        for (ServerSubLevel subLevel : chain) {
            if (subLevel.isRemoved()) {
                return false;
            }
            // A car whose owning mod says it must stay loaded (the chase's WAITING/ACTIVE nest) is
            // not "unmanned": unticketing it would unload the car, make the director abandon the
            // chase and burn that End region. WON nests answer false and stay purgeable.
            if (NonCrewCarriages.isForceLoadProtected(level, subLevel.getUniqueId())) {
                return false;
            }
            Vector3dc posePos = subLevel.logicalPose().position();
            int poseRx = SableRegionPurgeMath.regionCoordOfBlock(posePos.x());
            int poseRz = SableRegionPurgeMath.regionCoordOfBlock(posePos.z());
            if (RegionPurgePlanner.nearestCenterDistance(poseRx, centers) <= keepChunks
                    || inPlotGrid.test(RegionPurgePlanner.key(poseRx, poseRz))) {
                return false;
            }

            BoundingBox3d expanded = new BoundingBox3d(subLevel.boundingBox()).expand(BOUNDING_BOX_MARGIN);
            int minChunkX = Mth.floor(expanded.minX()) >> 4;
            int maxChunkX = Mth.floor(expanded.maxX()) >> 4;
            int minChunkZ = Mth.floor(expanded.minZ()) >> 4;
            int maxChunkZ = Mth.floor(expanded.maxZ()) >> 4;

            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                int rx = RegionPurgePlanner.regionOfChunk(cx);
                if (RegionPurgePlanner.nearestCenterDistance(rx, centers) <= keepChunks) {
                    return false;
                }
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    int rz = RegionPurgePlanner.regionOfChunk(cz);
                    if (inPlotGrid.test(RegionPurgePlanner.key(rx, rz))) {
                        return false;
                    }
                    SortedArraySet<Ticket<?>> chunkTickets = vanillaTickets.get(ChunkPos.asLong(cx, cz));
                    if (chunkTickets != null) {
                        for (Ticket<?> ticket : chunkTickets) {
                            if (!isTransientOrSableChunkTicket(ticket.getType())) {
                                return false;
                            }
                        }
                    }
                }
            }

            var rawBox = subLevel.boundingBox();
            BoundingBox3d playerSafetyBox = new BoundingBox3d(rawBox).expand(PLAYER_SAFETY_MARGIN);
            for (ServerPlayer player : level.players()) {
                Vec3 center = player.getBoundingBox().getCenter();
                if (!player.isSpectator()
                        && Sable.HELPER.getTrackingSubLevel(player) == subLevel
                        && SableRegionPurgeMath.isPointInExpandedBox(
                                center.x,
                                center.y,
                                center.z,
                                rawBox.minX(),
                                rawBox.minY(),
                                rawBox.minZ(),
                                rawBox.maxX(),
                                rawBox.maxY(),
                                rawBox.maxZ(),
                                TRACKING_PLAUSIBILITY_MARGIN)) {
                    return false;
                }
                if (playerSafetyBox.contains(center.x, center.y, center.z)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isTransientOrSableChunkTicket(TicketType<?> type) {
        return type == PhysicsChunkTicketManager.SUB_LEVEL_LOADED_TICKET_TYPE
                || type == TicketType.UNKNOWN
                || type == TicketType.POST_TELEPORT;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean removeTicketUnchecked(
            ServerSubLevelContainer container, ServerSubLevel subLevel, SubLevelLoadingTicket ticket) {
        return container.removeForceLoadTicket(subLevel, ticket.type(), ticket.key());
    }

    /**
     * Scans the 3x3 region neighborhood around {@code (regionX, regionZ)} so dependency chains or
     * holding chunks near a boundary are never missed. Never opens a missing {@code .slvlr} on disk
     * because {@link SubLevelStorage#attemptLoadHoldingChunk} creates empty 4 KiB files on miss.
     */
    private static Map<ChunkPos, HoldingCandidateChunk> collectCandidateChunks(
            int regionX,
            int regionZ,
            SubLevelHoldingChunkMapAccessor mapAcc,
            SubLevelStorage storage,
            SubLevelStorageAccessor storageAcc,
            Path storageFolder) {
        Map<ChunkPos, HoldingCandidateChunk> candidates = new LinkedHashMap<>();
        Long2ObjectMap<SubLevelHoldingChunk> loadedChunks = mapAcc.dimblend$getLoadedHoldingChunks();

        for (SubLevelHoldingChunk chunk : loadedChunks.values()) {
            ChunkPos cp = chunk.getChunkPos();
            if (Math.abs(cp.getRegionX() - regionX) <= 1 && Math.abs(cp.getRegionZ() - regionZ) <= 1) {
                candidates.put(cp, new HoldingCandidateChunk(chunk, true));
            }
        }

        for (int drx = -1; drx <= 1; drx++) {
            for (int drz = -1; drz <= 1; drz++) {
                int rx = regionX + drx;
                int rz = regionZ + drz;
                if (!regionFileExists(storageAcc, storageFolder, rx, rz)) {
                    continue;
                }
                for (int lx = 0; lx < 32; lx++) {
                    for (int lz = 0; lz < 32; lz++) {
                        ChunkPos cp = new ChunkPos((rx << 5) + lx, (rz << 5) + lz);
                        if (loadedChunks.containsKey(cp.toLong())) {
                            continue;
                        }
                        SubLevelHoldingChunk diskChunk = storage.attemptLoadHoldingChunk(cp);
                        if (diskChunk != null) {
                            candidates.put(cp, new HoldingCandidateChunk(diskChunk, false));
                        }
                    }
                }
            }
        }
        return candidates;
    }

    private static boolean regionFileExists(
            SubLevelStorageAccessor storageAcc, Path storageFolder, int regionX, int regionZ) {
        if (storageAcc.dimblend$getRegionCache().containsKey(ChunkPos.asLong(regionX, regionZ))) {
            return true;
        }
        return Files.exists(storageFolder.resolve("r." + regionX + "." + regionZ + SubLevelRegionFile.FILE_EXTENSION));
    }

    private static Map<UUID, HoldingEntry> collectEntries(
            Map<ChunkPos, HoldingCandidateChunk> candidateChunks,
            SubLevelStorageAccessor storageAcc,
            Path storageFolder,
            Map<UUID, SubLevelTicketInfo> allTickets,
            ServerSubLevelContainer container,
            int targetRegionX,
            int targetRegionZ,
            Set<HoldingCandidateChunk> modifiedChunks) {
        Map<UUID, HoldingEntry> entriesByUuid = new LinkedHashMap<>();
        for (HoldingCandidateChunk candidate : candidateChunks.values()) {
            SubLevelHoldingChunk chunk = candidate.chunk();
            ChunkPos chunkPos = chunk.getChunkPos();
            for (HoldingSubLevel holding : chunk.getLoadedHoldingSubLevels()) {
                SubLevelData data = holding.data();
                entriesByUuid.putIfAbsent(
                        data.uuid(),
                        new HoldingEntry(data.uuid(), data, holding.pointer(), chunk, candidate.inMemory()));
            }
            boolean scrubbed = false;
            for (SavedSubLevelPointer localPtr : new ArrayList<>(chunk.getSubLevelPointers())) {
                SubLevelData data = readStoredSubLevelQuietly(storageAcc, storageFolder, chunkPos, localPtr);
                if (data == null) {
                    // 悬挂指针：目标 region 的子级已被搬走/清空后残留的 local 指针。只在目标 region 里剪掉，
                    // 邻区（3x3 邻域只为依赖链而收集）留给它自己那一轮，避免越界改别人的落盘结果。
                    if (chunkPos.getRegionX() == targetRegionX && chunkPos.getRegionZ() == targetRegionZ
                            && chunk.getSubLevelPointers().remove(localPtr)) {
                        scrubbed = true;
                    }
                    continue;
                }
                if (container.getSubLevel(data.uuid()) != null || allTickets.containsKey(data.uuid())) {
                    continue;
                }
                GlobalSavedSubLevelPointer globalPtr =
                        new GlobalSavedSubLevelPointer(chunkPos, localPtr.storageIndex(), localPtr.subLevelIndex());
                entriesByUuid.putIfAbsent(
                        data.uuid(),
                        new HoldingEntry(data.uuid(), data, globalPtr, chunk, candidate.inMemory()));
            }
            if (scrubbed) {
                modifiedChunks.add(candidate);
            }
        }
        return entriesByUuid;
    }

    /**
     * Reads one sub-level tag without the side effects of
     * {@link SubLevelStorage#attemptLoadSubLevel}: that path opens {@link SubLevelStorageFile}, whose
     * constructor uses {@code StandardOpenOption.CREATE}, so a dangling pointer materialises an empty
     * {@code .slvls} file and logs an error — the normal state after a rotated dimension's sub-level region
     * was moved out. Here a missing file is just {@code null}, and an uncached file is opened read-through
     * without entering {@code storageCache}.
     *
     * <p>Deserialization mirrors {@code attemptLoadSubLevel} (zero-bounds recovery + origin chunk) so the
     * returned data is shaped exactly like the one the real load path would hand out.
     */
    private static @Nullable SubLevelData readStoredSubLevelQuietly(
            SubLevelStorageAccessor storageAcc,
            Path storageFolder,
            ChunkPos chunkPos,
            SavedSubLevelPointer pointer) {
        int regionX = chunkPos.getRegionX();
        int regionZ = chunkPos.getRegionZ();
        CompoundTag tag = null;
        try {
            SubLevelStorageFile cached = storageAcc.dimblend$getStorageCache()
                    .get(SectionPos.asLong(regionX, pointer.storageIndex(), regionZ));
            if (cached != null) {
                tag = cached.read(pointer.subLevelIndex());
            } else {
                Path slvlsPath = storageFolder.resolve(storageFileName(regionX, regionZ, pointer.storageIndex())
                        + SubLevelStorageFile.FILE_EXTENSION);
                if (!Files.exists(slvlsPath)) {
                    return null;
                }
                try (SubLevelStorageFile file =
                             new SubLevelStorageFile(slvlsPath, externalDirectory(storageFolder, regionX, regionZ))) {
                    tag = file.read(pointer.subLevelIndex());
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (tag == null) {
            return null;
        }

        SubLevelData data = SubLevelSerializer.fromData(tag);
        BoundingBox3d bounds = data.bounds();
        if (bounds.minX == 0.0 && bounds.minY == 0.0 && bounds.minZ == 0.0
                && bounds.maxX == 0.0 && bounds.maxY == 0.0 && bounds.maxZ == 0.0) {
            Vector3d position = data.pose().position();
            bounds.set(position.x, position.y, position.z, position.x, position.y, position.z).expand(1.0D);
        }
        data.setOriginLoadedChunk(chunkPos);
        return data;
    }

    /** {@code r.<regionX>.<regionZ>.<storageIndex>} — the stem Sable uses for one storage file. */
    private static String storageFileName(int regionX, int regionZ, int storageIndex) {
        return "r." + regionX + "." + regionZ + "." + storageIndex;
    }

    /**
     * External-chunk directory of one region's storage files: Sable's
     * {@code SubLevelStorage#getExternalPath(chunkPos)} appends {@code .r}, and that is the path its own
     * {@code SubLevelStorageFile} handles are built with.
     */
    private static Path externalDirectory(Path storageFolder, int regionX, int regionZ) {
        return storageFolder.resolve("r." + regionX + "." + regionZ + ".r");
    }

    private static Set<HoldingEntry> selectEntriesForPurge(
            Map<UUID, HoldingEntry> entriesByUuid,
            ServerSubLevelContainer container,
            Map<UUID, SubLevelTicketInfo> allTickets,
            int targetRegionX,
            int targetRegionZ,
            LongSet terrainRegionsRemaining) {
        if (entriesByUuid.isEmpty()) {
            return Set.of();
        }
        Map<UUID, Set<UUID>> adjacency = new HashMap<>();
        for (HoldingEntry entry : entriesByUuid.values()) {
            UUID id = entry.uuid();
            Set<UUID> neighbors = adjacency.computeIfAbsent(id, k -> new LinkedHashSet<>());
            for (UUID dep : entry.data().dependencies()) {
                neighbors.add(dep);
                adjacency.computeIfAbsent(dep, k -> new LinkedHashSet<>()).add(id);
            }
        }

        long targetRegionKey = RegionPurgePlanner.key(targetRegionX, targetRegionZ);
        Set<UUID> visited = new HashSet<>();
        Set<HoldingEntry> selected = new LinkedHashSet<>();

        for (UUID start : entriesByUuid.keySet()) {
            if (!visited.add(start)) {
                continue;
            }
            List<HoldingEntry> component = new ArrayList<>();
            boolean blockedByLoadedOrTicket = false;
            ArrayDeque<UUID> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                UUID cur = queue.poll();
                if (container.getSubLevel(cur) != null || allTickets.containsKey(cur)) {
                    blockedByLoadedOrTicket = true;
                }
                HoldingEntry entry = entriesByUuid.get(cur);
                if (entry != null) {
                    component.add(entry);
                }
                Set<UUID> neighbors = adjacency.get(cur);
                if (neighbors != null) {
                    for (UUID next : neighbors) {
                        if (visited.add(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
            if (blockedByLoadedOrTicket || component.isEmpty()) {
                continue;
            }

            LongArrayList poseKeys = new LongArrayList(component.size());
            LongArrayList holdingKeys = new LongArrayList(component.size());
            for (HoldingEntry entry : component) {
                Vector3dc pos = entry.data().pose().position();
                poseKeys.add(SableRegionPurgeMath.poseRegionKey(pos.x(), pos.z()));
                ChunkPos hcPos = entry.chunk().getChunkPos();
                holdingKeys.add(RegionPurgePlanner.key(hcPos.getRegionX(), hcPos.getRegionZ()));
            }
            if (SableRegionPurgeMath.shouldPurgePoseChain(
                    poseKeys, holdingKeys, targetRegionKey, terrainRegionsRemaining)) {
                selected.addAll(component);
            }
        }
        return selected;
    }

    private static boolean clearPlotStateAndChunks(
            ServerLevel level,
            ServerSubLevelContainer container,
            SubLevelData data,
            @Nullable IOWorker entityWorker,
            @Nullable IOWorker poiWorker) {
        CompoundTag fullTag = data.fullTag();
        if (!fullTag.contains("plot", Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag plotTag = fullTag.getCompound("plot");
        int localPlotX = plotTag.getInt("plot_x");
        int localPlotZ = plotTag.getInt("plot_z");
        boolean clearedOccupancy = false;
        int side = 1 << container.getLogSideLength();
        if (localPlotX >= 0 && localPlotX < side && localPlotZ >= 0 && localPlotZ < side) {
            container.getOccupancy().clear(container.getIndex(localPlotX, localPlotZ));
            clearedOccupancy = true;
        }
        int globalPlotX = localPlotX + container.getOrigin().x;
        int globalPlotZ = localPlotZ + container.getOrigin().y;
        int logPlotSize = container.getLogPlotSize();

        if (plotTag.contains("chunks", Tag.TAG_COMPOUND)) {
            CompoundTag chunksTag = plotTag.getCompound("chunks");
            for (String key : chunksTag.getAllKeys()) {
                long packedLocal;
                try {
                    packedLocal = Long.parseLong(key);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                ChunkPos globalPlotChunk = new ChunkPos(
                        ChunkPos.getX(packedLocal) + (globalPlotX << logPlotSize),
                        ChunkPos.getZ(packedLocal) + (globalPlotZ << logPlotSize));
                if (entityWorker != null) {
                    entityWorker.store(globalPlotChunk, null);
                }
                if (poiWorker != null) {
                    poiWorker.store(globalPlotChunk, null);
                }
                RegionMemoryPruner.prunePlotChunkPoi(level, globalPlotChunk);
            }
        }
        return clearedOccupancy;
    }

    private static void detargetTrackingPoints(
            SubLevelTrackingPointSavedData tracking,
            HoldingEntry entry,
            @Nullable GlobalSavedSubLevelPointer ptr) {
        for (Map.Entry<UUID, TrackingPoint> tpEntry : tracking.getAllTrackingPoints()) {
            TrackingPoint tp = tpEntry.getValue();
            if (!tp.inSubLevel()) {
                continue;
            }
            boolean matchId = entry.uuid().equals(tp.subLevelID());
            boolean matchPtr = ptr != null && ptr.equals(tp.lastSavedSubLevelPointer());
            if (matchId || matchPtr) {
                Vector3d worldPos = entry.data().pose().transformPosition(new Vector3d(tp.point()));
                tracking.setTrackingPoint(
                        tpEntry.getKey(),
                        new TrackingPoint(false, null, null, worldPos, null));
            }
        }
    }

    private static void persistModifiedChunks(
            Set<HoldingCandidateChunk> modifiedChunks,
            SubLevelHoldingChunkMapAccessor mapAcc,
            SubLevelStorage storage) {
        for (HoldingCandidateChunk mc : modifiedChunks) {
            SubLevelHoldingChunk chunk = mc.chunk();
            ChunkPos hcPos = chunk.getChunkPos();
            long hcKey = hcPos.toLong();
            if (chunk.isEmpty()) {
                storage.attemptRemoveHoldingChunk(hcPos);
                if (mc.inMemory()) {
                    mapAcc.dimblend$getLoadedHoldingChunks().remove(hcKey);
                    mapAcc.dimblend$getDirtyHoldingChunks().remove(hcKey);
                    mapAcc.dimblend$getQueuedUnloads().remove(hcPos);
                }
            } else {
                storage.attemptSaveHoldingChunk(hcPos, chunk);
                if (mc.inMemory()) {
                    mapAcc.dimblend$getDirtyHoldingChunks().remove(hcKey);
                }
            }
        }
    }

    private static void flushAndPruneEmptyFiles(
            SubLevelStorage storage,
            SubLevelStorageAccessor storageAcc,
            Path storageFolder,
            int targetRegionX,
            int targetRegionZ) {
        try {
            storage.flush();
        } catch (IOException e) {
            LOGGER.warn("dimblend purge: failed flushing Sable storage", e);
        }

        LongOpenHashSet emptyRegionKeys = new LongOpenHashSet();
        var storageIter = storageAcc.dimblend$getStorageCache().long2ObjectEntrySet().iterator();
        while (storageIter.hasNext()) {
            SubLevelStorageFile file = storageIter.next().getValue();
            if (file.isEmpty()) {
                file.delete();
                storageIter.remove();
            }
        }

        var regionIter = storageAcc.dimblend$getRegionCache().long2ObjectEntrySet().iterator();
        while (regionIter.hasNext()) {
            var entry = regionIter.next();
            SubLevelRegionFile file = entry.getValue();
            if (file.isEmpty()) {
                emptyRegionKeys.add(entry.getLongKey());
                file.delete();
                regionIter.remove();
            }
        }

        pruneTargetEmptyFiles(storage, storageAcc, storageFolder, targetRegionX, targetRegionZ);
        for (long key : emptyRegionKeys) {
            pruneEmptyStorageFilesOnDisk(
                    storageFolder, ChunkPos.getX(key), ChunkPos.getZ(key), storageAcc);
        }
    }

    private static void pruneTargetEmptyFiles(
            SubLevelStorage storage,
            SubLevelStorageAccessor storageAcc,
            Path storageFolder,
            int regionX,
            int regionZ) {
        long regionKey = ChunkPos.asLong(regionX, regionZ);
        SubLevelRegionFile cached = storageAcc.dimblend$getRegionCache().get(regionKey);
        if (cached != null) {
            if (cached.isEmpty()) {
                cached.delete();
                storageAcc.dimblend$getRegionCache().remove(regionKey);
            }
        } else {
            Path regionPath = storageFolder.resolve("r." + regionX + "." + regionZ + SubLevelRegionFile.FILE_EXTENSION);
            if (Files.exists(regionPath)) {
                Path externalPath = externalDirectory(storageFolder, regionX, regionZ);
                try {
                    SubLevelRegionFile file = new SubLevelRegionFile(regionPath, externalPath);
                    if (file.isEmpty()) {
                        file.delete();
                    } else {
                        file.close();
                    }
                } catch (IOException e) {
                    LOGGER.warn("dimblend purge: failed inspecting {} for empty cleanup", regionPath, e);
                }
            }
        }
        pruneEmptyStorageFilesOnDisk(storageFolder, regionX, regionZ, storageAcc);
    }

    private static void pruneEmptyStorageFilesOnDisk(
            Path storageFolder, int regionX, int regionZ, SubLevelStorageAccessor storageAcc) {
        if (!Files.isDirectory(storageFolder)) {
            return;
        }
        String prefix = "r." + regionX + "." + regionZ + ".";
        String glob = prefix + "*" + SubLevelStorageFile.FILE_EXTENSION;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(storageFolder, glob)) {
            for (Path slvlsPath : stream) {
                String name = slvlsPath.getFileName().toString();
                int indexStart = prefix.length();
                int indexEnd = name.length() - SubLevelStorageFile.FILE_EXTENSION.length();
                if (indexEnd <= indexStart) {
                    continue;
                }
                int storageIndex;
                try {
                    storageIndex = Integer.parseInt(name.substring(indexStart, indexEnd));
                } catch (NumberFormatException ignored) {
                    continue;
                }
                long cacheKey = SectionPos.asLong(regionX, storageIndex, regionZ);
                if (storageAcc.dimblend$getStorageCache().containsKey(cacheKey)) {
                    continue;
                }
                SubLevelStorageFile file =
                        new SubLevelStorageFile(slvlsPath, externalDirectory(storageFolder, regionX, regionZ));
                if (file.isEmpty()) {
                    file.delete();
                } else {
                    file.close();
                }
            }
        } catch (IOException e) {
            LOGGER.warn("dimblend purge: failed pruning empty .slvls files for r.{}.{}", regionX, regionZ, e);
        }
    }
}
