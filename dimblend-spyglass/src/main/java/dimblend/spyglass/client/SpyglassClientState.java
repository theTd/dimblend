package dimblend.spyglass.client;

import dimblend.spyglass.DimBlendSpyglass;
import dimblend.spyglass.net.SpyglassSnapshotPayload;
import dimblend.spyglass.structure.SpyglassDistance;
import dimblend.spyglass.structure.StructureKey;
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
 * filters it down to the exact display range around the player's <b>live</b> position and the
 * <b>live</b> reveal distance on every frame.
 *
 * <p>The reveal distance is the client's current terrain fog start ({@link SpyglassRevealState}). While
 * it is unknown (before the first terrain fog pass after joining a level) or disabled (underwater, in
 * lava, blind...), the ring is empty: nothing is drawn and no labels are shown.
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class SpyglassClientState {
    private static ResourceLocation dimension;
    private static long sequence;
    private static List<StructureOutline> structures = List.of();
    /** Memo of the last {@link #currentStructures()} filter; both level passes use it per frame. */
    private static long memoFrame = Long.MIN_VALUE;
    private static List<StructureOutline> memoSnapshot = List.of();
    private static double memoPlayerX;
    private static double memoPlayerZ;
    private static double memoReveal;
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
     * <p>Both level passes need this list every frame, so the result is memoized on the frame stamp,
     * the snapshot identity, the live player position and the reveal distance.
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
        double reveal = revealEnabled ? SpyglassRevealState.revealDistanceBlocks() : 0.0;
        long frame = minecraft.getFrameTimeNs();
        if (frame == memoFrame
                && structures == memoSnapshot
                && playerX == memoPlayerX
                && playerZ == memoPlayerZ
                && reveal == memoReveal
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
                            SpyglassDistance.horizontal(playerX, playerZ, structure),
                            SpyglassDistance.farthestHorizontal(playerX, playerZ, structure),
                            reveal))
                    .toList();
        }
        memoFrame = frame;
        memoSnapshot = structures;
        memoPlayerX = playerX;
        memoPlayerZ = playerZ;
        memoReveal = reveal;
        memoRevealEnabled = revealEnabled;
        memoStructures = visible;
        return visible;
    }

    /**
     * True when this key belongs to the latest snapshot — the padded ring, not the exact display
     * ring. The client shape cache uses it to keep snapshot shapes from evicting each other.
     */
    static boolean isInCurrentSnapshot(StructureKey key) {
        for (int i = 0; i < structures.size(); i++) {
            if (structures.get(i).key().equals(key)) {
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        dimension = null;
        sequence = 0L;
        structures = List.of();
        memoFrame = Long.MIN_VALUE;
        memoSnapshot = List.of();
        memoStructures = List.of();
        memoReveal = 0.0;
        memoRevealEnabled = false;
        // The reveal distance is per level: forget the observed value, so nothing is drawn until the
        // first terrain fog pass of the new level (the sender notices the new level by itself).
        SpyglassRevealState.reset();
        // Shapes are cached per dimension and keyed by structure; they must not survive a level,
        // dimension or connection change either. This is the single clear point used by the renderer,
        // the logout event and the level unload event. Their meshes go with them (GL buffers are
        // deleted on the render thread; ShapeMeshStore defers if called elsewhere).
        SpyglassShapeCache.clear();
        ShapeMeshStore.clear();
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
