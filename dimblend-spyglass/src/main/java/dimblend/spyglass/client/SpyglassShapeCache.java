package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassShapePayload;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureOutline;
import dimblend.spyglass.structure.StructureShape;
import dimblend.spyglass.structure.StructureShapeCodec;
import io.netty.handler.codec.DecoderException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client cache of the structures' real block shapes, reassembled from the streamed shape parts.
 *
 * <p>Data only: this class never meshes or draws. It keeps the newest revision per structure key and
 * is bounded by the number of cached blocks, so a long session with many structures cannot grow
 * memory without bound. Over budget, shapes that are <b>not</b> in the latest snapshot are evicted
 * (least recently used first); a shape of the current snapshot is never evicted to make room for
 * another one — if the snapshot's own shapes already exceed the budget, the arriving shape is
 * rejected and its structure is not shown at all (there is no wireframe fallback). Receiving the same
 * key and revision again only
 * refreshes the LRU position. Cleared wherever {@link SpyglassClientState} clears its snapshot
 * (dimension change, disconnect, world switch); a shape for another dimension is ignored.
 *
 * <p>Threading: {@link #receive} and {@link #shapeFor} run on the client main thread. The cached
 * {@link StructureShape} objects are immutable, so stage 2 may hand one to a worker thread and mesh
 * it there; the map itself must not be read off-thread.
 */
public final class SpyglassShapeCache {
    /** Total kept blocks the cache may hold; the client equivalent of the server's block cap. */
    public static final int MAX_CACHED_BLOCKS = 4_000_000;

    private static final ShapePartAssembler ASSEMBLER = new ShapePartAssembler();
    private static final Map<StructureKey, StructureShape> SHAPES =
            new LinkedHashMap<>(16, 0.75F, true);
    private static int cachedBlocks;
    private static boolean malformedLogged;

    private SpyglassShapeCache() {
    }

    /** Payload handler; installed by {@link SpyglassClientSetup}. */
    public static void receive(SpyglassShapePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }
        ResourceLocation dimension = minecraft.level.dimension().location();
        if (!dimension.equals(payload.dimension())) {
            // A shape of the dimension we left; the snapshot handler clears on the same event.
            return;
        }
        ShapePartAssembler.AssembledBody body = ASSEMBLER.accept(payload);
        if (body == null) {
            return;
        }
        StructureShape shape;
        try {
            shape = decode(minecraft, body);
        } catch (RuntimeException exception) {
            if (!malformedLogged) {
                malformedLogged = true;
                DimBlendSpyglass.LOGGER.warn(
                        "Ignoring a malformed spyglass structure shape for {}: {}",
                        body.key(),
                        exception.getMessage());
            }
            return;
        }
        if (shape == null) {
            return;
        }
        store(shape);
    }

    /**
     * The shape of this outline, if the server has streamed one and it decodes.
     *
     * <p>Stage 2 links an outline to its shape with this method: outlines and shapes both carry the
     * same {@link StructureKey}, so the mesh of exactly the start the outline describes is drawn.
     */
    public static Optional<StructureShape> shapeFor(StructureOutline outline) {
        return Optional.ofNullable(SHAPES.get(outline.key()));
    }

    public static void clear() {
        SHAPES.clear();
        cachedBlocks = 0;
        ASSEMBLER.clear();
    }

    /** Total blocks in the cache, for the debug overlay and tests. */
    public static int cachedBlocks() {
        return cachedBlocks;
    }

    static int cachedShapeCount() {
        return SHAPES.size();
    }

    private static StructureShape decode(Minecraft minecraft, ShapePartAssembler.AssembledBody body) {
        StructureShapeCodec.DecodedShape decoded = StructureShapeCodec.decode(body.body());
        BlockState[] palette = new BlockState[decoded.paletteIds().length];
        for (int i = 0; i < palette.length; i++) {
            BlockState state = Block.stateById(decoded.paletteIds()[i]);
            if (state == null) {
                throw new DecoderException("Unknown block state id " + decoded.paletteIds()[i]);
            }
            palette[i] = state;
        }
        Optional<Holder<Biome>> biome = minecraft.level.registryAccess()
                .registryOrThrow(Registries.BIOME)
                .getHolder(ResourceKey.create(Registries.BIOME, decoded.biome()))
                .map(holder -> (Holder<Biome>) holder);
        return StructureShape.of(body.key(), body.revision(), decoded, palette, biome);
    }

    private static void store(StructureShape shape) {
        store(shape, MAX_CACHED_BLOCKS, SpyglassClientState::isInCurrentSnapshot);
    }

    /**
     * Package-private seam: {@code budget} is the block budget and {@code inSnapshot} decides which
     * cached keys must not be evicted. Production uses {@link #MAX_CACHED_BLOCKS} and the latest
     * client snapshot; tests drive the eviction policy with small budgets.
     */
    static void store(StructureShape shape, int budget, Predicate<StructureKey> inSnapshot) {
        StructureShape previous = SHAPES.get(shape.key());
        if (previous != null) {
            if (previous.revision() == shape.revision()) {
                // Same shape again: the lookup above already refreshed its LRU position, and
                // re-encoding an equal revision carries the same blocks.
                return;
            }
            if (previous.revision() > shape.revision()) {
                // Newest revision wins; this one arrived late.
                return;
            }
            cachedBlocks -= previous.blockCount();
        }
        SHAPES.put(shape.key(), shape);
        cachedBlocks += shape.blockCount();
        Iterator<Map.Entry<StructureKey, StructureShape>> eldest = SHAPES.entrySet().iterator();
        while (cachedBlocks > budget && eldest.hasNext()) {
            Map.Entry<StructureKey, StructureShape> entry = eldest.next();
            if (inSnapshot.test(entry.getKey())) {
                // Never evict a current-snapshot shape to make room for another one: the server
                // keeps both, so the two would evict each other on every refresh.
                continue;
            }
            eldest.remove();
            cachedBlocks -= entry.getValue().blockCount();
        }
        if (cachedBlocks > budget && SHAPES.get(shape.key()) == shape) {
            // Everything left is part of the current snapshot: reject the newcomer so its structure
            // stays unshown instead of thrashing the snapshot's shapes out of the cache.
            SHAPES.remove(shape.key());
            cachedBlocks -= shape.blockCount();
            DimBlendSpyglass.LOGGER.debug(
                    "Spyglass shape cache is full of current-snapshot shapes; structure {} is not shown",
                    shape.key());
        }
    }
}
