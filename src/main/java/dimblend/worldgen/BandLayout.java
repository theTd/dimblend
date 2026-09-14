package dimblend.worldgen;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

public final class BandLayout {
    public static final int SURFACE_WEIGHT = BandLaneAssigner.SURFACE_WEIGHT;

    private enum Lane {
        SURFACE,
        UNDERGROUND,
        NETHER,
        END,
        AETHER,
        TWILIGHT,
        STARLIGHT,
        OTHERSIDE,
        VOIDSCAPE,
        MOD
    }

    private final List<ChunkGenerator> delegates;
    private final Lane[] lanes;
    private final BandLaneAssigner assigner;

    public BandLayout(List<ChunkGenerator> delegates, long seed) {
        this.delegates = delegates;
        this.lanes = classify(delegates);
        requireLane(Lane.SURFACE);
        requireLane(Lane.UNDERGROUND);
        requireLane(Lane.NETHER);
        requireLane(Lane.END);
        this.assigner = new BandLaneAssigner(kindsOf(this.lanes), seed);
    }

    public long seed() {
        return this.assigner.seed();
    }

    public int delegateIndex(int region) {
        return this.assigner.delegateIndex(region);
    }

    public ChunkGenerator delegate(int region) {
        return this.delegates.get(this.delegateIndex(region));
    }

    public static int regionOfBlockX(int blockX, int bandSize) {
        return Math.floorDiv(blockX, bandSize);
    }

    /**
     * True when blockX is a region-boundary column (local X == 0) and both adjacent regions
     * share a {@link #laneName} identity, e.g. spawn-adjacent 0/1 (both surface), 5/6
     * (both underground), or 11/12 (both nether). Twilight vs starlight (or aether vs
     * voidscape, …) still count as different lanes. Same-name boundaries get no
     * partition wall and no warp gate — see {@link RegionBoundaryWall}.
     */
    public boolean sameLaneAcrossBoundary(int blockX, int bandSize) {
        if (Math.floorMod(blockX, bandSize) != 0) {
            return false;
        }
        int region = regionOfBlockX(blockX, bandSize);
        return laneName(this.delegate(region - 1)).equals(laneName(this.delegate(region)));
    }

    public static boolean isSurfaceOverworld(ChunkGenerator delegate) {
        return laneOf(delegate) == Lane.SURFACE;
    }

    public static int fixedDelegateIndex(int region, List<ChunkGenerator> delegates) {
        Lane[] lanes = classify(delegates);
        Lane lane = fixedLane(Math.abs(region));
        int index = firstOf(lanes, lane == null ? Lane.SURFACE : lane);
        if (index < 0) {
            index = firstOf(lanes, Lane.SURFACE);
        }
        return index >= 0 ? index : 0;
    }

    /**
     * Fixed lane for distance |region| from spawn. Null = random pool.
     * Script is 0–21 plus 32; positive and negative sides mirror the same sequence.
     */
    @javax.annotation.Nullable
    private static Lane fixedLane(int distance) {
        return switch (BandLaneAssigner.fixedKind(distance)) {
            case BandLaneAssigner.SURFACE -> Lane.SURFACE;
            case BandLaneAssigner.UNDERGROUND -> Lane.UNDERGROUND;
            case BandLaneAssigner.NETHER -> Lane.NETHER;
            case BandLaneAssigner.END -> Lane.END;
            case BandLaneAssigner.AETHER -> Lane.AETHER;
            case BandLaneAssigner.TWILIGHT -> Lane.TWILIGHT;
            case BandLaneAssigner.STARLIGHT -> Lane.STARLIGHT;
            case BandLaneAssigner.OTHERSIDE -> Lane.OTHERSIDE;
            case BandLaneAssigner.VOIDSCAPE -> Lane.VOIDSCAPE;
            default -> null;
        };
    }

    private static int firstOf(Lane[] lanes, Lane lane) {
        for (int i = 0; i < lanes.length; i++) {
            if (lanes[i] == lane) {
                return i;
            }
        }
        return -1;
    }

    public static boolean isTwilight(ChunkGenerator delegate) {
        if (delegate instanceof YShiftedNoiseChunkGenerator shifted) {
            return shifted.sourceSettingsKey()
                    .map(key -> "twilightforest".equals(key.location().getNamespace()))
                    .orElse(false);
        }
        ResourceLocation settings = noiseSettings(delegate);
        return settings != null && "dimblend".equals(settings.getNamespace()) && settings.getPath().contains("twilight");
    }

    /** Voidscape void islands use bedrock as terrain; corridor must carve through it. */
    public static boolean isVoidscape(ChunkGenerator delegate) {
        ResourceLocation id = identity(delegate);
        return id != null && "voidscape".equals(id.getNamespace());
    }

    /**
     * Stable lane name for a delegate, used by /dimblend find tab completion and lookup.
     * surface / underground / nether / end / twilight / starlight or the settings/codec namespace
     */
    public static String laneName(ChunkGenerator delegate) {
        if (delegate instanceof SlicedOverworldChunkGenerator sliced) {
            return sliced.slice() == OverworldSlice.SURFACE ? "surface" : "underground";
        }
        if (isTwilight(delegate)) {
            return "twilight";
        }
        ResourceLocation id = identity(delegate);
        if (id == null) {
            return "mod";
        }
        String namespace = id.getNamespace();
        if ("minecraft".equals(namespace)) {
            return switch (id.getPath()) {
                case "overworld" -> "surface";
                case "nether" -> "nether";
                case "end" -> "end";
                default -> "mod";
            };
        }
        if ("dimblend".equals(namespace)) {
            return "twilight";
        }
        if ("eternal_starlight".equals(namespace)) {
            return "starlight";
        }
        return namespace;
    }

    /**
     * Prefer noise-settings key when available; otherwise the registered chunk-generator
     * type name. Non-noise delegates (Eternal Starlight, Voidscape) only expose the latter.
     */
    @javax.annotation.Nullable
    private static ResourceLocation identity(ChunkGenerator delegate) {
        ResourceLocation settings = noiseSettings(delegate);
        if (settings != null) {
            return settings;
        }
        return delegate.getTypeNameForDataFixer().map(ResourceKey::location).orElse(null);
    }

    public boolean touchesSurfaceTwilightSeam(int blockX, int bandSize) {
        return this.surfaceTwilightSeamWeight(blockX, bandSize) >= 0.0f;
    }

    public float surfaceTwilightSeamWeight(int blockX, int bandSize) {
        int region = regionOfBlockX(blockX, bandSize);
        int local = Math.floorMod(blockX, bandSize);
        int here = this.delegateIndex(region);
        if (local < BandIndex.SEAM_WIDTH) {
            int west = this.delegateIndex(region - 1);
            if (isSurfaceTwilightPair(here, west)) {
                float u = local / (BandIndex.SEAM_WIDTH * 2.0f) + 0.5f;
                return smooth(isSurfaceOverworld(this.delegates.get(here)) ? u : 1.0f - u);
            }
        }
        if (local >= bandSize - BandIndex.SEAM_WIDTH) {
            int east = this.delegateIndex(region + 1);
            if (isSurfaceTwilightPair(here, east)) {
                float u = (local - (bandSize - BandIndex.SEAM_WIDTH)) / (BandIndex.SEAM_WIDTH * 2.0f);
                return smooth(isSurfaceOverworld(this.delegates.get(here)) ? 1.0f - u : u);
            }
        }
        return -1.0f;
    }

    public int surfaceDelegateForSeam(int blockX, int bandSize) {
        return this.pairDelegate(blockX, bandSize, true);
    }

    public int twilightDelegateForSeam(int blockX, int bandSize) {
        return this.pairDelegate(blockX, bandSize, false);
    }

    private int pairDelegate(int blockX, int bandSize, boolean surface) {
        int region = regionOfBlockX(blockX, bandSize);
        int a = this.delegateIndex(region);
        int local = Math.floorMod(blockX, bandSize);
        int b = local < BandIndex.SEAM_WIDTH ? this.delegateIndex(region - 1) : this.delegateIndex(region + 1);
        boolean aSurface = isSurfaceOverworld(this.delegates.get(a));
        return surface == aSurface ? a : b;
    }

    private boolean isSurfaceTwilightPair(int a, int b) {
        ChunkGenerator left = this.delegates.get(a);
        ChunkGenerator right = this.delegates.get(b);
        return isSurfaceOverworld(left) && isTwilight(right) || isTwilight(left) && isSurfaceOverworld(right);
    }

    private static float smooth(float u) {
        u = Math.max(0.0f, Math.min(1.0f, u));
        return u * u * (3.0f - 2.0f * u);
    }

    private void requireLane(Lane lane) {
        if (firstLane(lane) < 0) {
            throw new IllegalStateException("rotating delegates missing " + lane);
        }
    }

    private int firstLane(Lane lane) {
        for (int i = 0; i < this.lanes.length; i++) {
            if (this.lanes[i] == lane) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] kindsOf(Lane[] lanes) {
        byte[] kinds = new byte[lanes.length];
        for (int i = 0; i < lanes.length; i++) {
            kinds[i] = switch (lanes[i]) {
                case SURFACE -> BandLaneAssigner.SURFACE;
                case UNDERGROUND -> BandLaneAssigner.UNDERGROUND;
                case NETHER -> BandLaneAssigner.NETHER;
                case END -> BandLaneAssigner.END;
                case AETHER -> BandLaneAssigner.AETHER;
                case TWILIGHT -> BandLaneAssigner.TWILIGHT;
                case STARLIGHT -> BandLaneAssigner.STARLIGHT;
                case OTHERSIDE -> BandLaneAssigner.OTHERSIDE;
                case VOIDSCAPE -> BandLaneAssigner.VOIDSCAPE;
                case MOD -> BandLaneAssigner.MOD;
            };
        }
        return kinds;
    }

    private static Lane[] classify(List<ChunkGenerator> delegates) {
        Lane[] lanes = new Lane[delegates.size()];
        for (int i = 0; i < delegates.size(); i++) {
            lanes[i] = laneOf(delegates.get(i));
        }
        return lanes;
    }

    private static Lane laneOf(ChunkGenerator delegate) {
        return switch (laneName(delegate)) {
            case "surface" -> Lane.SURFACE;
            case "underground" -> Lane.UNDERGROUND;
            case "nether" -> Lane.NETHER;
            case "end" -> Lane.END;
            case "aether" -> Lane.AETHER;
            case "twilight" -> Lane.TWILIGHT;
            case "starlight" -> Lane.STARLIGHT;
            case "deeperdarker" -> Lane.OTHERSIDE;
            case "voidscape" -> Lane.VOIDSCAPE;
            default -> Lane.MOD;
        };
    }

    private static ResourceLocation noiseSettings(ChunkGenerator delegate) {
        if (delegate instanceof SlicedOverworldChunkGenerator sliced) {
            delegate = sliced.inner();
        }
        if (!(delegate instanceof NoiseBasedChunkGenerator noise)) {
            return null;
        }
        return noise.generatorSettings().unwrapKey().map(key -> key.location()).orElse(null);
    }
}
