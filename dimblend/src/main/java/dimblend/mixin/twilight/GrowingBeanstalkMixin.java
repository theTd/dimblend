package dimblend.mixin.twilight;

import dimblend.compat.TwilightBand;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import twilightforest.block.entity.GrowingBeanstalkBlockEntity;

@Mixin(value = GrowingBeanstalkBlockEntity.class, remap = false)
public abstract class GrowingBeanstalkMixin {
    @ModifyConstant(method = "tryToPlaceStalk", constant = @Constant(intValue = 150))
    private int dimblend$liftedCloudClear(int original, Level level, BlockPos pos, boolean checkBlocked) {
        BlockEntity self = (BlockEntity) (Object) this;
        if (TwilightBand.isTwilightPos(level != null ? level : self.getLevel(), pos)) {
            return original + TwilightBand.DEFAULT_Y_OFFSET;
        }
        return original;
    }
}
