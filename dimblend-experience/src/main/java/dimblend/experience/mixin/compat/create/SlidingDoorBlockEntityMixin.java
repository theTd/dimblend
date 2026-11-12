package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlockEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dimblend.experience.compat.create.SlidingDoorAnimationAccess;
import dimblend.experience.compat.create.SlidingDoorPairing;
import net.createmod.catnip.animation.LerpedFloat;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SlidingDoorBlockEntity.class)
public abstract class SlidingDoorBlockEntityMixin extends SmartBlockEntity
        implements SlidingDoorAnimationAccess {
    @Shadow(remap = false)
    private LerpedFloat animation;

    private SlidingDoorBlockEntityMixin(
            BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public float dimblend$animationValue(float partialTicks) {
        return this.animation.getValue(partialTicks);
    }

    @Override
    public void dimblend$setAnimationValue(float value) {
        this.animation.startWithValue(value);
    }

    @Inject(method = "showBlockModel", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$keepPairedInvisible(CallbackInfo ci) {
        if (SlidingDoorPairing.isPaired(this.getBlockState())) {
            if (this.level != null) {
                this.level.playSound(
                        null,
                        this.worldPosition,
                        SoundEvents.IRON_DOOR_CLOSE,
                        SoundSource.BLOCKS,
                        0.5f,
                        1.0f);
            }
            ci.cancel();
        }
    }

    @Inject(method = "shouldRenderSpecial", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$alwaysRenderSpecialWhenPaired(
            BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (SlidingDoorPairing.isPaired(state)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "createRenderBoundingBox", at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void dimblend$expandPairedRenderBounds(CallbackInfoReturnable<AABB> cir) {
        cir.setReturnValue(cir.getReturnValue().inflate(1.5));
    }
}
