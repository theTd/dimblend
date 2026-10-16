package dimblend.experience.mixin.compat.fluid;

import com.adonis.fluid.block.CopperTap.CopperTapBlock;
import com.adonis.fluid.block.CopperTap.CopperTapBlockEntity;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Reuse the tap's own idle-drip decision; particles and normal transfers stay upstream. */
@Mixin(CopperTapBlockEntity.class)
public abstract class CopperTapLeakMixin {
    @Unique
    private int dimblend$idleDripTicks;

    @WrapMethod(method = "tick()V", require = 1, allow = 1)
    private void dimblend$leakAfterGrace(Operation<Void> original) {
        original.call();
        CopperTapBlockEntity self = (CopperTapBlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || level.isClientSide) {
            return;
        }
        if (!self.getBlockState().getValue(BlockStateProperties.OPEN) || !self.shouldDrip()
                || self.isProcessing() || self.isBeltProcessing()) {
            dimblend$idleDripTicks = 0;
            return;
        }
        Direction face = self.getBlockState().getValue(CopperTapBlock.FACING);
        BlockPos sourcePos = self.getBlockPos().relative(face.getOpposite());
        if (!level.hasChunkAt(sourcePos)) {
            dimblend$idleDripTicks = 0;
            return;
        }
        IFluidHandler source = level.getCapability(Capabilities.FluidHandler.BLOCK, sourcePos, face);
        if (source == null) {
            source = level.getCapability(Capabilities.FluidHandler.BLOCK, sourcePos, null);
        }
        // Waterlogged leaves/grates have only the tap's synthetic infinite handler.
        if (source == null || source.drain(1, IFluidHandler.FluidAction.SIMULATE).isEmpty()) {
            dimblend$idleDripTicks = 0;
            return;
        }
        if (++dimblend$idleDripTicks >= 300 && (dimblend$idleDripTicks - 300) % 20 == 0) {
            source.drain(1, IFluidHandler.FluidAction.EXECUTE);
            dimblend$idleDripTicks = 300;
        }
    }
}
