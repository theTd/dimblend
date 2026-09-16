package dimblend.mixin;

import dimblend.compat.VoidscapeSkyLight;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LayerLightSectionStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The rotating Voidscape lane ships an <em>all-zero</em> sky data layer for its
 * sections: "no sky light here", the answer a {@code has_skylight: false}
 * dimension gives by having no sky layer at all.
 *
 * <p>Null is not that answer. Null means "no data available" and consumers that
 * read data layers directly substitute their own default: Sodium's
 * {@code ClonedChunkSection.copyLightArray} replaces a null sky layer with
 * {@code DEFAULT_SKY_LIGHT_ARRAY = new DataLayer(15)} whenever the dimension
 * declares {@code has_skylight} — which {@code dimblend:rotating} does for every
 * other lane — and its {@code LevelSlice.getBrightness} never calls
 * {@code getLightValue}, so a null layer would light the whole band to 15, caves
 * and island interiors included. An empty layer keeps every reader at 0:
 * {@code ChunkSerializer} skips empty layers, {@code ClientboundLightUpdatePacketData}
 * marks them empty so the client queues a zero layer instead of synthesizing its
 * own, and vanilla reads are already 0 via {@link SkyLightSectionStorageMixin}.
 *
 * <p>Block light is never touched, and the lane test is a no-op outside
 * {@code dimblend:rotating}. The returned instance escapes into third-party
 * caches (Sodium stores it in its cloned section), so a fresh layer is handed
 * out per call rather than a shared instance.
 */
@Mixin(LayerLightSectionStorage.class)
public abstract class LayerLightSectionStorageMixin {
    @Shadow
    @Final
    private LightLayer layer;

    @Shadow
    @Final
    protected LightChunkGetter chunkSource;

    @Inject(method = "getDataLayerData(J)Lnet/minecraft/world/level/chunk/DataLayer;", at = @At("HEAD"), cancellable = true)
    private void dimblend$voidscapeSkyLightDataIsEmpty(long sectionPos, CallbackInfoReturnable<DataLayer> cir) {
        if (this.layer != LightLayer.SKY) {
            return;
        }
        if (VoidscapeSkyLight.isMaskedSection(this.chunkSource.getLevel(), sectionPos)) {
            cir.setReturnValue(new DataLayer());
        }
    }
}
