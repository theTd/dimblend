package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import twilightforest.item.MagicMapItem;

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
}
