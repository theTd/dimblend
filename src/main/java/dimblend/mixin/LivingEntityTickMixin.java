package dimblend.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.time.ServerBandTime;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LivingEntity.class)
public abstract class LivingEntityTickMixin {
    @WrapMethod(method = "tick")
    private void dimblend$bandTime(Operation<Void> original) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.level().isClientSide()) {
            original.call();
            return;
        }
        ServerBandTime.run(self.level(), self.blockPosition(), original::call);
    }
}
