package dimblend.blocks.compat.rail;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 单槽、容量 {@link TrainTubeNetworkMath#CAPACITY_MB} mB 的流体接口，背后是整条分量的共享池。
 *
 * <p>换液规则（锁定判定 #2 的一部分，单测在 {@code TrainTubeNetworkMathTest}）：</p>
 * <ul>
 *   <li>同种流体（含全部 {@code DataComponents}）：只接受剩余空间，满了返回 0；</li>
 *   <li>不同流体：<b>SIMULATE 必须返回正数</b>（{@code min(100, 灌入量)}），否则 Create 的泵
 *       会认为"灌不进去"而卡死；<b>EXECUTE 先清空旧液再灌新液</b>。</li>
 * </ul>
 *
 * <p>抽取按池量截断；任何高压水泵（含跨车厢另一端的）都能抽同一份池。</p>
 */
public class TrainTubeFluidHandler implements IFluidHandler {

    private final ServerLevel level;
    private final BlockPos pos;

    public TrainTubeFluidHandler(ServerLevel level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    private FluidStack pool() {
        FluidStack stack = TrainTubeNetworkManager.pool(level, pos);
        return stack == null ? FluidStack.EMPTY : stack;
    }

    @Override
    public int getTanks() {
        return 1;
    }

    @Override
    public FluidStack getFluidInTank(int tank) {
        return pool().copy();
    }

    @Override
    public int getTankCapacity(int tank) {
        return TrainTubeNetworkMath.CAPACITY_MB;
    }

    @Override
    public boolean isFluidValid(int tank, FluidStack stack) {
        return stack != null && !stack.isEmpty();
    }

    @Override
    public int fill(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty()) {
            return 0;
        }
        FluidStack current = pool();
        int poolAmount = current.getAmount();
        boolean sameFluid = !current.isEmpty() && FluidStack.isSameFluidSameComponents(current, resource);
        int simulated = TrainTubeNetworkMath.simulateFill(poolAmount, sameFluid, resource.getAmount());
        if (action == FluidAction.EXECUTE && simulated > 0) {
            int accepted = TrainTubeNetworkMath.acceptedAmount(poolAmount, sameFluid, resource.getAmount(), simulated);
            int after = TrainTubeNetworkMath.poolAmountAfterFill(poolAmount, sameFluid, accepted);
            FluidStack next = sameFluid && poolAmount > 0
                    ? current.copyWithAmount(after)
                    : resource.copyWithAmount(after);
            TrainTubeNetworkManager.storePool(level, pos, next);
        }
        return simulated;
    }

    @Override
    public FluidStack drain(FluidStack resource, FluidAction action) {
        if (resource == null || resource.isEmpty()) {
            return FluidStack.EMPTY;
        }
        FluidStack current = pool();
        if (current.isEmpty() || !FluidStack.isSameFluidSameComponents(current, resource)) {
            return FluidStack.EMPTY;
        }
        return drainAmount(current, resource.getAmount(), action);
    }

    @Override
    public FluidStack drain(int maxDrain, FluidAction action) {
        return drainAmount(pool(), maxDrain, action);
    }

    private FluidStack drainAmount(FluidStack current, int wanted, FluidAction action) {
        int amount = TrainTubeNetworkMath.drainAmount(current.getAmount(), wanted);
        if (amount <= 0) {
            return FluidStack.EMPTY;
        }
        FluidStack drained = current.copyWithAmount(amount);
        if (action == FluidAction.EXECUTE) {
            int left = current.getAmount() - amount;
            TrainTubeNetworkManager.storePool(level, pos,
                    left <= 0 ? FluidStack.EMPTY : current.copyWithAmount(left));
        }
        return drained;
    }
}
