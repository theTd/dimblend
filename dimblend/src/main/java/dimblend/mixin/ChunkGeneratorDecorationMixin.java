package dimblend.mixin;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BiomeDecoration;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces vanilla decoration only in {@code dimblend:rotating}. Other dimensions keep
 * the original method so their mixins still apply.
 */
@Mixin(ChunkGenerator.class)
public abstract class ChunkGeneratorDecorationMixin {
    @Inject(method = "applyBiomeDecoration", at = @At("HEAD"), cancellable = true)
    private void dimblend$safeRotatingDecoration(
            WorldGenLevel level, ChunkAccess chunk, StructureManager structures, CallbackInfo ci) {
        if (!DimBlendRegistries.ROTATING_LEVEL.equals(level.getLevel().dimension())) {
            return;
        }
        ci.cancel();
        BiomeDecoration.apply((ChunkGenerator) (Object) this, level, chunk, structures);
    }
}
