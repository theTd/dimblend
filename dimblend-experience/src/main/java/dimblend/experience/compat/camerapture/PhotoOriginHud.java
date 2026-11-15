package dimblend.experience.compat.camerapture;

import dimblend.api.RegionLanes;
import dimblend.spyglass.api.HudWorldMarkers;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Draws {@code @} at the capture position of a picture the player is holding.
 *
 * <p>The marker rides the spyglass HUD projection at the same {@code 2x} font scale as its structure
 * marks, but it is not a structure mark: it has no display ring and cannot be selected. It is shown
 * only while at least one held picture's origin is in the <b>same rotating region</b> as the player
 * ({@link RegionLanes#sameRegion}); another dimension or another band draws nothing.
 *
 * <p>Loaded only after the spyglass, CameraCapture and core mods are all present; registration goes
 * through {@link HudWorldMarkers} in the {@code dimblend.spyglass.api} boundary.
 */
public final class PhotoOriginHud implements HudWorldMarkers.Provider {
    /**
     * {@code false}: the {@code @}-only marker, like a lowered spyglass mark. {@code true}: also
     * drive the off-screen edge arrow and the crosshair distance readout.
     */
    public static final boolean GUIDE_WITH_ARROW_AND_DISTANCE = false;

    /** The glyph at the capture position; ASCII so every shipped font draws it. */
    public static final String GLYPH = "@";

    private static final ResourceLocation PICTURE_ID =
            ResourceLocation.fromNamespaceAndPath("camerapture", "picture");
    private static final String MARKER_PREFIX = "dimblend_photo_origin_";
    private static final PhotoOriginHud INSTANCE = new PhotoOriginHud();

    /** Installs the provider once, from client setup behind the mod-presence gate. */
    public static void register() {
        HudWorldMarkers.register(INSTANCE);
    }

    @Override
    public List<HudWorldMarkers.Marker> markers() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null) {
            return List.of();
        }
        List<HudWorldMarkers.Marker> markers = new ArrayList<>(2);
        add(markers, level, player, InteractionHand.MAIN_HAND);
        add(markers, level, player, InteractionHand.OFF_HAND);
        return markers;
    }

    private static void add(
            List<HudWorldMarkers.Marker> markers,
            Level level,
            LocalPlayer player,
            InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (stack.isEmpty()
                || !BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(PICTURE_ID)) {
            return;
        }
        PhotoOriginData.Origin origin = PhotoOriginData.read(stack);
        if (origin == null) {
            return;
        }
        if (!RegionLanes.sameRegion(
                level,
                origin.dimension(),
                Mth.floor(origin.x()),
                Mth.floor(player.getX()))) {
            return;
        }
        markers.add(new HudWorldMarkers.Marker(
                MARKER_PREFIX + (hand == InteractionHand.MAIN_HAND ? "main_hand" : "off_hand"),
                GLYPH,
                origin.x(),
                origin.y(),
                origin.z(),
                GUIDE_WITH_ARROW_AND_DISTANCE));
    }

    private PhotoOriginHud() {
    }
}
