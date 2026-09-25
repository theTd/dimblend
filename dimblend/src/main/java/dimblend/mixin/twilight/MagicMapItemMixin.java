package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.MapColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import twilightforest.item.MagicMapItem;
import twilightforest.util.datamaps.MagicMapBiomeColor;

@Mixin(value = MagicMapItem.class, remap = false)
public abstract class MagicMapItemMixin {
    @Redirect(
            method = "lambda$update$0",
            at = @At(value = "NEW", target = "(III)Lnet/minecraft/core/BlockPos;", remap = true)
    )
    private static BlockPos dimblend$sampleLiftedBiome(int x, int y, int z, int biomesPerPixel, Level level, int startX, int startZ, ChunkPos chunkPos) {
        int sampleY = TwilightBand.isTwilightColumn(level, x) ? TwilightBand.sampleY(level) : y;
        return new BlockPos(x, sampleY, z);
    }

    @Inject(method = "getMapColorPerBiome", at = @At("HEAD"), cancellable = true)
    private void dimblend$blankForeignBiomes(Holder<Biome> biome, CallbackInfoReturnable<MagicMapBiomeColor> cir) {
        if (!TwilightBand.isTwilightBiome(biome)) {
            cir.setReturnValue(new MagicMapBiomeColor(MapColor.NONE));
        }
    }
}
