package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassRevealPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Tells the server the client's reveal distance (terrain fog start) so the snapshot ring can start at
 * the fog instead of a fixed 256 blocks.
 *
 * <p>The value is quantized down to {@value #QUANTUM_BLOCKS} blocks so small fog wobble does not
 * produce packets, and is sent only when the quantized value differs from the last one sent, at most
 * once per {@value #SEND_INTERVAL_TICKS} client ticks. The first send after a level change is
 * immediate: a new {@link Level} (join, dimension change) resets the "last sent" memo in the tick
 * handler itself, on the same thread that reads it, so a fresh value goes out instead of the server
 * keeping the previous level's value.
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class SpyglassRevealSender {
    /** At most one reveal packet per second; the server refresh only reacts to 16-block changes. */
    static final int SEND_INTERVAL_TICKS = 20;
    /** Quantization step in blocks; see the server's reveal refresh threshold. */
    static final int QUANTUM_BLOCKS = 8;
    /** No value has been sent in this level yet. */
    private static final int NEVER_SENT = Integer.MIN_VALUE;

    private static int lastSentBlocks = NEVER_SENT;
    private static int ticksSinceSend;
    /** Client thread only: detects a level change without touching the level-derived caches. */
    private static Level currentLevel;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || minecraft.getConnection() == null) {
            currentLevel = null;
            reset();
            return;
        }
        if (minecraft.level != currentLevel) {
            currentLevel = minecraft.level;
            reset();
        }
        ticksSinceSend++;
        if (!SpyglassRevealState.isEnabled()) {
            return;
        }
        if (lastSentBlocks != NEVER_SENT && ticksSinceSend < SEND_INTERVAL_TICKS) {
            return;
        }
        int blocks = quantize(SpyglassRevealState.revealDistanceBlocks());
        if (blocks == lastSentBlocks) {
            return;
        }
        PacketDistributor.sendToServer(new SpyglassRevealPayload(blocks));
        lastSentBlocks = blocks;
        ticksSinceSend = 0;
    }

    /** Rounds the fog start down to the quantum; the server pads the rest. */
    static int quantize(double fogStartBlocks) {
        return (int) (Math.floor(fogStartBlocks / QUANTUM_BLOCKS) * QUANTUM_BLOCKS);
    }

    /** Drops the "last sent" memo so the next tick sends the new level's value. */
    static void reset() {
        lastSentBlocks = NEVER_SENT;
        ticksSinceSend = 0;
    }

    private SpyglassRevealSender() {
    }
}
