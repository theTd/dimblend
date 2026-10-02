package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3d;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.zip.CRC32C;
import net.minecraft.network.FriendlyByteBuf;
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

    public static void registerTerrain(Object token, Map<Long, PalettedContainer<BlockState>> sections, Set<Long> chunks) {
        registerTerrain(token, sections, chunks, 0L);
    }

    /**
     * {@code geometryVersion} folds the render-mesh mirror's content version into the terrain
     * identity. Sodium rebuilds land asynchronously a frame or two after a block change, so the
     * palette hash alone would re-simulate once against stale mirror geometry and then settle;
     * the version fold fires the gate again when the rebuilt section actually arrives.
     */
    public static void registerTerrain(Object token, Map<Long, PalettedContainer<BlockState>> sections,
            Set<Long> chunks, long geometryVersion) {
        CRC32C hash = new CRC32C();
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            for (long chunk : chunks.stream().sorted().toList()) {
                buffer.clear();
                buffer.writeLong(chunk);
                hash.update(buffer.nioBuffer());
            }
            for (var entry : sections.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                buffer.clear();
                buffer.writeLong(entry.getKey());
                entry.getValue().write(buffer);
                hash.update(buffer.nioBuffer());
            }
        } finally { buffer.release(); }
        synchronized (AcousticUpdateGate.class) { TERRAIN.put(token, hash.getValue() ^ geometryVersion); }
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
        if (a.terrain != b.terrain || a.bodies.size() != b.bodies.size()) return false;
        for (int i = 0; i < a.bodies.size(); i++) {
            Body left = a.bodies.get(i), right = b.bodies.get(i);
            if (!left.id.equals(right.id) || left.geometry != right.geometry) return false;
            Pose3d p = left.pose, q = right.pose;
            if (p.position().distanceSquared(q.position()) > 0.01 * 0.01
                    || p.rotationPoint().distanceSquared(q.rotationPoint()) > 0.01 * 0.01
                    || p.scale().distanceSquared(q.scale()) > 1e-8
                    || Math.abs(p.orientation().dot(q.orientation())) < Math.cos(Math.toRadians(0.1) / 2)) return false;
        }
        return true;
    }

    public static synchronized void forget(Object owner) { STATES.remove(owner); }

    private AcousticUpdateGate() { }
}
