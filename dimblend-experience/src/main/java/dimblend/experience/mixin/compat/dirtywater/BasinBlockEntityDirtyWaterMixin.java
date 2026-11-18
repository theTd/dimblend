package dimblend.experience.mixin.compat.dirtywater;

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import dimblend.experience.compat.dirtywater.BasinFluidGuard;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 给 Create 工作盆（{@code create:basin}）的方块流体能力套一层脏水闸门。
 *
 * <p>{@code addBehaviours} 里 {@code fluidCapability = new CombinedTankWrapper(output, input)}；
 * TAIL 注入把它换成 {@link BasinFluidGuard}（委托原 handler，只拦脏水 fill）。这样
 * <b>管路在输出端堵塞</b>（fill 返回 0 → {@code FluidNetwork} 不抽源罐）、
 * <b>铜龙头不注</b>（{@code tryProcess} 先 SIMULATE 再抽），都不需要再改 Create 自己的代码。</p>
 *
 * <p>{@code fluidCapability} 目标为 protected，按仓库纪律 shadow 声明成 private（只声明存在，
 * 实际访问走目标类自身）。</p>
 */
@Mixin(value = BasinBlockEntity.class, remap = false)
public abstract class BasinBlockEntityDirtyWaterMixin {

    @Shadow
    private IFluidHandler fluidCapability;

    @Inject(method = "addBehaviours(Ljava/util/List;)V", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$guardDirtyWaterFill(List<BlockEntityBehaviour> behaviours, CallbackInfo ci) {
        IFluidHandler current = this.fluidCapability;
        if (current == null || current instanceof BasinFluidGuard) {
            return;
        }
        this.fluidCapability = new BasinFluidGuard((BasinBlockEntity) (Object) this, current);
    }
}
