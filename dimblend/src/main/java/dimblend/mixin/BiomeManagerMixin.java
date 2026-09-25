package dimblend.mixin;

import dimblend.BiomeCacheHolder;
import dimblend.DimBlendRegistries;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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

    @Override
    public void dimblend$clearCache() {
        this.dimblend$biomeCache.clear();
        this.dimblend$cacheSize.set(0);
    }
}
