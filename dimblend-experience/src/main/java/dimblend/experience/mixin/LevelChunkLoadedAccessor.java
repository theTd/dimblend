package dimblend.experience.mixin;

import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelChunk.class)
public interface LevelChunkLoadedAccessor {
    @Accessor("loaded")
    boolean dimblend$isLoaded();
}
