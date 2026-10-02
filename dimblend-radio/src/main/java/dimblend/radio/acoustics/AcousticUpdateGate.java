package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.zip.CRC32C;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.phys.Vec3;

/** Cache identity follows geometry and meaningful motion, not snapshot object identity or time. */
public final class AcousticUpdateGate {
    private record Body(UUID id, long geometry, Pose3d pose) { }
    private record Scene(long terrain, List<Body> bodies) { }
    private record Input(Scene scene, Vec3 source, Vec3 listener) { }
    private static final class State { Input direct, reflections; }
    private static final Map<Object, Long> TERRAIN = new WeakHashMap<>();
    private static final Map<Object, Scene> SCENES = new WeakHashMap<>();
    private static final Map<Object, State> STATES = new WeakHashMap<>();

    public static void registerTerrain(Object token, Long2ObjectMap<PalettedContainer<BlockState>> sections, LongSet chunks) {
        registerTerrain(token, sections, chunks, 0L);
    }

    /**
     * {@code geometryVersion} folds the render-mesh mirror's content version into the terrain
     * identity. Sodium rebuilds land asynchronously a frame or two after a block change, so the
     * palette hash alone would re-simulate once against stale mirror geometry and then settle;
     * the version fold fires the gate again when the rebuilt section actually arrives.
     */
    public static void registerTerrain(Object token, Long2ObjectMap<PalettedContainer<BlockState>> sections,
            LongSet chunks, long geometryVersion) {
        Long2LongOpenHashMap fingerprints = new Long2LongOpenHashMap(sections.size());
        for (var entry : sections.long2ObjectEntrySet()) {
            fingerprints.put(entry.getLongKey(), AcousticPaletteCache.fingerprint(entry.getValue()));
        }
        registerTerrainIdentity(token, fingerprints, chunks, geometryVersion);
    }

    public static void registerTerrainIdentity(Object token, Long2LongMap fingerprints,
            LongSet chunks, long geometryVersion) {
        long identity = contentHash(fingerprints, chunks) ^ geometryVersion;
        synchronized (AcousticUpdateGate.class) { TERRAIN.put(token, identity); }
    }

    /**
     * CRC32C over the sorted chunk keys, then the sorted (section, fingerprint) pairs, big-endian:
     * independent of hash-set iteration order, and of how the caller collected them.
     */
    public static long contentHash(Long2LongMap fingerprints, LongSet chunks) {
        CRC32C hash = new CRC32C();
        ByteBuffer buffer = ByteBuffer.allocate(2 * Long.BYTES);
        long[] sortedChunks = chunks.toLongArray();
        Arrays.sort(sortedChunks);
        for (long chunk : sortedChunks) {
            hash.update(buffer.clear().putLong(chunk).flip());
        }
        long[] sections = fingerprints.keySet().toLongArray();
        Arrays.sort(sections);
        for (long section : sections) {
            hash.update(buffer.clear().putLong(section).putLong(fingerprints.get(section)).flip());
        }
        return hash.getValue();
    }

    public static synchronized void registerSnapshot(Object snapshot, Object terrain, List<?> blocks,
            List<UUID> ids, List<Pose3d> poses) {
        List<Body> bodies = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            bodies.add(new Body(ids.get(i), TERRAIN.getOrDefault(blocks.get(i), -1L), poses.get(i)));
        }
        bodies.sort(Comparator.comparing(Body::id));
        SCENES.put(snapshot, new Scene(TERRAIN.getOrDefault(terrain, -1L), List.copyOf(bodies)));
    }

    public static synchronized void copySnapshot(Object from, Object to) {
        Scene scene = SCENES.get(from);
        if (scene != null) SCENES.put(to, scene);
    }

    public static synchronized boolean shouldSimulate(Object owner, Object snapshot, Vec3 source,
            Vec3 listener, boolean reflections) {
        Scene scene = SCENES.get(snapshot);
        if (scene == null) return true;
        State state = STATES.computeIfAbsent(owner, key -> new State());
        Input previous = reflections ? state.reflections : state.direct;
        if (previous != null && source.distanceToSqr(previous.source) < 0.05 * 0.05
                && listener.distanceToSqr(previous.listener) < 0.05 * 0.05 && sameScene(previous.scene, scene)) {
            return false;
        }
        Input next = new Input(scene, source, listener);
        if (reflections) state.reflections = next;
        else state.direct = next;
        return true;
    }

    private static boolean sameScene(Scene a, Scene b) {
        if (!sameContent(a, b)) return false;
        for (int i = 0; i < a.bodies.size(); i++) {
            if (!samePose(a.bodies.get(i).pose, b.bodies.get(i).pose)) return false;
        }
        return true;
    }

    /** Same terrain and the same structures with the same blocks, wherever they are now. */
    private static boolean sameContent(Scene a, Scene b) {
        if (a.terrain != b.terrain || a.bodies.size() != b.bodies.size()) return false;
        for (int i = 0; i < a.bodies.size(); i++) {
            Body left = a.bodies.get(i), right = b.bodies.get(i);
            if (!left.id.equals(right.id) || left.geometry != right.geometry) return false;
        }
        return true;
    }

    /** Structure motion below 1 cm and 0.1 degrees is jitter, not a new scene. */
    public static boolean samePose(Pose3dc p, Pose3dc q) {
        return p.position().distanceSquared(q.position()) <= 0.01 * 0.01
                && p.rotationPoint().distanceSquared(q.rotationPoint()) <= 0.01 * 0.01
                && p.scale().distanceSquared(q.scale()) <= 1e-8
                && Math.abs(p.orientation().dot(q.orientation())) >= Math.cos(Math.toRadians(0.1) / 2);
    }

    /**
     * Block content changed (terrain, a structure's blocks, a structure arriving or leaving) since
     * the owner's last simulation: worth simulating before the motion cadence comes round. Motion
     * alone, the listener's or a structure's, waits for that cadence; {@link #shouldSimulate} still
     * sees it, so a moving train is re-simulated at the regular rate instead of back to back.
     */
    public static synchronized boolean geometryChanged(Object owner, Object snapshot, boolean reflections) {
        Scene scene = SCENES.get(snapshot);
        State state = STATES.get(owner);
        Input previous = state == null ? null : reflections ? state.reflections : state.direct;
        return scene == null || previous == null || !sameContent(previous.scene, scene);
    }

    public static synchronized void forget(Object owner) { STATES.remove(owner); }

    public static synchronized void invalidateReflections(Object owner) {
        State state = STATES.get(owner);
        if (state != null) state.reflections = null;
    }

    private AcousticUpdateGate() { }
}
