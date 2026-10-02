package dimblend.radio.acoustics.terrain;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import dimblend.radio.acoustics.AcousticSceneChanges;

/**
 * Thread-safe mirror of rendered terrain geometry for acoustics. Fed by the Sodium chunk-build
 * pipeline (any worker thread) and read by the acoustic simulation workers. Entries are replaced
 * atomically per section; readers never see partially written state.
 */
public final class SectionGeometryCache {
    private record CachedSection(SectionQuads quads, boolean complete, Long blocks) { }
    private record ExpectedBlocks(Long fingerprint) { }
    private static final Map<Long, CachedSection> SECTIONS = new ConcurrentHashMap<>();
    private static final Map<Long, ExpectedBlocks> EXPECTED = new ConcurrentHashMap<>();
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
        put(sectionPos, originX, originY, originZ, vertices, materials, owners, true);
    }

    public static void put(long sectionPos, int originX, int originY, int originZ,
            float[] vertices, byte[] materials, byte[] owners, boolean complete) {
        put(sectionPos, originX, originY, originZ, vertices, materials, owners, complete, null);
    }

    public static void put(long sectionPos, int originX, int originY, int originZ,
            float[] vertices, byte[] materials, byte[] owners, boolean complete, Long blocks) {
        CachedSection previous = SECTIONS.get(sectionPos);
        if (previous != null && previous.complete == complete && java.util.Objects.equals(previous.blocks, blocks)
                && java.util.Arrays.equals(previous.quads.vertices(), vertices)
                && java.util.Arrays.equals(previous.quads.materials(), materials)
                && java.util.Arrays.equals(previous.quads.owners(), owners)) return;
        SECTIONS.put(sectionPos, new CachedSection(new SectionQuads(originX, originY, originZ, vertices, materials,
                owners, EPOCH.incrementAndGet()), complete, blocks));
        AcousticSceneChanges.geometryChanged(sectionPos);
    }

    /** Immediately reject the old mesh; a late build must match the newly captured block contents. */
    public static void invalidate(long sectionPos) {
        EXPECTED.put(sectionPos, new ExpectedBlocks(null));
        SECTIONS.remove(sectionPos);
        EPOCH.incrementAndGet();
    }

    public static boolean needsValidation(long sectionPos) { return EXPECTED.containsKey(sectionPos); }

    /**
     * An edited section whose new block contents have not been fingerprinted yet. Captures only
     * hash these: every later edit goes through {@link #invalidate} and re-arms it, so a section
     * edited once is not re-hashed (4096 blocks) on every capture for as long as it stays loaded.
     */
    public static boolean awaitingFingerprint(long sectionPos) {
        ExpectedBlocks expected = EXPECTED.get(sectionPos);
        return expected != null && expected.fingerprint == null;
    }

    public static void expectBlocks(long sectionPos, long fingerprint) {
        EXPECTED.computeIfPresent(sectionPos, (key, old) -> new ExpectedBlocks(fingerprint));
    }

    private static boolean current(long sectionPos, CachedSection entry) {
        ExpectedBlocks expected = EXPECTED.get(sectionPos);
        return expected == null || expected.fingerprint != null && expected.fingerprint.equals(entry.blocks);
    }

    public static void remove(long sectionPos) {
        SECTIONS.remove(sectionPos);
        EXPECTED.remove(sectionPos);
        AcousticSceneChanges.geometryChanged(sectionPos);
    }

    public static void clear() {
        SECTIONS.clear();
        EXPECTED.clear();
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
                    CachedSection entry = SECTIONS.get(SectionPos.asLong(x, y, z));
                    if (entry != null && current(SectionPos.asLong(x, y, z), entry)) {
                        long pos = SectionPos.asLong(x, y, z);
                        hash ^= pos * 0x9E3779B97F4A7C15L + entry.quads.version();
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
        return presentSections(bounds, minSection, maxSection, false);
    }

    /** Incomplete sections use whole-section voxel fallback, avoiding holes or duplicate faces. */
    public static Coverage presentSections(AABB bounds, int minSection, int maxSection, boolean allowIncomplete) {
        List<SectionQuads> sections = new ArrayList<>();
        LongSet covered = new LongOpenHashSet();
        int minY = Math.max(minSection, (int) Math.floor(bounds.minY) >> 4);
        int maxY = Math.min(maxSection - 1, (int) Math.floor(bounds.maxY) >> 4);
        for (int x = (int) Math.floor(bounds.minX) >> 4; x <= ((int) Math.floor(bounds.maxX) >> 4); x++) {
            for (int z = (int) Math.floor(bounds.minZ) >> 4; z <= ((int) Math.floor(bounds.maxZ) >> 4); z++) {
                for (int y = minY; y <= maxY; y++) {
                    long pos = SectionPos.asLong(x, y, z);
                    CachedSection entry = SECTIONS.get(pos);
                    if (entry != null && current(pos, entry) && entry.quads.quadCount() > 0 && (entry.complete || allowIncomplete)) {
                        sections.add(entry.quads);
                        covered.add(pos);
                    }
                }
            }
        }
        return new Coverage(sections, covered);
    }

    /** @param sections decoded geometry of covered sections; @param covered their section keys. */
    public record Coverage(List<SectionQuads> sections, LongSet covered) { }

    /** Counters for probes and diagnostics. */
    public static String stats() {
        long quads = 0;
        for (CachedSection entry : SECTIONS.values()) {
            quads += entry.quads.quadCount();
        }
        return "sections=" + SECTIONS.size() + " quads=" + quads
                + " approxBytes=" + (quads * (12 * 4 + 1 + 3));
    }

    private SectionGeometryCache() { }
}
