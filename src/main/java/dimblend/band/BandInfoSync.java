package dimblend.band;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.RotatingChunkGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Server side of the band progress HUD: each player in the rotating dimension
 * is told the dimension's band size. The client cannot read the band size
 * itself (the client chunk cache holds no ChunkGenerator), hence this sync.
 * Only changes are sent, so this is silent after the first tick in the
 * dimension.
 */
public final class BandInfoSync {
    private final Map<UUID, Integer> sent = new HashMap<>();

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerLevel level = player.serverLevel();
        if (!level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL)) {
            this.sent.remove(player.getUUID());
            return;
        }
        int bandSize = bandSizeOf(level);
        Integer previous = this.sent.put(player.getUUID(), bandSize);
        if (previous != null && previous == bandSize) {
            return;
        }
        player.connection.send(new ClientboundCustomPayloadPacket(new BandInfoPayload(bandSize)));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        this.sent.remove(event.getEntity().getUUID());
    }

    private static int bandSizeOf(ServerLevel level) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        return generator instanceof RotatingChunkGenerator rotating ? rotating.bandSize() : 0;
    }
}
