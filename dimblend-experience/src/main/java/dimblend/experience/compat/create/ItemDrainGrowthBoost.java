package dimblend.experience.compat.create;

import java.util.ArrayList;
import java.util.List;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * 分液池催熟（原版骨粉逻辑，全局功能不限维度；保湿见 {@link ItemDrainIrrigation}）。
 *
 * <p>规则（需求口径）：存水不低于阈值（默认 200mb）时，每周期（默认 30 秒）在
 * 覆盖范围内<b>随机挑一株</b>植物走原版骨粉逻辑催熟；催熟<b>生效才扣</b>
 * 10mb 存水。低于阈值停止催熟（保湿不受影响），回水后自动恢复。</p>
 *
 * <p>“原版骨粉逻辑”= {@code BonemealableBlock} 三段式：
 * {@code isValidBonemealTarget} 筛候选 → {@code isBonemealSuccess} 判生效 →
 * {@code performBonemeal} 执行生长。扣水门 = {@code isBonemealSuccess} 通过
 * （即 {@code performBonemeal} 被调用），据此扣 10mb——注意两点：
 * ① 这与原版骨粉物品的消耗时机不同（原版对有效目标无论 success 与否都消耗骨粉），
 * 此处是“生效才扣”的需求口径；② 已知限制：个别 {@code performBonemeal} 实现
 * 可能零世界变化仍被计为生效并扣水（如高草上方被挡、草方块 128 次散布无蔓延位），
 * 严格“世界真变化才扣”需做前后快照检测，对蔓延型目标（变化发生在邻居）覆盖不全，
 * 故不采用。另：本路径不走物品/事件语义——不触发 NeoForge {@code BonemealEvent}，
 * 第三方靠骨粉事件拦截/联动的模组不会感知本催熟。催熟生效时在目标格发原版
 * {@link LevelEvent#PARTICLES_AND_SOUND_PLANT_GROWTH}（1505，与骨粉物品/发射器同一事件，
 * 客户端出绿色成长粒子 + 骨粉音效）。</p>
 *
 * <p>候选范围：“范围内一株植物”= 保湿同款 7x7（半径可配）柱体的
 * <b>池体同层 y 与其上一层 y+1</b>——作物长在同层土壤上方，只扫 y 层永远找不到
 * 作物；候选是两层内一切 {@code BonemealableBlock} 且
 * {@code isValidBonemealTarget} 的方块（含草方块/苔藓这类蔓延型，需求口径）。</p>
 *
 * <p>消耗口径：只认水（与保湿同一水箱，非水时保湿/催熟都不工作）；扣水走公开
 * capability（DOWN 面，理由同 {@link ItemDrainIrrigation}），SIMULATE 先验、
 * 不足则本拍不催不扣；扣水后手动标脏 + 同步（capability 排液不走 behaviour 的
 * updateFluids）。成本 0 = 免费催熟。每次周期只催一株，无论成功与否不重试。</p>
 */
public final class ItemDrainGrowthBoost {

    /** Mixin 侧持有的单池催熟状态（内存态，重启/卸载即丢，可重新上弦）。 */
    public static final class State {
        /** 距下次催熟的节拍数（1 节拍 = 10 tick，由调用方节拍驱动）；&le;0 表示待上弦。 */
        public int countdown;
    }

    /** Mixin 实现：提供 {@link State} 持有。 */
    public interface HasState {
        State dimblend$growthState();
    }

    /**
     * 催熟推进（调用方保证：服务端、总开关已开、约每 10 tick 一次）。
     * 刚启动/复位后先上弦满一个周期才首催（防止区块重载刷催熟）；
     * 到点后低于阈值/无目标/骨粉判定未生效/水不足都只跳过本拍，下一周期重试。
     * 热改周期配置只在下次上弦/击发重置时取新值，已上弦的倒计时沿用旧值到本次击发为止。
     */
    public static void tick(ServerLevel level, ItemDrainBlockEntity be, State state) {
        int beats = IrrigationMath.intervalBeats(Config.ITEM_DRAIN_GROWTH_INTERVAL_SECONDS.get() * 20);
        if (state.countdown <= 0) {
            state.countdown = beats;
            return;
        }
        if (--state.countdown > 0) {
            return;
        }
        state.countdown = beats;
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, be.getBlockPos(), Direction.DOWN);
        if (handler == null || handler.getTanks() < 1) {
            return;
        }
        FluidStack stored = handler.getFluidInTank(0);
        if (stored.isEmpty() || !stored.is(Fluids.WATER)
                || IrrigationMath.belowThreshold(stored.getAmount(), Config.ITEM_DRAIN_GROWTH_MIN_MB.get())) {
            return;
        }
        RandomSource random = level.getRandom();
        Target target = randomTarget(level, be.getBlockPos(), Config.ITEM_DRAIN_COVERAGE_RADIUS.get(), random);
        if (target == null) {
            return;
        }
        // 生效才扣：扣水门 = isBonemealSuccess 通过（performBonemeal 被调用）；
        // 零世界变化的边界见类 javadoc“已知限制”。未生效本拍不催也不扣，下一周期重新抽目标
        if (!target.plant.isBonemealSuccess(level, random, target.pos, target.state)) {
            return;
        }
        int cost = Config.ITEM_DRAIN_GROWTH_COST_MB.get();
        if (cost > 0) {
            // SIMULATE 先验再 EXECUTE：并发抽水导致不足时本拍不催不扣，残水留给后续周期
            FluidStack probe = new FluidStack(Fluids.WATER, cost);
            if (handler.drain(probe.copy(), FluidAction.SIMULATE).getAmount() < cost) {
                return;
            }
            grow(level, random, target);
            handler.drain(probe, FluidAction.EXECUTE);
            // capability 排液不走 behaviour 的 updateFluids（ItemDrain 的 fluidUpdateCallback
            // 是 no-op），这里手动标脏 + 同步，保证存档与护目镜显示正确
            be.setChanged();
            be.sendData();
        } else {
            grow(level, random, target);
        }
    }

    /** 执行原版骨粉生长并在目标格发原版骨粉成长粒子（1505，data=15 与骨粉物品一致）。 */
    private static void grow(ServerLevel level, RandomSource random, Target target) {
        target.plant.performBonemeal(level, random, target.pos, target.state);
        level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, target.pos, 15);
    }

    /** 一株催熟候选：目标格 + 判过 {@code isValidBonemealTarget} 的骨粉方块与其状态。 */
    private static final class Target {
        final BlockPos pos;
        final BlockState state;
        final BonemealableBlock plant;

        Target(BlockPos pos, BlockState state, BonemealableBlock plant) {
            this.pos = pos;
            this.state = state;
            this.plant = plant;
        }
    }

    /**
     * 在覆盖柱体（7x7 的 y 与 y+1 两层，边界数学见
     * {@link IrrigationMath#coverageCells}）内随机挑一株骨粉候选；无候选返回 null。
     * 未加载格跳过（与保湿一致，中心区块必已加载）。
     */
    private static Target randomTarget(ServerLevel level, BlockPos center, int radius, RandomSource random) {
        int[] cells = IrrigationMath.coverageCells(center.getX(), center.getY(), center.getZ(), radius);
        List<Target> candidates = new ArrayList<>();
        for (int y = cells[1]; y <= cells[1] + 1; y++) {
            for (int x = cells[0]; x <= cells[3]; x++) {
                for (int z = cells[2]; z <= cells[4]; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BonemealableBlock plant
                            && plant.isValidBonemealTarget(level, pos, state)) {
                        candidates.add(new Target(pos, state, plant));
                    }
                }
            }
        }
        return candidates.isEmpty() ? null : candidates.get(random.nextInt(candidates.size()));
    }

    /** 复位（开关关闭/BE 销毁时调用，多次调用安全）：丢弃倒计时，恢复后重新上弦满周期。 */
    public static void reset(State state) {
        state.countdown = 0;
    }

    private ItemDrainGrowthBoost() {
    }
}
