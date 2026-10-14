package dimblend.experience.compat.simurail;

import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeadmanLatchPayloadTest {
    private static final UUID FIRST = new UUID(0, 1);
    private static final UUID SECOND = new UUID(0, 2);

    @Test
    void snapshotsRoundTripIncludingEmptyResetAndOtherDimensions() {
        for (String dimension : List.of("rotating", "other")) {
            var dim = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.fromNamespaceAndPath("dimblend", dimension));
            for (List<UUID> carriages : List.of(List.of(FIRST, SECOND), List.<UUID>of())) {
                var original = new DeadmanLatchPayload(dim, carriages);
                var buffer = Unpooled.buffer();
                try {
                    DeadmanLatchPayload.STREAM_CODEC.encode(buffer, original);
                    assertEquals(original, DeadmanLatchPayload.STREAM_CODEC.decode(buffer));
                    assertEquals(0, buffer.readableBytes());
                } finally {
                    buffer.release();
                }
            }
        }
    }

    @Test
    void clientSnapshotReplacementRemovesOnlyReleasedCarriagesAndDoesNotAliasInput() {
        var dim = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("dimblend", "rotating"));
        var other = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath("dimblend", "other"));
        ClientDeadmanLatch.reset();
        try {
            var ids = new ArrayList<>(List.of(FIRST, SECOND));
            var payload = new DeadmanLatchPayload(dim, ids);
            ClientDeadmanLatch.set(dim, payload.carriages());
            ClientDeadmanLatch.set(other, List.of(FIRST));
            ids.clear();
            assertTrue(ClientDeadmanLatch.isLatched(dim, FIRST));
            assertTrue(ClientDeadmanLatch.isLatched(dim, SECOND));
            ClientDeadmanLatch.set(dim, List.of(SECOND));
            assertFalse(ClientDeadmanLatch.isLatched(dim, FIRST));
            assertTrue(ClientDeadmanLatch.isLatched(dim, SECOND));
            ClientDeadmanLatch.set(dim, List.of());
            assertFalse(ClientDeadmanLatch.isLatched(dim, SECOND));
            assertTrue(ClientDeadmanLatch.isLatched(other, FIRST));
            ClientDeadmanLatch.reset();
            assertFalse(ClientDeadmanLatch.isLatched(other, FIRST));
        } finally {
            ClientDeadmanLatch.reset();
        }
    }
}
