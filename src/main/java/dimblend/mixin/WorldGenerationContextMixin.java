package dimblend.mixin;

import dimblend.worldgen.WorldGenerationContextExtension;
import dimblend.worldgen.YShiftedNoiseChunkGenerator;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WorldGenerationContext.class)
public abstract class WorldGenerationContextMixin implements WorldGenerationContextExtension {
    @Unique
    private int dimblend$absoluteOffset;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dimblend$captureShift(ChunkGenerator generator, LevelHeightAccessor height, CallbackInfo ci) {
        if (generator instanceof YShiftedNoiseChunkGenerator shifted) {
            this.dimblend$absoluteOffset = shifted.yOffset();
        }
    }

    @Override
    public int dimblend$absoluteOffset() {
        return this.dimblend$absoluteOffset;
    }

    @Override
    public void dimblend$setAbsoluteOffset(int offset) {
        this.dimblend$absoluteOffset = offset;
    }
}
