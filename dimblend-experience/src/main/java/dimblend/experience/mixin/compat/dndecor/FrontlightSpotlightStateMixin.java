package dimblend.experience.mixin.compat.dndecor;

import dimblend.experience.compat.dndecor.FrontlightSpotlight;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Add a normally-off state without adding item data or a block entity to DnDecor lamps. */
@Mixin(targets = "dev.lopyluna.dndecor.content.blocks.frontlight.FrontlightBlock", remap = false)
public abstract class FrontlightSpotlightStateMixin extends Block {
    private FrontlightSpotlightStateMixin(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Inject(method = "createBlockStateDefinition", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$addSpotlightState(StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(FrontlightSpotlight.ENABLED);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, allow = 1)
    private void dimblend$defaultSpotlightOff(BlockBehaviour.Properties properties, CallbackInfo ci) {
        registerDefaultState(defaultBlockState().setValue(FrontlightSpotlight.ENABLED, false));
    }
}
