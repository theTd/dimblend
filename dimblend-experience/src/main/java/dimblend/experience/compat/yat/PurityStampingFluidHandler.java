package dimblend.experience.compat.yat;

import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 给"往水箱/容器里灌世界水"的路径套一层纯度图章：{@code fill} 的水先打上指定纯度，
 * 再交给真实 handler。
 *
 * <p>用于不改目标方法内部字节码就能补纯度的场景（Sophisticated Core 泵升级的
 * {@code fillFromBlock} 等）：把它的 {@code IFluidHandler} 参数换成本包装即可，
 * 其它动作（{@code drain} / 容量 / 有效性）原样转发，行为不变。</p>
 *
 * <p>只在 {@code WaterSourcePurityRules.isWater(...)} 为真时打标；已有更高纯度的水
 * 按 {@code min} 混合（脏水只会更脏，见 {@link WaterSourcePurityRules#blendMin}）。</p>
 */
public final class PurityStampingFluidHandler implements IFluidHandler {

    private final IFluidHandler delegate;
    private final int purity;

    public PurityStampingFluidHandler(IFluidHandler delegate, int purity) {
        this.delegate = delegate;
        this.purity = WaterSourcePurityRules.clampPurity(purity);
    }

    @Override
    public int getTanks() {
        return this.delegate.getTanks();
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return this.delegate.getFluidInTank(tank);
    }

    @Override
    public int getTankCapacity(int tank) {
        return this.delegate.getTankCapacity(tank);
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return this.delegate.isFluidValid(tank, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (!resource.isEmpty() && resource.is(net.minecraft.world.level.material.Fluids.WATER)) {
            FluidStack stamped = resource.copy();
            int existing = WaterSourcePurityRules.effectivePurity(
                    dev.minhnh.yetanotherthirst.compat.FluidPurityHelper.hasPurity(stamped),
                    dev.minhnh.yetanotherthirst.compat.FluidPurityHelper.getPurity(stamped));
            dev.minhnh.yetanotherthirst.compat.FluidPurityHelper.addPurity(
                    stamped, WaterSourcePurityRules.blendMin(existing, this.purity));
            return this.delegate.fill(stamped, action);
        }
        return this.delegate.fill(resource, action);
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        return this.delegate.drain(resource, action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        return this.delegate.drain(maxDrain, action);
    }
}
