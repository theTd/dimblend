package dimblend.experience.compat.create;

import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import dimblend.experience.compat.sable.SableWorldPosition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

/**
 * N6 分液池下界蒸发（全局功能，不限维度；与水/下界群系有关，与催熟/保湿/回注互不依赖）。
 *
 * <p>规则（需求口径）：分液池位于<b>下界群系</b>时，池内存水以
 * `itemDrainNetherEvaporationMbPerSecond`（默认 50 mB/s，范围 0–1500，0=不蒸发）
 * 的速度持续蒸发，直到完全排空；<b>只蒸发水</b>（{@code stored.is(Fluids.WATER)}，
 * 与保湿/催熟同一判据），岩浆等其它流体原样保留。管道回注与蒸发可同时进行——
 * 蒸发只是每拍从当拍实存水里扣，回注把水补回来即继续扣，互不阻断。</p>
 *
 * <p>节拍：调用方（{@code ItemDrainNetherEvaporationMixin} 挂
 * `ItemDrainBlockEntity.tick()V` HEAD）保证服务端、开关已开、每 10 tick 调一次。
 * 10 tick = 0.5 秒，故默认 50 mB/s 每拍恰扣 25 mB。配置值不是 2 的倍数时用
 * 余数累加器保证长期平均仍为配置速率（例：1 mB/s 每两拍扣 1 mB），
 * 而不是每拍向下取整永远为 0。单拍扣量上限 = 当拍实存水量，排空即止。</p>
 *
 * <p>扣水口径与 N2 催熟一致：DOWN 面公开 capability、SIMULATE 先验再 EXECUTE、
 * 探头派生自实存 stack（保数据组件，见 2026-10-23 "cannot drain" 事故），
 * 扣完 {@code setChanged()} + {@code sendData()}（capability 排液不走 behaviour 的
 * updateFluids）。SIMULATE 不足时本拍不扣，已累计的整数部分不补扣（避免停机后集中补扣）。</p>
 *
 * <p>群系口径：`level.getBiome(pos)` 同时认原版 {@link BiomeTags#IS_NETHER} 与
 * NeoForge 约定标签 {@link Tags.Biomes#IS_NETHER}。Sable 结构上的分液池坐标是 plot
 * 坐标，plot 区块群系是结构创建时烤入的——Sable 本体 `biome_projection` 会让
 * `level.getBiome(plotPos)` 返回结构当前世界位置的群系（见 spec §已知限制）；
 * 这里再显式投影一次世界坐标（世界坐标幂等、未装 Sable 原样返回），
 * 与集水器 `GutterOutletBiomeProjectionMixin` 同口径，不依赖 Sable 构建版本。
 * 因此列车开进旋转维度的下界带、池子随车移动时，按当前世界位置判定并蒸发。</p>
 */
public final class ItemDrainNetherEvaporation {

    /** 调用方节拍：每 10 tick 一次（与保湿/催熟一致）。 */
    public static final int TICKS_PER_BEAT = 10;
    /** 原版服务端每秒 tick 数。 */
    private static final int TICKS_PER_SECOND = 20;

    /** Mixin 侧持有的单池状态（内存态；区块卸载/拆除即丢，重新加载后从 0 余数开始）。 */
    public static final class State {
        /** 不足 1 mB 的余数（0..19 个 1/20 mB），跨拍累加保证长期平均速率精确。 */
        public int remainder;
    }

    /** Mixin 实现：提供 {@link State} 持有。 */
    public interface HasState {
        State dimblend$netherEvaporationState();
    }

    /** 单拍结算结果：本拍应扣的整数 mB 与留给下一拍的余数。 */
    public record Charge(int amountMb, int remainder) {
    }

    /**
     * 纯函数：把「上一拍余数 + 本拍应蒸发的 1/20 mB 数」结算成整数 mB 与余数。
     * 20 为每秒 tick 数；{@code ticksPerBeat} 为调用方节拍（10）。
     * 速率/节拍非法时返回 0（不蒸发）。
     */
    public static Charge charge(int remainder, int mbPerSecond, int ticksPerBeat) {
        if (mbPerSecond <= 0 || ticksPerBeat <= 0) {
            return new Charge(0, 0);
        }
        long numerator = (long) Math.max(0, remainder) + (long) mbPerSecond * ticksPerBeat;
        return new Charge((int) (numerator / TICKS_PER_SECOND), (int) (numerator % TICKS_PER_SECOND));
    }

    /**
     * 蒸发推进（调用方保证：服务端、开关已开、约每 10 tick 一次）。
     * 非下界群系/非水/空池直接跳过；速率 0 也不扣。每次只扣当拍实存水量以内的部分。
     */
    public static void tick(ServerLevel level, ItemDrainBlockEntity be, State state) {
        int mbPerSecond = Config.ITEM_DRAIN_NETHER_EVAPORATION_MB_PER_SECOND.get();
        if (mbPerSecond <= 0) {
            state.remainder = 0;
            return;
        }
        if (!isNetherBiome(level, be.getBlockPos())) {
            // 离开下界带即停：余数清零，重新进入时从正常速率重新开始，不补算离开期间的水量
            state.remainder = 0;
            return;
        }
        Charge charge = charge(state.remainder, mbPerSecond, TICKS_PER_BEAT);
        state.remainder = charge.remainder();
        if (charge.amountMb() <= 0) {
            return;
        }
        IFluidHandler handler = level.getCapability(Capabilities.FluidHandler.BLOCK, be.getBlockPos(), Direction.DOWN);
        if (handler == null || handler.getTanks() < 1) {
            DimBlend.LOGGER.debug("drain evaporation skipped at {}: no DOWN fluid capability", be.getBlockPos());
            return;
        }
        FluidStack stored = handler.getFluidInTank(0);
        if (stored.isEmpty() || !stored.is(Fluids.WATER)) {
            state.remainder = 0;
            return;
        }
        // 单拍不超过实存水量：排空即止，绝不请求超过存量的排液
        int amount = Math.min(charge.amountMb(), stored.getAmount());
        // 探头派生自实存 stack（保数据组件）：FluidTank.drain 按类型+组件匹配，
        // 裸水探头对带组件的水（YAT 纯度等）会失配（2026-10-23 事故根因）
        FluidStack probe = stored.copyWithAmount(amount);
        if (handler.drain(probe.copy(), FluidAction.SIMULATE).getAmount() < amount) {
            DimBlend.LOGGER.debug("drain evaporation skipped at {}: cannot drain {} mb of {} (components={})",
                    be.getBlockPos(), amount, stored.getFluid(), stored.getComponentsPatch());
            return;
        }
        handler.drain(probe, FluidAction.EXECUTE);
        if (amount < charge.amountMb()) {
            // 存量不足整拍量：池子已排空，余数（含本拍未扣的整数部分）清零，
            // 避免回注后按"欠账"集中多扣
            state.remainder = 0;
        }
        // capability 排液不走 behaviour 的 updateFluids（ItemDrain 的 fluidUpdateCallback
        // 是 no-op），手动标脏 + 同步，保证存档与护目镜显示正确（与 N2 同口径）
        be.setChanged();
        be.sendData();
    }

    /**
     * 群系判定：投影到世界坐标后认下界标签。Sable 结构方块的 plot 坐标经
     * {@link SableWorldPosition#projectBlock} 换算；普通方块原样返回（幂等）。
     */
    public static boolean isNetherBiome(ServerLevel level, BlockPos pos) {
        BlockPos worldPos = SableWorldPosition.projectBlock(level, pos);
        Holder<Biome> biome = level.getBiome(worldPos);
        return biome.is(BiomeTags.IS_NETHER) || biome.is(Tags.Biomes.IS_NETHER);
    }

    /** 复位（开关关闭/BE 销毁时调用，多次调用安全）。 */
    public static void reset(State state) {
        state.remainder = 0;
    }

    private ItemDrainNetherEvaporation() {
    }
}
