package dimblend.experience.compat.create;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.FarmlandWaterManager;
import net.neoforged.neoforge.common.ticket.AABBTicket;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * 分液池保湿（免费保湿、无催熟，全局功能不限维度；催熟见 {@link ItemDrainGrowthBoost}）。
 *
 * <p>原理：水箱只要有水（无最低水量门槛、不消耗水量），分液池就持有一个 NeoForge
 * {@code FarmlandWaterManager} 的 AABB 票据，覆盖周围同层方形土壤；原版
 * {@code FarmBlock.randomTick} 的 {@code isNearWater} 认票据后把范围内耕地维持在
 * {@code MOISTURE=7}。票据只解决“维持”，建票时主动把范围内耕地拉满一次
 * （flag 与原版 randomTick 一致），其余时间（含新开垦干土）走原版 randomTick 节奏。</p>
 *
 * <p>水源事实（Create 6.0.10 {@code ItemDrainBlockEntity} 源码口径）：
 * 内置水箱 {@code SmartFluidTankBehaviour.single(this, 1500)}（1.5 桶），
 * 常态 {@code forbidInsertion}——管道灌不进去，水只能靠分液物品进来；
 * {@code allowExtraction} 常开，催熟扣水走公开 capability 即可，无需 accessor。
 * UP 面 capability 为 null，这里固定用 DOWN 面读取。</p>
 *
 * <p>调用方（Mixin）：服务端每 10 tick 调一次 {@link #tick}；BE 销毁时调
 * {@link #drop} 摘票。票据本身是内存态（区块卸载由
 * {@code FarmlandWaterManager} 按 master chunk 自动清理），水位/开关恢复后
 * 下一拍自动重建。</p>
 */
public final class ItemDrainIrrigation {

    /** Mixin 侧持有的单池状态（内存态，重启/卸载即丢，票据可重建）。 */
    public static final class State {
        /** 当前有效的保湿票据；null 或 !isValid 表示无覆盖。 */
        public AABBTicket ticket;
        /** 建票时的半径；Config 热改半径后靠此比对触发重建。 */
        public int ticketRadius;
    }

    /** Mixin 实现：提供 {@link State} 持有。 */
    public interface HasState {
        State dimblend$irrigationState();
    }

    /**
     * 保湿推进（调用方保证：服务端、总开关已开、约每 10 tick 一次）。
     * 空水箱/非水/capability 缺失时摘票返回；否则建票并维持。
     * 保湿不消耗水量，也没有最低水量门槛——只要有水就保湿。
     */
    public static void tick(ServerLevel level, ItemDrainBlockEntity be, State state) {
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, be.getBlockPos(), Direction.DOWN);
        if (handler == null || handler.getTanks() < 1) {
            drop(state);
            return;
        }
        FluidStack stored = handler.getFluidInTank(0);
        if (stored.isEmpty() || !stored.is(Fluids.WATER)) {
            drop(state);
            return;
        }
        int radius = Config.ITEM_DRAIN_COVERAGE_RADIUS.get();
        if (state.ticket == null || !state.ticket.isValid() || state.ticketRadius != radius) {
            drop(state);
            double[] bounds = IrrigationMath.coverageBounds(be.getBlockPos().getX(), be.getBlockPos().getY(),
                    be.getBlockPos().getZ(), radius);
            state.ticket = FarmlandWaterManager.addAABBTicket(level,
                    new AABB(bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]));
            state.ticketRadius = radius;
            // 建票即灌溉一次：新票/水位恢复/半径改动后范围内干土不等 randomTick，直接变湿
            hydrateCoveredFarmland(level, be.getBlockPos(), radius);
        }
    }

    /**
     * 把覆盖范围内的耕地主动拉满到 {@code MOISTURE=7}（写盘 flag 与原版
     * {@code FarmBlock.randomTick} 一致，不触发邻居更新）。
     *
     * <p>只扫描与分液池同层的整数格（边界数学见
     * {@link IrrigationMath#coverageCells}）；跨区块边缘未加载格跳过
     * （中心区块必已加载，半径最大 8 时最多越界半个区块）。
     * 仅识别原版 {@code Blocks.FARMLAND}（本包无自定义耕地；
     * 继承 {@code FarmBlock} 的第三方土壤仍走票据被动保湿）。</p>
     */
    static void hydrateCoveredFarmland(ServerLevel level, BlockPos center, int radius) {
        int[] cells = IrrigationMath.coverageCells(center.getX(), center.getY(), center.getZ(), radius);
        for (int x = cells[0]; x <= cells[3]; x++) {
            for (int z = cells[2]; z <= cells[4]; z++) {
                BlockPos pos = new BlockPos(x, cells[1], z);
                if (!level.isLoaded(pos)) {
                    continue;
                }
                BlockState state = level.getBlockState(pos);
                if (!state.is(Blocks.FARMLAND)) {
                    continue;
                }
                if (state.getValue(FarmBlock.MOISTURE) < FarmBlock.MAX_MOISTURE) {
                    level.setBlock(pos, state.setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE), 2);
                }
            }
        }
    }

    /** 摘票（开关关闭/缺水/BE 销毁时调用，多次调用安全）。 */
    public static void drop(State state) {
        if (state.ticket != null) {
            state.ticket.invalidate();
            state.ticket = null;
        }
    }

    /**
     * 保湿覆盖说明（边界数学见 {@link IrrigationMath#coverageBounds}，
     * 耕地扫描的整数格见 {@link IrrigationMath#coverageCells}，
     * 纯函数可单元测试）：以分液池为中心、边长 2r+1 的<b>同层</b>方形（y..y+1）。
     * 仅覆盖与分液池等高的土壤层：池体坐高一格时脚下耕地、高一层以上的作物都不在
     * 范围内——需求“同等高度”口径（不等价于原版水，后者还滋润下一层）。
     */
    private ItemDrainIrrigation() {
    }
}
