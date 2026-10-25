package dimblend.spyglass.server;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassShapePayload;
import dimblend.spyglass.structure.StructureKey;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Streams the shapes of the structures in the latest snapshot to each player, nearest first, within a
 * per-player byte budget.
 *
 * <p>The client is told which (structure key, revision) shapes it already has. A snapshot schedules
 * only the missing ones, so a structure whose shape is unchanged costs no bandwidth on the 30-second
 * refresh, while a re-saved structure (new region-header revision) is re-sent. Every new snapshot also
 * forgets the keys that are no longer part of it: the client's block-bounded cache may have evicted
 * such a shape, and only a forgotten key is sent again when the structure re-enters the ring. The
 * pending queue and the in-flight part are rebuilt from the snapshot, so the encoded shapes of keys
 * that left the snapshot are released with the old queue. Tracking resets on a dimension change, on
 * logout and on server stop; a player who stops scoping drops the pending queue (the known set is
 * kept, it is still accurate).
 *
 * <p>Only clients that registered the shape channel receive anything: NeoForge throws when a custom
 * payload is sent to a client that did not, so the channel is checked once per player and the state
 * is dropped if it is missing.
 *
 * <p>Runs on the tick thread. The shapes were encoded on the scan executor and are immutable, so
 * sending a part is a packet allocation and a write, no game state access.
 */
final class SpyglassShapeStreamer {
    /** Total encoded bytes a player may receive per tick over all shapes. */
    static final int BUDGET_BYTES_PER_TICK = SpyglassShapePayload.STREAM_BUDGET_BYTES_PER_TICK;
    /** Bound of remembered "client already has this shape" entries per player. */
    private static final int MAX_KNOWN_SHAPES = 8192;

    private final Map<UUID, ClientShapes> clients = new HashMap<>();

    /** Schedules the shapes of a freshly sent snapshot; {@code shapes} is nearest first. */
    void onSnapshot(UUID playerId, ResourceKey<Level> dimension, List<EncodedStructureShape> shapes) {
        ClientShapes state = this.clients.get(playerId);
        if (state == null) {
            if (shapes.isEmpty()) {
                return;
            }
            state = new ClientShapes(dimension);
            this.clients.put(playerId, state);
        } else if (!state.dimension.location().equals(dimension.location())) {
            state.reset(dimension);
        }
        // (F2a) Forget every shape that is no longer part of the snapshot. The client may have
        // evicted it to stay inside its block budget while its structure was still known here, so a
        // structure that leaves the ring and re-enters must be sent again. Replacing the pending
        // queue below also releases the encoded shapes of keys that left the snapshot (F3).
        if (!state.known.isEmpty()) {
            state.known.keySet().retainAll(keysOf(shapes));
        }
        state.pending.clear();
        state.sending = null;
        state.nextPart = 0;
        for (EncodedStructureShape shape : shapes) {
            Integer knownRevision = state.known.get(shape.key());
            if (knownRevision == null || knownRevision != shape.revision()) {
                state.pending.add(shape);
            }
        }
    }

    private static Set<StructureKey> keysOf(List<EncodedStructureShape> shapes) {
        Set<StructureKey> keys = new HashSet<>(Math.max(4, shapes.size()));
        for (EncodedStructureShape shape : shapes) {
            keys.add(shape.key());
        }
        return keys;
    }

    void dropPending(UUID playerId) {
        ClientShapes state = this.clients.get(playerId);
        if (state != null) {
            state.pending.clear();
            state.sending = null;
            state.nextPart = 0;
        }
    }

    /**
     * Forgets which shapes the client has. Used when the client cache was cleared while the
     * connection stayed: a respawn replaces the client level (and with it the cached shapes) without a
     * new login or dimension id, so the server has to stream them again.
     */
    void resetTracking(UUID playerId) {
        ClientShapes state = this.clients.get(playerId);
        if (state != null) {
            state.known.clear();
            state.pending.clear();
            state.sending = null;
            state.nextPart = 0;
        }
    }

    void remove(UUID playerId) {
        this.clients.remove(playerId);
    }

    void clear() {
        this.clients.clear();
    }

    /** Sends up to the budget for every player with pending shapes; cheap when nothing is queued. */
    void stream(MinecraftServer server) {
        if (this.clients.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ClientShapes state = this.clients.get(player.getUUID());
            if (state == null || (state.pending.isEmpty() && state.sending == null)) {
                continue;
            }
            if (!NetworkRegistry.hasChannel(player.connection, SpyglassShapePayload.TYPE.id())) {
                this.clients.remove(player.getUUID());
                DimBlendSpyglass.LOGGER.debug(
                        "Spyglass shape stream dropped for {}: client has no shape channel",
                        player.getUUID());
                continue;
            }
            try {
                streamPlayer(player, state);
            } catch (RuntimeException exception) {
                this.clients.remove(player.getUUID());
                DimBlendSpyglass.LOGGER.debug(
                        "Spyglass shape stream to {} failed; dropping its pending shapes",
                        player.getUUID());
            }
        }
    }

    private void streamPlayer(ServerPlayer player, ClientShapes state) {
        stream(state, payload -> PacketDistributor.sendToPlayer(player, payload));
    }

    /**
     * Package-private seam: runs one tick's streaming budget for {@code playerId} into {@code sink}
     * instead of the network, so the re-send and budget rules are testable without a connection.
     */
    void streamForTest(UUID playerId, Consumer<SpyglassShapePayload> sink) {
        ClientShapes state = this.clients.get(playerId);
        if (state != null) {
            stream(state, sink);
        }
    }

    private void stream(ClientShapes state, Consumer<SpyglassShapePayload> sink) {
        int budget = BUDGET_BYTES_PER_TICK;
        while (budget > 0) {
            EncodedStructureShape shape = state.sending;
            if (shape == null) {
                shape = state.pending.peek();
                if (shape == null) {
                    return;
                }
                // Nearest first: if the next part does not fit, stop instead of skipping ahead.
                if (shape.parts().get(0).length + SpyglassShapePayload.PART_HEADER_BYTES > budget) {
                    return;
                }
                state.sending = shape;
                state.nextPart = 0;
            }
            List<byte[]> parts = shape.parts();
            byte[] part = parts.get(state.nextPart);
            int bytes = part.length + SpyglassShapePayload.PART_HEADER_BYTES;
            if (bytes > budget) {
                return;
            }
            sink.accept(new SpyglassShapePayload(
                    state.dimension.location(),
                    shape.key(),
                    shape.revision(),
                    state.nextPart,
                    parts.size(),
                    part));
            budget -= bytes;
            state.nextPart++;
            if (state.nextPart >= parts.size()) {
                state.known.put(shape.key(), shape.revision());
                state.pending.poll();
                state.sending = null;
            }
        }
    }

    private static final class ClientShapes {
        private final Map<StructureKey, Integer> known =
                new LinkedHashMap<>(16, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<StructureKey, Integer> eldest) {
                        return size() > MAX_KNOWN_SHAPES;
                    }
                };
        private final Deque<EncodedStructureShape> pending = new ArrayDeque<>();
        private ResourceKey<Level> dimension;
        private @Nullable EncodedStructureShape sending;
        private int nextPart;

        private ClientShapes(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }

        private void reset(ResourceKey<Level> dimension) {
            this.dimension = dimension;
            this.known.clear();
            this.pending.clear();
            this.sending = null;
            this.nextPart = 0;
        }
    }
}
