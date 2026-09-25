package dimblend.worldgen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Per-lane corridor track material (分纬度轨道材质), the north-star spec in
 * docs/generation-rules.md "三、分纬度规则":
 *
 * <ul>
 *   <li>地表 / 地下：桦木 / 深色橡木宽轨，有路基</li>
 *   <li>下界：黑石宽轨，无路基；末地：幻纱宽轨，无路基</li>
 *   <li>暮色：标准宽轨，有路基；星光 / 深暗：标准宽轨，无路基</li>
 *   <li>天域 / 深渊：无枕木宽轨，无路基</li>
 * </ul>
 *
 * <p>The roadbed flag is a per-lane property, not a per-material one: the
 * standard-gauge andesite track carries a roadbed in Twilight bands but none
 * in Starlight/Otherside bands. Unknown modded lanes fall back to the
 * standard track without a roadbed.
 *
 * <p>Classification reuses {@link BandLayout}'s public lane primitives
 * (slice / isTwilight / isVoidscape / laneName) so profile lookup always
 * agrees with the layout, the boundary walls and the time lock.
 */
public enum CorridorTrackProfile {
    SURFACE(wide("track_birch_wide"), true),
    UNDERGROUND(wide("track_dark_oak_wide"), true),
    NETHER(wide("track_blackstone_wide"), false),
    END(wide("track_phantom_wide"), false),
    TWILIGHT(wide("track_create_andesite_wide"), true),
    STARLIGHT(wide("track_create_andesite_wide"), false),
    OTHERSIDE(wide("track_create_andesite_wide"), false),
    AETHER(wide("track_tieless_wide"), false),
    VOIDSCAPE(wide("track_tieless_wide"), false),
    MOD(wide("track_create_andesite_wide"), false);

    private final ResourceLocation trackId;
    private final boolean roadbed;

    CorridorTrackProfile(ResourceLocation trackId, boolean roadbed) {
        this.trackId = trackId;
        this.roadbed = roadbed;
    }

    public ResourceLocation trackId() {
        return this.trackId;
    }

    /** True when the lane writes the cobblestone roadbed row under the track. */
    public boolean hasRoadbed() {
        return this.roadbed;
    }

    /** Wide-gauge track id inside the railways (Steam 'n' Rails) namespace. */
    private static ResourceLocation wide(String path) {
        return ResourceLocation.fromNamespaceAndPath("railways", path);
    }

    /**
     * Profile for one corridor column's delegate generator. Modded lanes are
     * told apart by {@link BandLayout#laneName}'s stable names: starlight is a
     * dedicated name, Otherside/Aether/Voidscape surface as their identity
     * namespaces (deeperdarker / aether / voidscape).
     */
    public static CorridorTrackProfile forDelegate(ChunkGenerator delegate) {
        if (delegate instanceof SlicedOverworldChunkGenerator sliced) {
            return sliced.slice() == OverworldSlice.SURFACE ? SURFACE : UNDERGROUND;
        }
        if (BandLayout.isTwilight(delegate)) {
            return TWILIGHT;
        }
        if (BandLayout.isVoidscape(delegate)) {
            return VOIDSCAPE;
        }
        return switch (BandLayout.laneName(delegate)) {
            case "surface" -> SURFACE;
            case "underground" -> UNDERGROUND;
            case "nether" -> NETHER;
            case "end" -> END;
            case "starlight" -> STARLIGHT;
            case "deeperdarker" -> OTHERSIDE;
            case "aether" -> AETHER;
            default -> MOD;
        };
    }
}
