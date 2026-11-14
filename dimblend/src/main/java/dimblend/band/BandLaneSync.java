package dimblend.band;

import dimblend.DimBlendRegistries;
import dimblend.api.RegionOverrides;
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
 * seeded outcome. Creative players are the exception: they get the real lane
 * name of a random-drawn neighbor (build/debug view wants the spoiler), and the
 * dedupe key carries the creative flag so an in-place gamemode switch re-sends
 * and neither leaks the name into survival nor leaves a stale "未知" in
 * region. The client cannot derive lanes itself — band assignment for random
 * regions depends on the world seed, which is never synced — hence this push.
 * Sent only when the player's region, creative flag, or the override state of
 * the region/its outward neighbor changes (lane and next-lane are pure
 * functions of those), so this is silent while a player stays in one band in
 * one gamemode. A region forced to a barren End by the dragon chase is named
 * 「末地」 ahead even for survival players: the forced lane is public knowledge,
 * not the seeded draw it replaced.
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
        boolean creative = player.isCreative();
        // 去重 key 带创造位：原地切模式要重发，否则创造切生存会把真实区域名留在 HUD 上。
        // 也带“本区/前方区是否被强制改道”位：追逐中下一区被写成荒岛末地时玩家可能原地不动，
        // key 不变就不重发，「前方到达」会一直停在「未知」。
        int next = region + (region < 0 ? -1 : 1);
        int key = pushKey(region, creative,
                RegionOverrides.isBarren(region), RegionOverrides.isBarren(next));
        Integer previous = this.sent.put(player.getUUID(), key);
        if (previous != null && previous == key) {
            return;
        }
        String lane = laneAt(level, player.getBlockX());
        player.connection.send(new ClientboundCustomPayloadPacket(
                new BandLanePayload(lane, nextLaneAt(rotating, region, creative))));
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

    /**
     * Dedupe key: region, creative flag and the override state of the region and its outward
     * neighbor. Both flags are needed: an override written while a player already stands in the
     * previous region must re-send, and clearing it must re-send back to "未知".
     * Injective for realistic region indexes (region * 2 already multiplies).
     */
    static int pushKey(int region, boolean creative, boolean barrenHere, boolean barrenNext) {
        return BandTrainMath.lanePushKey(region, creative) * 4
                + (barrenHere ? 1 : 0)
                + (barrenNext ? 2 : 0);
    }

    /**
     * Outward-neighbor lane: scripted neighbors give their name; a random draw
     * gives "" for survival/adventure (HUD 显示「未知」, 不剧透抽选结果) and the
     * real lane name for creative players. A forced barren End region counts as
     * known ({@link BandLayout#scriptedOutwardLaneName} returns "end"), because the
     * chase must announce 「末地」 ahead.
     */
    private static String nextLaneAt(RotatingChunkGenerator rotating, int region, boolean creative) {
        BandLayout layout = rotating.layoutOrNull();
        if (layout == null) {
            return "";
        }
        String lane = layout.scriptedOutwardLaneName(region);
        if (lane != null) {
            return lane;
        }
        if (!creative) {
            return "";
        }
        // 创造模式放开剧透：下一格按“离出生点更远一格”解析出真实区域名。
        int next = region + (region < 0 ? -1 : 1);
        return BandLayout.laneName(layout.delegate(next));
    }
}
