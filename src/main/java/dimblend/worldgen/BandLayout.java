package dimblend.worldgen;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

public final class BandLayout {
    public static final int SURFACE_WEIGHT = 2;

    private enum Lane {
        SURFACE,
        UNDERGROUND,
        NETHER,
        END,
        MOD
    }

    private final List<ChunkGenerator> delegates;
    private final Lane[] lanes;
    private final long seed;
    private final Int2IntOpenHashMap cache = new Int2IntOpenHashMap();

    public BandLayout(List<ChunkGenerator> delegates, long seed) {
        this.delegates = delegates;
        this.lanes = classify(delegates);
        this.seed = seed;
        requireLane(Lane.SURFACE);
        requireLane(Lane.UNDERGROUND);
        requireLane(Lane.NETHER);
        requireLane(Lane.END);
        this.cache.defaultReturnValue(-1);
        this.cache.put(0, firstLane(Lane.SURFACE));
    }

    public long seed() {
        return this.seed;
    }

    public int delegateIndex(int region) {
        synchronized (this.cache) {
            int cached = this.cache.get(region);
            if (cached >= 0) {
                return cached;
            }
            int step = region > 0 ? 1 : -1;
            int current = this.cache.get(0);
            for (int r = step; ; r += step) {
                int known = this.cache.get(r);
                if (known >= 0) {
                    current = known;
                    if (r == region) {
                        return current;
                    }
                    continue;
                }
                current = assign(r, current);
                this.cache.put(r, current);
                if (r == region) {
                    return current;
                }
            }
        }
    }

    public ChunkGenerator delegate(int region) {
        return this.delegates.get(this.delegateIndex(region));
    }

    public static int regionOfBlockX(int blockX, int bandSize) {
        return Math.floorDiv(blockX, bandSize);
    }

    public static boolean isSurfaceOverworld(ChunkGenerator delegate) {
        return laneOf(delegate) == Lane.SURFACE;
    }

    public static int fixedDelegateIndex(int region, List<ChunkGenerator> delegates) {
        if (region < 0) {
            Lane[] lanes = classify(delegates);
            return firstOf(lanes, Lane.SURFACE);
        }
        Lane[] lanes = classify(delegates);
        Lane lane = fixedLane(region);
        if (lane == null) {
            return firstOf(lanes, Lane.SURFACE);
        }
        int index = firstOf(lanes, lane);
        return index >= 0 ? index : firstOf(lanes, Lane.SURFACE);
    }

    @javax.annotation.Nullable
    private static Lane fixedLane(int region) {
        if (region == 0 || region == 1 || region == 4) {
            return Lane.SURFACE;
        }
        if (region == 2 || region == 5) {
            return Lane.UNDERGROUND;
        }
        if (region == 3 || region == 6) {
            return Lane.UNDERGROUND;
        }
        if (region == 7) {
            return Lane.NETHER;
        }
        if (region == 32) {
            return Lane.END;
        }
        return null;
    }

    private static int firstOf(Lane[] lanes, Lane lane) {
        for (int i = 0; i < lanes.length; i++) {
            if (lanes[i] == lane) {
                return i;
            }
        }
        return 0;
    }

    public static boolean isTwilight(ChunkGenerator delegate) {
        ResourceLocation settings = noiseSettings(delegate);
        return settings != null && "dimblend".equals(settings.getNamespace()) && settings.getPath().contains("twilight");
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

    private int assign(int region, int previous) {
        if (region < 0) {
            int abs = -region;
            if (abs >= 1 && abs <= 15) {
                return pick(region, previous, Lane.SURFACE, Lane.UNDERGROUND, Lane.NETHER);
            }
            if (abs >= 16 && abs <= 31) {
                return pick(region, previous, Lane.SURFACE, Lane.UNDERGROUND, Lane.NETHER, Lane.MOD);
            }
            return pick(region, previous, Lane.SURFACE, Lane.UNDERGROUND, Lane.NETHER, Lane.END, Lane.MOD);
        }
        if (region == 1 || region == 4) {
            return firstLane(Lane.SURFACE);
        }
        if (region == 2 || region == 5) {
            return firstLane(Lane.UNDERGROUND);
        }
        if (region == 3 || region == 6) {
            return firstLane(Lane.UNDERGROUND);
        }
        if (region == 7) {
            return firstLane(Lane.NETHER);
        }
        if (region == 32) {
            return firstLane(Lane.END);
        }
        if (region >= 8 && region <= 15) {
            return pick(region, previous, Lane.SURFACE, Lane.UNDERGROUND, Lane.NETHER);
        }
        if (region >= 16 && region <= 31) {
            return pick(region, previous, Lane.SURFACE, Lane.UNDERGROUND, Lane.NETHER, Lane.MOD);
        }
        return pick(region, previous, Lane.SURFACE, Lane.UNDERGROUND, Lane.NETHER, Lane.END, Lane.MOD);
    }

    private int pick(int region, int previous, Lane... wanted) {
        int total = 0;
        for (int i = 0; i < this.lanes.length; i++) {
            if (i == previous || !contains(wanted, this.lanes[i])) {
                continue;
            }
            total += weight(this.lanes[i]);
        }
        if (total <= 0) {
            return previous;
        }
        int roll = mix(this.seed, region);
        if (roll < 0) {
            roll = ~roll;
        }
        roll %= total;
        for (int i = 0; i < this.lanes.length; i++) {
            if (i == previous || !contains(wanted, this.lanes[i])) {
                continue;
            }
            int w = weight(this.lanes[i]);
            if (roll < w) {
                return i;
            }
            roll -= w;
        }
        return previous;
    }

    private static boolean contains(Lane[] wanted, Lane lane) {
        for (Lane value : wanted) {
            if (value == lane) {
                return true;
            }
        }
        return false;
    }

    private static int weight(Lane lane) {
        return lane == Lane.SURFACE ? SURFACE_WEIGHT : 1;
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

    private static Lane[] classify(List<ChunkGenerator> delegates) {
        Lane[] lanes = new Lane[delegates.size()];
        for (int i = 0; i < delegates.size(); i++) {
            lanes[i] = laneOf(delegates.get(i));
        }
        return lanes;
    }

    private static Lane laneOf(ChunkGenerator delegate) {
        if (delegate instanceof SlicedOverworldChunkGenerator sliced) {
            return switch (sliced.slice()) {
                case SURFACE -> Lane.SURFACE;
                case UNDERGROUND -> Lane.UNDERGROUND;
            };
        }
        ResourceLocation settings = noiseSettings(delegate);
        if (settings != null) {
            if ("minecraft".equals(settings.getNamespace()) && "overworld".equals(settings.getPath())) {
                return Lane.SURFACE;
            }
            if ("minecraft".equals(settings.getNamespace()) && "nether".equals(settings.getPath())) {
                return Lane.NETHER;
            }
            if ("minecraft".equals(settings.getNamespace()) && "end".equals(settings.getPath())) {
                return Lane.END;
            }
        }
        return Lane.MOD;
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

    private static int mix(long seed, int region) {
        long x = seed;
        x ^= (long) region * 0x9E3779B97F4A7C15L;
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= x >>> 33;
        return (int) x;
    }
}
