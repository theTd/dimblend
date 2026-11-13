package dimblend.worldgen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;

/**
 * Per-lane corridor track material (分纬度轨道材质), the north-star spec in
 * docs/generation-rules.md "三、分纬度规则":
 *
 * <ul>
 *   <li>地表 / 地下：桦木 / 深色橡木宽轨，有 7 格宽路基</li>
 *   <li>下界：黑石宽轨，有黑石地基（3 格宽）</li>
 *   <li>末地：幻纱宽轨，无路基</li>
 *   <li>暮色：标准宽轨，有 7 格宽路基；星光：标准宽轨，1 格宽虚空石地基；深暗：标准宽轨，1 格宽磨制深板岩地基</li>
 *   <li>天域 / 深渊：无枕木宽轨，无路基</li>
 * </ul>
 *
 * <p>The foundation is a per-lane pair of plain values — material id and total
 * width — and not a per-material flag: the standard-gauge andesite track
 * carries a 7-wide cobblestone foundation in Twilight bands, a single-block
 * voidstone/deepslate row in Starlight/Otherside bands, and none in the modded
 * fallback. A width of {@code 0} is the single way to say "no foundation";
 * there is no second boolean that could disagree with it, and
 * {@link #foundationHalfWidth()} derives −1 from it so the corridor writer can
 * keep using an inclusive half-width.
 * Unknown modded lanes fall back to the standard track without a foundation.
 *
 * <p>Roadbed material used to be hardcoded to cobblestone in
 * {@link OakTrackCorridor}; it is now a lane property because the nether band
 * needs blackstone under its blackstone track.
 *
 * <p><b>Ids, not {@code Block} constants.</b> Both the track and the foundation
 * store a {@link ResourceLocation}, resolved against a live registry when the
 * corridor is written. A {@code Blocks.XXX} reference here would initialise the
 * vanilla {@code Blocks} class at enum class-init, which needs a bootstrapped
 * registry — that would make the profile unreadable from a plain unit test and
 * is exactly why the track id was already an id.
 *
 * <p>Classification reuses {@link BandLayout}'s public lane primitives
 * (slice / isTwilight / isVoidscape / laneName) so profile lookup always
 * agrees with the layout, the boundary walls and the time lock.
 */
public enum CorridorTrackProfile {
    SURFACE(wide("track_birch_wide"), vanilla("cobblestone"), 7),
    UNDERGROUND(wide("track_dark_oak_wide"), vanilla("cobblestone"), 7),
    NETHER(wide("track_blackstone_wide"), vanilla("blackstone"), 3),
    END(wide("track_phantom_wide"), vanilla("cobblestone"), 0),
    TWILIGHT(wide("track_create_andesite_wide"), vanilla("cobblestone"), 7),
    STARLIGHT(wide("track_create_andesite_wide"), mod("eternal_starlight", "polished_voidstone"), 1),
    OTHERSIDE(wide("track_create_andesite_wide"), vanilla("polished_deepslate"), 1),
    AETHER(wide("track_tieless_wide"), vanilla("cobblestone"), 0),
    VOIDSCAPE(wide("track_tieless_wide"), vanilla("cobblestone"), 0),
    MOD(wide("track_create_andesite_wide"), vanilla("cobblestone"), 0);

    private final ResourceLocation trackId;
    private final ResourceLocation foundationBlockId;
    private final int foundationWidth;

    CorridorTrackProfile(ResourceLocation trackId, ResourceLocation foundationBlockId, int foundationWidth) {
        if (foundationWidth < 0 || (foundationWidth > 0 && (foundationWidth & 1) == 0)) {
            throw new IllegalArgumentException("Invalid foundationWidth: " + foundationWidth);
        }
        this.trackId = trackId;
        this.foundationBlockId = foundationBlockId;
        this.foundationWidth = foundationWidth;
    }

    public ResourceLocation trackId() {
        return this.trackId;
    }

    /**
     * Block id written in the lane's foundation row (one block below the track).
     * Never null: a lane without a foundation is expressed by a width of 0.
     * Resolve it through the block registry — see the class note on why this is
     * an id and not a {@code Block}.
     */
    public ResourceLocation foundationBlockId() {
        return this.foundationBlockId;
    }

    /**
     * Total width in the Z axis of the foundation row: 0 (no foundation) or an
     * odd positive count of blocks centred on {@link OakTrackCorridor#CORRIDOR_Z}.
     * Nether = 3 → z −1..+1; Starlight/Otherside = 1 → z 0..0; the lanes that
     * already had a cobblestone roadbed keep 7 → half-width 3.
     */
    public int foundationWidth() {
        return this.foundationWidth;
    }

    /**
     * Inclusive half-width in the Z axis of the foundation row, so the row is
     * {@code 2 * halfWidth + 1 == foundationWidth()} blocks wide. −1 is the
     * single way to say "no foundation"; 0 means a 1-block-wide row.
     */
    public int foundationHalfWidth() {
        return this.foundationWidth == 0 ? -1 : (this.foundationWidth - 1) / 2;
    }

    /** True when the lane writes a foundation row under the track at all. */
    public boolean hasFoundation() {
        return this.foundationWidth > 0;
    }

    /** Wide-gauge track id inside the railways (Steam 'n' Rails) namespace. */
    private static ResourceLocation wide(String path) {
        return ResourceLocation.fromNamespaceAndPath("railways", path);
    }

    /** Foundation material id inside the vanilla namespace. */
    private static ResourceLocation vanilla(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    /** Foundation material id inside an optional mod's namespace. */
    private static ResourceLocation mod(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
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
