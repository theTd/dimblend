package dimblend.purge;

import dimblend.mixin.PoiManagerAccessor;
import dimblend.mixin.SectionStorageAccessor;
import dimblend.mixin.ServerLevelCachesAccessor;
import dimblend.mixin.StructureCheckAccessor;
import it.unimi.dsi.fastutil.longs.Long2BooleanMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Optional;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;

/**
 * Drops the per-chunk memos vanilla keeps for a region that is about to be deleted. Runs on the
 * server thread in the same tick the IO deletes are submitted, after the caller has verified that
 * nothing in the region is loaded. Every structure touched here is mutated only on the server
 * thread, and every memo reloads from disk if the delete ends up deferred.
 *
 * <ul>
 *   <li>POI section cache and dirty set: a stale dirty key would rewrite the POI column after the
 *       delete, and cached sections would keep serving deleted POIs. NeoForge already evicts
 *       sections on chunk unload, but POI queries reload them afterwards.</li>
 *   <li>POI village distance tracker: NeoForge's unload eviction bypasses it, so a deleted village
 *       center would keep {@code ServerLevel.isVillage} true at the regenerated site (raids,
 *       villager pathing). Every section is re-evaluated, present in the cache or not.</li>
 *   <li>{@code PoiManager.loadedChunks}: a stale entry makes portal search
 *       ({@code ensureLoadedAndValid}) skip the block rescan of the regenerated chunk.</li>
 *   <li>{@code StructureCheck}: still correct after the delete (deterministic worldgen); pruned
 *       only because vanilla never evicts it.</li>
 * </ul>
 *
 * <p>{@code EntityStorage.emptyChunks} is deliberately left alone: the entity deserializer thread
 * adds to it concurrently, so bulk removal could corrupt the set for a few bytes per chunk.
 */
public final class RegionMemoryPruner {

    public record Pruned(int poiSections, int structureChunks) {
    }

    private RegionMemoryPruner() {
    }

    public static Pruned prune(ServerLevel level, int regionX, int regionZ) {
        long regionKey = RegionPurgePlanner.key(regionX, regionZ);
        int minChunkX = regionX << RegionPurgePlanner.CHUNKS_PER_REGION_SHIFT;
        int minChunkZ = regionZ << RegionPurgePlanner.CHUNKS_PER_REGION_SHIFT;
        int minSection = level.getMinSection();
        int maxSection = level.getMaxSection();

        PoiManager poi = level.getPoiManager();
        SectionStorageAccessor poiStorage = (SectionStorageAccessor) poi;
        PoiManagerAccessor poiAccess = (PoiManagerAccessor) poi;
        Long2ObjectMap<Optional<?>> poiSections = poiStorage.dimblend$getStorage();
        LongLinkedOpenHashSet poiDirty = poiStorage.dimblend$getDirty();
        LongSet poiScanned = poiAccess.dimblend$getLoadedChunks();
        StructureCheckAccessor structures =
                (StructureCheckAccessor) ((ServerLevelCachesAccessor) level).dimblend$getStructureCheck();

        int poiRemoved = 0;
        int structuresRemoved = 0;
        for (int dx = 0; dx < RegionPurgePlanner.CHUNKS_PER_REGION; dx++) {
            for (int dz = 0; dz < RegionPurgePlanner.CHUNKS_PER_REGION; dz++) {
                int chunkX = minChunkX + dx;
                int chunkZ = minChunkZ + dz;
                long chunk = ChunkPos.asLong(chunkX, chunkZ);
                poiScanned.remove(chunk);
                if (structures.dimblend$getLoadedChunks().remove(chunk) != null) {
                    structuresRemoved++;
                }
                for (int sectionY = minSection; sectionY < maxSection; sectionY++) {
                    long section = SectionPos.asLong(chunkX, sectionY, chunkZ);
                    poiDirty.remove(section);
                    if (poiSections.remove(section) != null) {
                        poiRemoved++;
                    }
                    // With the section gone this recomputes a non-village level and drops any stale one.
                    poiAccess.dimblend$onSectionLoad(section);
                }
            }
        }
        for (Long2BooleanMap checks : structures.dimblend$getFeatureChecks().values()) {
            checks.keySet().removeIf((long chunk) -> RegionPurgePlanner.regionKeyOfChunk(chunk) == regionKey);
        }
        return new Pruned(poiRemoved, structuresRemoved);
    }
}
