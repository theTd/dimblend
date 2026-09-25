package dimblend.mixin;

import dimblend.BiomeCacheHolder;
import dimblend.DimBlendRegistries;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @Inject(method = "replaceBiomes", at = @At("HEAD"))
    private void dimblend$clearBiomeCache(int chunkX, int chunkZ, FriendlyByteBuf buffer, CallbackInfo ci) {
        if (this.level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        if (this.level.getBiomeManager() instanceof BiomeCacheHolder holder) {
            holder.dimblend$clearCache();
        }
    }
}
