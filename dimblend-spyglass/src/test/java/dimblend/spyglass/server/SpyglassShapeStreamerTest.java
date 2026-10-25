package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.net.SpyglassShapePayload;
import dimblend.spyglass.structure.StructureKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

/**
 * Exercises the "client already has this shape" bookkeeping through the package-private streaming
 * seam: the budget loop runs unchanged, only the packet sink is captured instead of sent.
 */
class SpyglassShapeStreamerTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "house");

    @Test
    void forgetsShapesThatLeftTheSnapshotAndResendsThemOnReEntry() {
        SpyglassShapeStreamer streamer = new SpyglassShapeStreamer();
        EncodedStructureShape house = shape(0);
        EncodedStructureShape tower = shape(4);

        assertEquals(List.of(house.key()), drain(streamer, house));
        // The same snapshot again: the client still has the shape, so nothing is sent.
        assertTrue(drain(streamer, house).isEmpty());
        // House leaves the ring; tower is streamed. The forgotten key is sent again when the
        // structure comes back, which is what the client needs after an eviction.
        assertEquals(List.of(tower.key()), drain(streamer, tower));
        assertEquals(List.of(house.key()), drain(streamer, house));
    }

    @Test
    void aNewSnapshotReleasesPendingShapesThatLeftIt() {
        SpyglassShapeStreamer streamer = new SpyglassShapeStreamer();
        EncodedStructureShape house = shape(0);
        EncodedStructureShape tower = shape(4);

        // Both are queued but not sent before the next snapshot drops the house.
        streamer.onSnapshot(PLAYER, Level.OVERWORLD, List.of(house, tower));

        assertEquals(List.of(tower.key()), drain(streamer, tower));
    }

    @Test
    void aNewRevisionIsSentAgain() {
        SpyglassShapeStreamer streamer = new SpyglassShapeStreamer();
        EncodedStructureShape house = shape(0);

        assertEquals(List.of(house.key()), drain(streamer, house));
        EncodedStructureShape resaved = shape(0, house.revision() + 1);

        assertEquals(List.of(resaved.key()), drain(streamer, resaved));
    }

    /** Sends a snapshot and drains one tick of streaming, returning the keys that went out. */
    private static List<StructureKey> drain(
            SpyglassShapeStreamer streamer, EncodedStructureShape... shapes) {
        streamer.onSnapshot(PLAYER, Level.OVERWORLD, List.of(shapes));
        List<SpyglassShapePayload> sent = new ArrayList<>();
        streamer.streamForTest(PLAYER, sent::add);
        return sent.stream().map(SpyglassShapePayload::key).toList();
    }

    private static EncodedStructureShape shape(int startChunkX) {
        return shape(startChunkX, 1);
    }

    private static EncodedStructureShape shape(int startChunkX, int revision) {
        byte[] body = new byte[32];
        Arrays.fill(body, (byte) 7);
        return EncodedStructureShape.split(
                new StructureKey(ID, startChunkX, 0), revision, 1, body);
    }
}
