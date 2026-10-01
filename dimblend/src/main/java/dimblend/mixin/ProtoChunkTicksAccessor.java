package dimblend.mixin;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.ProtoChunkTicks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@link ProtoChunkTicks} has no way to remove or move a tick, so a worldgen step that
 * relocates blocks vertically swaps in a rebuilt container instead.
 */
@Mixin(ProtoChunk.class)
public interface ProtoChunkTicksAccessor {
    @Mutable
    @Accessor("blockTicks")
    void dimblend$setBlockTicks(ProtoChunkTicks<Block> ticks);

    @Mutable
    @Accessor("fluidTicks")
    void dimblend$setFluidTicks(ProtoChunkTicks<Fluid> ticks);
}
