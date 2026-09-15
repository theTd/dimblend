package dimblend.mixin;

import dimblend.compat.CopycatObsidianHardness;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.extensions.IBlockStateExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Copycats override NeoForge's 4-arg {@code Block#getExplosionResistance} and
 * {@code canEntityDestroy} with the applied material. Those overrides skip
 * {@code super}, so a {@code Block} mixin would miss them. The
 * {@link IBlockStateExtension} defaults are the callers, and HEAD-cancel here
 * substitutes obsidian before that dispatch.
 */
@Mixin(IBlockStateExtension.class)
public interface IBlockStateExtensionMixin {

    @Inject(method = "getExplosionResistance", at = @At("HEAD"), cancellable = true, remap = false)
    private void dimblend$copycatObsidianExplosionResistance(
            BlockGetter level, BlockPos pos, Explosion explosion, CallbackInfoReturnable<Float> cir) {
        BlockState state = (BlockState) (Object) this;
        if (CopycatObsidianHardness.isCopycat(state)) {
            cir.setReturnValue(CopycatObsidianHardness.obsidianExplosionResistance());
        }
    }

    @Inject(method = "canEntityDestroy", at = @At("HEAD"), cancellable = true, remap = false)
    private void dimblend$copycatObsidianCanEntityDestroy(
            BlockGetter level, BlockPos pos, Entity entity, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = (BlockState) (Object) this;
        if (CopycatObsidianHardness.isCopycat(state)) {
            cir.setReturnValue(Blocks.OBSIDIAN.defaultBlockState().canEntityDestroy(level, pos, entity));
        }
    }
}
