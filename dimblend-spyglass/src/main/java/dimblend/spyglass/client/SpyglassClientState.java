package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassBusyHandlers;
import dimblend.spyglass.net.SpyglassSnapshotPayload;
import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.spyglass.structure.StructureOutline;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Latest structure snapshot for the current client level.
 *
 * <p>Fed from {@link dimblend.spyglass.net.SpyglassSnapshotHandlers} — common code never references
 * this class; {@link SpyglassClientSetup} installs {@link #apply} on the client. The server sends a
 * padded ring (see {@link dimblend.spyglass.structure.SpyglassDistance#inSnapshotRange}); this class
 * filters it down to the exact display range around the player's <b>live</b> position on every frame.
 *
 * <p>The display ring is fixed at 64–1024 blocks. {@link SpyglassRevealState} still gates drawing:
 * while no terrain fog pass has run yet, or the camera is underwater/in lava/blind, the ring is
 * empty and nothing is drawn.
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class SpyglassClientState {
    private static ResourceLocation dimension;
    private static long sequence;
    private static List<StructureOutline> structures = List.of();
    /** Memo of the last {@link #currentStructures()} filter; the mark publisher uses it per frame. */
    private static long memoFrame = Long.MIN_VALUE;
    private static List<StructureOutline> memoSnapshot = List.of();
    private static double memoPlayerX;
    private static double memoPlayerZ;
    private static boolean memoRevealEnabled;
    private static List<StructureOutline> memoStructures = List.of();

    public static void apply(SpyglassSnapshotPayload payload) {
        DimBlendSpyglass.LOGGER.debug("Spyglass snapshot received dimension={} structures={} sequence={}",
                payload.dimension(), payload.structures().size(), payload.sequence());
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return;
        }

        ResourceLocation currentDimension = minecraft.level.dimension().location();
        if (dimension != null && !dimension.equals(currentDimension)) {
            clear();
        }
        if (!currentDimension.equals(payload.dimension())) {
            return;
        }
        if (dimension != null && payload.sequence() <= sequence) {
            return;
        }

        dimension = currentDimension;
        sequence = payload.sequence();
        structures = payload.structures();
    }

    /**
     * Structures inside the exact display range of the player's live position and reveal distance,
     * for the current frame. A structure is eligible when its union-bounds center is at most
     * {@link SpyglassDistance#MAX_BLOCKS} away and its farthest footprint corner is at least the
     * reveal distance away, i.e. at least part of it is past the fog. Callers must gate on
     * {@link SpyglassScopeGate#isScoping} themselves.
     *
     * <p>The mark publisher needs this list every frame, so the result is memoized on the frame
     * stamp, the snapshot identity, the live player position and the reveal distance.
     */
    public static List<StructureOutline> currentStructures() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            clear();
            return List.of();
        }
        ResourceLocation currentDimension = minecraft.level.dimension().location();
        if (dimension == null) {
            // No snapshot for this level yet (first frames after joining). Do not clear every frame:
            // that would also reset the reveal state and the "last sent" memo before the first
            // snapshot arrives.
            return List.of();
        }
        if (!dimension.equals(currentDimension)) {
            clear();
            return List.of();
        }

        boolean revealEnabled = SpyglassRevealState.isEnabled();
        double playerX = minecraft.player.getX();
        double playerZ = minecraft.player.getZ();
        long frame = minecraft.getFrameTimeNs();
        if (frame == memoFrame
                && structures == memoSnapshot
                && playerX == memoPlayerX
                && playerZ == memoPlayerZ
                && revealEnabled == memoRevealEnabled) {
            return memoStructures;
        }
        List<StructureOutline> visible;
        if (!revealEnabled) {
            // No terrain fog pass yet, or an effect/fluid fog: the spyglass shows nothing at all.
            visible = List.of();
        } else {
            visible = structures.stream()
                    .filter(structure -> SpyglassDistance.inRange(
                            SpyglassDistance.horizontal(playerX, playerZ, structure)))
                    .toList();
        }
        memoFrame = frame;
        memoSnapshot = structures;
        memoPlayerX = playerX;
        memoPlayerZ = playerZ;
        memoRevealEnabled = revealEnabled;
        memoStructures = visible;
        return visible;
    }

    public static void clear() {
        dimension = null;
        sequence = 0L;
        structures = List.of();
        memoFrame = Long.MIN_VALUE;
        memoSnapshot = List.of();
        memoStructures = List.of();
        memoRevealEnabled = false;
        // The fog state is per level: forget the observed values, so nothing is drawn until the first
        // terrain fog pass of the new level.
        SpyglassRevealState.reset();
        // A scan flag belongs to the level it was requested for; never let the measuring line survive
        // a dimension change or a disconnect.
        SpyglassBusyHandlers.reset();
        // A selection belongs to the level it was made in: never let it point into another dimension,
        // and never let a held mouse button survive a disconnect as an armed latch.
        StructureSelection.clear();
        MarkFrame.publish(null);
        MarkScreenFrame.reset();
        StructureMarkInput.reset();
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        // The integrated server also posts Unload for its own levels, on the server thread, typically
        // after the client already dropped its level (minecraft.level == null); those must not touch
        // the client caches.
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || event.getLevel() == minecraft.level) {
            clear();
        }
    }

    private SpyglassClientState() {
    }
}
