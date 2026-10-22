package dimblend.band;

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
 * Server side of the per-player band-lane push: each player in the rotating
 * dimension is told the {@link BandLayout#laneName} of the band they stand in,
 * plus the outward neighbor's lane when that neighbor is scripted (fixed
 * 0–20/32–33 or the forced closer of a freshly opened surface run) — random
 * draws are pushed as "" so the HUD shows "未知" without ever leaking the
 * seeded outcome. The client cannot derive lanes itself — band assignment for
 * random regions depends on the world seed, which is never synced — hence this
 * push. Sent only when the player's region changes (lane and next-lane are pure
 * functions of the region), so this is silent while a player stays in one band.
 */
public final class BandLaneSync {
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
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        int region = BandLayout.regionOfBlockX(player.getBlockX(), rotating.bandSize());
        Integer previous = this.sent.put(player.getUUID(), region);
        if (previous != null && previous == region) {
            return;
        }
        String lane = laneAt(level, player.getBlockX());
        player.connection.send(new ClientboundCustomPayloadPacket(
                new BandLanePayload(lane, nextLaneAt(rotating, region))));
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        this.sent.remove(event.getEntity().getUUID());
    }

    /** Lane of the band owning {@code blockX}, same resolution as chunk generation. Shared with the weather lock. */
    public static String laneAt(ServerLevel level, int blockX) {
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return "unknown";
        }
        int delegate = rotating.delegateIndexForBlockX(blockX);
        return BandLayout.laneName(rotating.delegates().get(delegate));
    }

    /** Scripted outward-neighbor lane, or "" for random draws / generator not yet seeded. */
    private static String nextLaneAt(RotatingChunkGenerator rotating, int region) {
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return "";
        }
        String lane = layout.scriptedOutwardLaneName(region);
        return lane == null ? "" : lane;
    }
}
