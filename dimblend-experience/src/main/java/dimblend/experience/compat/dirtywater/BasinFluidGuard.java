package dimblend.experience.compat.dirtywater;

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 工作盆方块流体能力的脏水闸门。
 *
 * <p>在 {@code BasinBlockEntity#addBehaviours} 的 TAIL 把 {@code fluidCapability} 包一层：
 * 脏水且当前意图不允许时 {@code fill} 返回 0。<b>返回 0 是管路堵塞的全部机制</b>——
 * {@code CombinedTankWrapper} 汇总 0 → {@code FluidNetwork} 的 simulate pass 把目标从
 * {@code availableOutputs} 摘掉、execute pass 不抽源罐，源与管网都保留流体、不溢出；
 * {@code fluid:copper_tap} 的 {@code tryProcess} 同样只在 {@code fill(..., SIMULATE) > 0}
 * 之后才抽源罐。{@code drain}（盆往外出流）原样透传。</p>
 */
public final class BasinFluidGuard implements IFluidHandler {

    private final BasinBlockEntity basin;
    private final IFluidHandler delegate;

    public BasinFluidGuard(BasinBlockEntity basin, IFluidHandler delegate) {
        this.basin = basin;
        this.delegate = delegate;
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
        if (!DirtyWaterCompat.purityAvailable()) {
            return this.delegate.isFluidValid(tank, stack);
        }
        if (DirtyWaterPurity.isDirtyWaterFluid(stack) && !BasinDirtyWaterPolicy.allowsDirtyWater(this.basin)) {
            return false;
        }
        return this.delegate.isFluidValid(tank, stack);
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (!DirtyWaterCompat.purityAvailable()) {
            return this.delegate.fill(resource, action);
        }
        if (DirtyWaterPurity.isDirtyWaterFluid(resource) && !BasinDirtyWaterPolicy.allowsDirtyWater(this.basin)) {
            return 0;
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
