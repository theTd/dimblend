package dimblend.mixin;

import dimblend.BiomeCacheHolder;
import dimblend.DimBlendRegistries;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Shadow
    @Final
    private ClientLevel level;

    @Inject(method = {"replaceBiomes", "drop", "updateViewRadius"}, at = @At("RETURN"))
    private void dimblend$biomesChanged(CallbackInfo ci) {
        dimblend$clearBiomeCache();
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void dimblend$chunkArrived(CallbackInfoReturnable<LevelChunk> cir) {
        if (cir.getReturnValue() != null) dimblend$clearBiomeCache();
    }

    @Unique
    private void dimblend$clearBiomeCache() {
        if (this.level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        if (this.level.getBiomeManager() instanceof BiomeCacheHolder holder) {
            holder.dimblend$clearCache();
        }
    }
}
