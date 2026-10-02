package dimblend.radio.acoustics.terrain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

/**
 * Thread-safe mirror of rendered terrain geometry for acoustics. Fed by the Sodium chunk-build
 * pipeline (any worker thread) and read by the acoustic simulation workers. Entries are replaced
 * atomically per section; readers never see partially written state.
 */
public final class SectionGeometryCache {
    private static final Map<Long, SectionQuads> SECTIONS = new ConcurrentHashMap<>();
    private static final AtomicLong EPOCH = new AtomicLong();
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();

    /** True once the Sodium pipeline has actually fed us geometry (proof the hook is live). */
    public static boolean active() {
        return ACTIVE.get();
    }

    /** @return true if this call was the one that flipped the cache to active. */
    public static boolean markActive() {
        return ACTIVE.compareAndSet(false, true);
    }

    public static void put(long sectionPos, int originX, int originY, int originZ,
            float[] vertices, byte[] materials, byte[] owners) {
        SECTIONS.put(sectionPos, new SectionQuads(originX, originY, originZ, vertices, materials,
                owners, EPOCH.incrementAndGet()));
    }

    public static void remove(long sectionPos) {
        SECTIONS.remove(sectionPos);
    }

    public static void clear() {
        SECTIONS.clear();
        EPOCH.incrementAndGet();
    }

    /**
     * Order-independent fold of (position, version) over cached sections intersecting
     * {@code bounds}. Changes whenever an in-range section is rebuilt or evicted, which is what
     * re-arms the acoustic update gate after Sodium's asynchronous rebuild lands.
     */
    public static long foldHash(AABB bounds, int minSection, int maxSection) {
        long hash = 0;
        int minY = Math.max(minSection, (int) Math.floor(bounds.minY) >> 4);
        int maxY = Math.min(maxSection - 1, (int) Math.floor(bounds.maxY) >> 4);
        for (int x = (int) Math.floor(bounds.minX) >> 4; x <= ((int) Math.floor(bounds.maxX) >> 4); x++) {
            for (int z = (int) Math.floor(bounds.minZ) >> 4; z <= ((int) Math.floor(bounds.maxZ) >> 4); z++) {
                for (int y = minY; y <= maxY; y++) {
                    SectionQuads entry = SECTIONS.get(SectionPos.asLong(x, y, z));
                    if (entry != null) {
                        long pos = SectionPos.asLong(x, y, z);
                        hash ^= pos * 0x9E3779B97F4A7C15L + entry.version();
                    }
                }
            }
        }
        return hash;
    }

    /**
     * Rendered-geometry coverage of {@code bounds}: every cached section in range, plus the set
     * of section keys that hold real geometry. Sodium only meshes sections its visibility
     * traversal visits (occluded sections are never built), so coverage is always partial; the
     * caller fills the gaps voxel-wise, skipping exactly the keys in {@link Coverage#covered()}.
     * Entries with zero quads (fluids/plants-only sections, block-entity-only sections) are not
     * reported as covered, so the voxel filler still gets a chance at their collision shapes.
     */
    public static Coverage presentSections(AABB bounds, int minSection, int maxSection) {
        List<SectionQuads> sections = new ArrayList<>();
        Set<Long> covered = new HashSet<>();
        int minY = Math.max(minSection, (int) Math.floor(bounds.minY) >> 4);
        int maxY = Math.min(maxSection - 1, (int) Math.floor(bounds.maxY) >> 4);
        for (int x = (int) Math.floor(bounds.minX) >> 4; x <= ((int) Math.floor(bounds.maxX) >> 4); x++) {
            for (int z = (int) Math.floor(bounds.minZ) >> 4; z <= ((int) Math.floor(bounds.maxZ) >> 4); z++) {
                for (int y = minY; y <= maxY; y++) {
                    long pos = SectionPos.asLong(x, y, z);
                    SectionQuads entry = SECTIONS.get(pos);
                    if (entry != null && entry.quadCount() > 0) {
                        sections.add(entry);
                        covered.add(pos);
                    }
                }
            }
        }
        return new Coverage(sections, covered);
    }

    /** @param sections decoded geometry of covered sections; @param covered their section keys. */
    public record Coverage(List<SectionQuads> sections, Set<Long> covered) { }

    /** Counters for probes and diagnostics. */
    public static String stats() {
        long quads = 0;
        for (SectionQuads entry : SECTIONS.values()) {
            quads += entry.quadCount();
        }
        return "sections=" + SECTIONS.size() + " quads=" + quads
                + " approxBytes=" + (quads * (12 * 4 + 1 + 3));
    }

    private SectionGeometryCache() { }
}
