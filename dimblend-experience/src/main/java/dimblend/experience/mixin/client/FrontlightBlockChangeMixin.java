package dimblend.experience.mixin.client;

import dimblend.experience.client.headlight.FrontlightIndex;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class FrontlightBlockChangeMixin {
    @Inject(method = "setBlockState", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$updateFrontlight(BlockPos pos, BlockState state, boolean moving,
                                          CallbackInfoReturnable<BlockState> cir) {
        LevelChunk chunk = (LevelChunk) (Object) this;
        if (chunk.getLevel() instanceof ClientLevel && cir.getReturnValue() != null) {
            FrontlightIndex.blockChanged(chunk, pos, chunk.getBlockState(pos));
        }
    }
}
