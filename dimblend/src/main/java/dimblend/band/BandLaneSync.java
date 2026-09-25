package dimblend.band;

import dimblend.DimBlendRegistries;
import dimblend.weather.ServerGlobalWeatherLock;
import dimblend.worldgen.BandLayout;
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
 * Server side of the per-player band-lane push: each player in the rotating
 * dimension is told the {@link BandLayout#laneName} of the band they stand in.
 * The client cannot derive the lane itself — band assignment for random
 * regions depends on the world seed, which is never synced — hence this sync.
 * Only changes are sent, so this is silent while a player stays in one band.
 */
public final class BandLaneSync {
    private final Map<UUID, String> sent = new HashMap<>();

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
        String lane = laneAt(level, player.getBlockX());
        ServerGlobalWeatherLock.enforce(level, lane);
        String previous = this.sent.put(player.getUUID(), lane);
        if (lane.equals(previous)) {
            return;
        }
        player.connection.send(new ClientboundCustomPayloadPacket(new BandLanePayload(lane)));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        this.sent.remove(event.getEntity().getUUID());
    }

    private static String laneAt(ServerLevel level, int blockX) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return "unknown";
        }
        int delegate = rotating.delegateIndexForBlockX(blockX);
        return BandLayout.laneName(rotating.delegates().get(delegate));
    }
}
