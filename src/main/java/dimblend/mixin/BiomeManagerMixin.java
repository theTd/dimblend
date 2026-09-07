package dimblend.mixin;

import dimblend.BiomeCacheHolder;
import dimblend.DimBlendRegistries;
import dimblend.worldgen.PregenConfig;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.util.LinearCongruentialGenerator;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BiomeManager.class)
public abstract class BiomeManagerMixin implements BiomeCacheHolder {
    @Shadow
    @Final
    private BiomeManager.NoiseBiomeSource noiseBiomeSource;

    @Shadow
    @Final
    private long biomeZoomSeed;

    /**
     * Last-cell corner-fiddle memo for {@code getBiome} (server-side worldgen
     * path; client queries keep using {@link #dimblend$biomeCache}). Every
     * position inside one quart cell ({@code (pos-2)>>2} per axis) evaluates
     * the same 8 corner fiddle triples, and surface-phase calls are heavily
     * cell-local (~86% corner hit, probe measurement). On a hit the stored
     * fiddles reproduce vanilla {@code getFiddledDistance} bit-for-bit: same
     * {@code LinearCongruentialGenerator} stream, same z-first addition order.
     * On a miss the vanilla body runs and the register is refilled at RETURN —
     * one redundant 8-corner pass on the ~14% miss tail.
     *
     * <p>Threading: every WorldGenRegion builds a fresh BiomeManager and a
     * region's worldgen is single-threaded, so plain instance fields are safe.
     * Client sources are excluded because meshing workers may query
     * concurrently; those go through the client cache above.
     */
    @Unique
    private static final int dimblend$NO_CELL = Integer.MIN_VALUE;

    @Unique
    private final double[] dimblend$fiddles = new double[24]; // per corner i: fx, fy, fz

    @Unique
    private int dimblend$cellQx = dimblend$NO_CELL;

    @Unique
    private int dimblend$cellQy = dimblend$NO_CELL;

    @Unique
    private int dimblend$cellQz = dimblend$NO_CELL;

    @Unique
    private final ConcurrentHashMap<Long, Holder<Biome>> dimblend$biomeCache = new ConcurrentHashMap<>();

    @Unique
    private static final int dimblend$CACHE_CLEAR_THRESHOLD = 262_144;

    @Unique
    private final AtomicLong dimblend$cacheSize = new AtomicLong();

    @Inject(method = "getBiome", at = @At("HEAD"), cancellable = true)
    private void dimblend$cacheBiome(BlockPos pos, CallbackInfoReturnable<Holder<Biome>> cir) {
        if (!(this.noiseBiomeSource instanceof ClientLevel level)) {
            return;
        }
        if (level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        Holder<Biome> cached = this.dimblend$biomeCache.get(pos.asLong());
        if (cached != null) {
            cir.setReturnValue(cached);
        }
    }

    @Inject(method = "getBiome", at = @At("HEAD"), cancellable = true)
    private void dimblend$cornerMemoHit(BlockPos pos, CallbackInfoReturnable<Holder<Biome>> cir) {
        if (cir.isCancelled()) {
            return; // client cache already answered
        }
        if (!dimblend$surfaceMemoEnabled()) {
            return;
        }
        if (this.noiseBiomeSource instanceof ClientLevel) {
            return; // single-threaded server path only
        }
        int x = pos.getX() - 2;
        int y = pos.getY() - 2;
        int z = pos.getZ() - 2;
        int qx = x >> 2;
        int qy = y >> 2;
        int qz = z >> 2;
        if (qx != this.dimblend$cellQx || qy != this.dimblend$cellQy || qz != this.dimblend$cellQz) {
            return;
        }
        double dx = (x & 3) / 4.0;
        double dy = (y & 3) / 4.0;
        double dz = (z & 3) / 4.0;
        double best = Double.POSITIVE_INFINITY;
        int bestI = 0;
        for (int i = 0; i < 8; i++) {
            double ax = (i & 4) == 0 ? dx : dx - 1.0;
            double ay = (i & 2) == 0 ? dy : dy - 1.0;
            double az = (i & 1) == 0 ? dz : dz - 1.0;
            double d = Mth.square(az + this.dimblend$fiddles[3 * i + 2])
                    + Mth.square(ay + this.dimblend$fiddles[3 * i + 1])
                    + Mth.square(ax + this.dimblend$fiddles[3 * i]);
            if (d < best) {
                best = d;
                bestI = i;
            }
        }
        int cx = (bestI & 4) == 0 ? qx : qx + 1;
        int cy = (bestI & 2) == 0 ? qy : qy + 1;
        int cz = (bestI & 1) == 0 ? qz : qz + 1;
        cir.setReturnValue(this.noiseBiomeSource.getNoiseBiome(cx, cy, cz));
    }

    @Inject(method = "getBiome", at = @At("RETURN"))
    private void dimblend$storeBiome(BlockPos pos, CallbackInfoReturnable<Holder<Biome>> cir) {
        if (cir.isCancelled()) {
            return;
        }
        if (!(this.noiseBiomeSource instanceof ClientLevel level)) {
            return;
        }
        if (level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        Holder<Biome> result = cir.getReturnValue();
        if (result != null) {
            this.dimblend$biomeCache.put(pos.asLong(), result);
            if (this.dimblend$cacheSize.incrementAndGet() > dimblend$CACHE_CLEAR_THRESHOLD) {
                this.dimblend$biomeCache.clear();
                this.dimblend$cacheSize.set(0);
            }
        }
    }

    @Inject(method = "getBiome", at = @At("RETURN"))
    private void dimblend$cornerMemoFill(BlockPos pos, CallbackInfoReturnable<Holder<Biome>> cir) {
        if (cir.isCancelled()) {
            return; // answered by the memo hit path above
        }
        if (!dimblend$surfaceMemoEnabled()) {
            return;
        }
        if (this.noiseBiomeSource instanceof ClientLevel) {
            return;
        }
        int x = pos.getX() - 2;
        int y = pos.getY() - 2;
        int z = pos.getZ() - 2;
        int qx = x >> 2;
        int qy = y >> 2;
        int qz = z >> 2;
        if (qx == this.dimblend$cellQx && qy == this.dimblend$cellQy && qz == this.dimblend$cellQz) {
            return;
        }
        // recompute the 8 corner fiddle triples of THIS cell exactly as
        // vanilla getFiddledDistance derives them (bytecode-verified order)
        for (int i = 0; i < 8; i++) {
            int cx = (i & 4) == 0 ? qx : qx + 1;
            int cy = (i & 2) == 0 ? qy : qy + 1;
            int cz = (i & 1) == 0 ? qz : qz + 1;
            long s = LinearCongruentialGenerator.next(this.biomeZoomSeed, cx);
            s = LinearCongruentialGenerator.next(s, cy);
            s = LinearCongruentialGenerator.next(s, cz);
            s = LinearCongruentialGenerator.next(s, cx);
            s = LinearCongruentialGenerator.next(s, cy);
            s = LinearCongruentialGenerator.next(s, cz);
            double fx = dimblend$getFiddle(s);
            s = LinearCongruentialGenerator.next(s, this.biomeZoomSeed);
            double fy = dimblend$getFiddle(s);
            s = LinearCongruentialGenerator.next(s, this.biomeZoomSeed);
            double fz = dimblend$getFiddle(s);
            this.dimblend$fiddles[3 * i] = fx;
            this.dimblend$fiddles[3 * i + 1] = fy;
            this.dimblend$fiddles[3 * i + 2] = fz;
        }
        this.dimblend$cellQx = qx;
        this.dimblend$cellQy = qy;
        this.dimblend$cellQz = qz;
    }

    @Override
    public void dimblend$clearCache() {
        this.dimblend$biomeCache.clear();
        this.dimblend$cacheSize.set(0);
    }

    @Unique
    private boolean dimblend$surfaceMemoEnabled() {
        try {
            return PregenConfig.SURFACE_CORNER_MEMO.get();
        } catch (Exception e) {
            return false; // config not loaded yet -> vanilla path
        }
    }

    @Unique
    private static double dimblend$getFiddle(long value) {
        double d = Math.floorMod(value >> 24, 1024) / 1024.0;
        return (d - 0.5) * 0.9;
    }
}
