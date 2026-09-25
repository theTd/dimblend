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
 * the client applies it visually (see ClientLevelMixin). Only mode/pin
 * changes are sent, so this is silent while a player stays in one band.
 * Unlock packets carry the live world day time (not the NONE sentinel 0)
 * so the client can snap back to the clock that kept running under the lock.
 */
public final class TimeLockSync {
    private final Map<UUID, TimeLockTarget> sent = new HashMap<>();

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
        TimeLockTarget target = targetAt(level, player.getBlockX());
        TimeLockTarget previous = this.sent.put(player.getUUID(), target);
        if (target.equals(previous)) {
            return;
        }
        // PrimaryLevelData: Level.getDayTime is mixed to the column lock.
        Wire wire = Wire.of(target, level.getLevelData().getDayTime());
        player.connection.send(new ClientboundCustomPayloadPacket(
                new TimeLockPayload(wire.modeId(), wire.time())));
    }

    /**
     * Packet body for a lock change. Change detection uses {@link TimeLockTarget}
     * equality ({@code NONE.time() == 0}), not this payload time, so surface
     * bands do not resend every tick as the world clock advances.
     */
    record Wire(int modeId, long time) {
        static Wire of(TimeLockTarget target, long worldDayTime) {
            return new Wire(target.mode().ordinal(), target.payloadTime(worldDayTime));
        }
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
