package dimblend.experience.mixin.compat.create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.simibubi.create.content.decoration.slidingDoor.SlidingDoorBlock;
import com.simibubi.create.content.equipment.wrench.IWrenchable;
import dimblend.experience.compat.create.SlidingDoorPairing;
import dimblend.experience.compat.create.SlidingDoorPairingInteraction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockSetType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SlidingDoorBlock.class)
public abstract class SlidingDoorBlockMixin extends DoorBlock implements IWrenchable {
    private SlidingDoorBlockMixin(
            BlockBehaviour.Properties properties, BlockSetType type, boolean folds) {
        super(type, properties);
    }

    @Inject(method = "createBlockStateDefinition", at = @At("TAIL"), require = 1, allow = 1)
    private void dimblend$addPairedProperty(
            StateDefinition.Builder<Block, BlockState> builder, CallbackInfo ci) {
        builder.add(SlidingDoorPairing.PAIRED);
    }

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, allow = 1)
    private void dimblend$initDefaultPairedState(
            BlockBehaviour.Properties properties, BlockSetType type, boolean folds, CallbackInfo ci) {
        BlockState defaultState = this.defaultBlockState();
        if (defaultState.hasProperty(SlidingDoorPairing.PAIRED)) {
            this.registerDefaultState(defaultState.setValue(SlidingDoorPairing.PAIRED, false));
        }
    }

    @WrapMethod(method = "updateShape", require = 1, allow = 1)
    private BlockState dimblend$preservePairedAcrossHalves(
            BlockState pState,
            Direction pFacing,
            BlockState pFacingState,
            LevelAccessor pLevel,
            BlockPos pCurrentPos,
            BlockPos pFacingPos,
            Operation<BlockState> original) {
        BlockState returned = original.call(pState, pFacing, pFacingState, pLevel, pCurrentPos, pFacingPos);
        Block self = (Block) (Object) this;
        if (!returned.is(self) || !returned.hasProperty(SlidingDoorPairing.PAIRED)) {
            return returned;
        }

        if (pFacing.getAxis() == Direction.Axis.Y && pFacingState.is(self)) {
            DoubleBlockHalf half = returned.getValue(HALF);
            if ((half == DoubleBlockHalf.LOWER) == (pFacing == Direction.UP)
                    && pFacingState.getValue(HALF) != half
                    && pFacingState.hasProperty(SlidingDoorPairing.PAIRED)) {
                // DoorBlock.updateShape already returns pFacingState with HALF swapped back, so
                // OPEN/POWERED arrive from the sibling. Create then overwrites VISIBLE from the
                // sibling because its baked model encodes the open pose. A paired door keeps
                // VISIBLE=false forever and is drawn by SlidingDoorRenderer, so only PAIRED needs
                // carrying across here.
                boolean paired = pFacingState.getValue(SlidingDoorPairing.PAIRED);
                BlockState updated = returned.setValue(SlidingDoorPairing.PAIRED, paired);
                if (paired && updated.getValue(SlidingDoorBlock.VISIBLE)) {
                    updated = updated.setValue(SlidingDoorBlock.VISIBLE, false);
                }
                return updated;
            }
        }

        if (SlidingDoorPairing.isPaired(returned) && returned.getValue(SlidingDoorBlock.VISIBLE)) {
            return returned.setValue(SlidingDoorBlock.VISIBLE, false);
        }
        return returned;
    }

    @Inject(method = "getRenderShape", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$pairedRenderShape(
            BlockState pState, CallbackInfoReturnable<RenderShape> cir) {
        if (SlidingDoorPairing.isPaired(pState)) {
            cir.setReturnValue(RenderShape.ENTITYBLOCK_ANIMATED);
        }
    }

    @Inject(method = "getShape", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$pairedShape(
            BlockState pState,
            BlockGetter pLevel,
            BlockPos pPos,
            CollisionContext pContext,
            CallbackInfoReturnable<VoxelShape> cir) {
        if (SlidingDoorPairing.isPaired(pState)) {
            cir.setReturnValue(SlidingDoorPairing.pairedShape(pState));
        }
    }

    @Override
    protected VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (SlidingDoorPairing.isPaired(state) && state.getValue(OPEN)) {
            return Shapes.empty();
        }
        return super.getCollisionShape(state, level, pos, context);
    }

    @Inject(method = "isDoubleDoor", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private static void dimblend$isolatePairedFromNeighborDoors(
            BlockState pState,
            DoorHingeSide hinge,
            Direction facing,
            BlockState otherDoor,
            CallbackInfoReturnable<Boolean> cir) {
        if (SlidingDoorPairing.isPaired(pState) || SlidingDoorPairing.isPaired(otherDoor)) {
            cir.setReturnValue(false);
        }
    }

    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        if (SlidingDoorPairing.isTargetDoor(state)) {
            return InteractionResult.PASS;
        }
        return IWrenchable.super.onWrenched(state, context);
    }

    @Override
    public InteractionResult onSneakWrenched(BlockState state, UseOnContext context) {
        if (SlidingDoorPairing.isPaired(state)) {
            return SlidingDoorPairingInteraction.onSneakWrenchedPaired(state, context);
        }
        return IWrenchable.super.onSneakWrenched(state, context);
    }
}
