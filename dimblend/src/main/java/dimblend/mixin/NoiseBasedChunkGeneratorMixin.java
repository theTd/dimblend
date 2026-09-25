package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.worldgen.YShiftScope;
import dimblend.worldgen.YShiftedNoiseChunkGenerator;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @WrapMethod(method = "createNoiseChunk")
    private NoiseChunk dimblend$scopeShift(
            ChunkAccess chunk,
            StructureManager structures,
            Blender blender,
            RandomState random,
            Operation<NoiseChunk> original
    ) {
        if ((Object) this instanceof YShiftedNoiseChunkGenerator shifted) {
            return YShiftScope.get(shifted.yOffset(), () -> original.call(chunk, structures, blender, random));
        }
        return original.call(chunk, structures, blender, random);
    }
}
