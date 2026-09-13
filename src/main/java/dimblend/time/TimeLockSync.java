package dimblend.time;

import dimblend.DimBlendRegistries;
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
 * Server side of the per-band time lock. Each player in the rotating
 * dimension is told the {@link TimeLockTarget} of the band they stand in;
 * the client applies it visually (see ClientLevelMixin). Only changes are
 * sent, so this is silent while a player stays in one band.
 */
public final class TimeLockSync {
    private final Map<UUID, SentLock> sent = new HashMap<>();

    private record SentLock(int modeId, long time) {
        static SentLock of(TimeLockTarget target) {
            return new SentLock(target.mode().ordinal(), target.time());
        }
    }

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
        SentLock lock = SentLock.of(targetAt(level, player.getBlockX()));
        SentLock previous = this.sent.put(player.getUUID(), lock);
        if (lock.equals(previous)) {
            return;
        }
        player.connection.send(new ClientboundCustomPayloadPacket(new TimeLockPayload(lock.modeId(), lock.time())));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        this.sent.remove(event.getEntity().getUUID());
    }

    private static TimeLockTarget targetAt(ServerLevel level, int blockX) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return TimeLockTarget.NONE;
        }
        int delegate = rotating.delegateIndexForBlockX(blockX);
        return TimeLockTarget.fromLane(BandLayout.laneName(rotating.delegates().get(delegate)));
    }
}
