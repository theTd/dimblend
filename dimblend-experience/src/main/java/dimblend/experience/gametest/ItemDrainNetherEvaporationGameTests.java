package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dimblend.experience.compat.create.ItemDrainNetherEvaporation;
import dimblend.experience.compat.create.ItemDrainPipeRefill;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * N6 分液池下界蒸发的运行时覆盖（{@link ItemDrainNetherEvaporation} + mixin；纯函数单测见
 * {@code ItemDrainNetherEvaporationTest}）：下界群系水按 50 mB/s 扣、排空即止；主世界群系
 * 不扣；下界群系岩浆不扣。群系用 {@link GameTestBiomes#overwrite} 改写测试点周围 3x3 区块
 * （{@code BiomeManager} 取样会跨 quart，只改单区块会读回混合结果），用例收尾在 finally
 * 里恢复原群系，避免污染同世界后续用例。
 *
 * <p>节拍：每 10 tick 一拍、默认每拍 25 mB；105 tick 内落 10–11 拍，故断言扣量是 25 的
 * 整数倍且不为 0，不钉具体拍数。模板复用 {@code item_drain_refill} 空平台。
 * 仅 Create 在场时注册（ModList 守卫，见 {@code DimBlend}）。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class ItemDrainNetherEvaporationGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    private static final BlockPos DRAIN = new BlockPos(1, 1, 1);
    /** 105 tick：默认速率下落 10–11 拍（每拍 25 mB），留出对齐余量。 */
    private static final int WAIT_TICKS = 105;

    private ItemDrainNetherEvaporationGameTests() {
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 200, batch = "item_drain_nether_evap_water")
    public static void waterEvaporatesInNetherBiome(GameTestHelper helper) {
        withBiome(helper, Biomes.NETHER_WASTES, () -> {
            placeDrain(helper);
            fill(helper, new FluidStack(Fluids.WATER, 1000), 1000);
            helper.runAfterDelay(WAIT_TICKS, () -> {
                try {
                    int stored = storedMb(helper);
                    int drained = 1000 - stored;
                    helper.assertTrue(drained >= 25,
                            "nether water must evaporate at least one 25 mb beat, drained " + drained);
                    helper.assertTrue(drained % 25 == 0,
                            "evaporation must drain whole 25 mb beats at default 50 mb/s, drained " + drained);
                    helper.assertTrue(stored > 0,
                            "1000 mb must not be fully drained within " + WAIT_TICKS + " ticks, stored " + stored);
                    helper.succeed();
                } finally {
                    restoreBiome(helper);
                }
            });
        });
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 200, batch = "item_drain_nether_evap_overworld")
    public static void waterStaysInOverworldBiome(GameTestHelper helper) {
        withBiome(helper, Biomes.PLAINS, () -> {
            placeDrain(helper);
            fill(helper, new FluidStack(Fluids.WATER, 1000), 1000);
            helper.runAfterDelay(WAIT_TICKS, () -> {
                try {
                    helper.assertTrue(storedMb(helper) == 1000,
                            "overworld water must not evaporate, stored " + storedMb(helper));
                    helper.succeed();
                } finally {
                    restoreBiome(helper);
                }
            });
        });
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 200, batch = "item_drain_nether_evap_lava")
    public static void nonWaterFluidStaysInNetherBiome(GameTestHelper helper) {
        withBiome(helper, Biomes.NETHER_WASTES, () -> {
            placeDrain(helper);
            fill(helper, new FluidStack(Fluids.LAVA, 1000), 1000);
            helper.runAfterDelay(WAIT_TICKS, () -> {
                try {
                    FluidStack stored = storedFluid(helper);
                    helper.assertTrue(stored.getAmount() == 1000,
                            "lava must not evaporate, stored " + stored.getAmount());
                    helper.assertTrue(stored.getFluid().isSame(Fluids.LAVA),
                            "stored fluid must remain lava, got " + stored.getFluid());
                    helper.succeed();
                } finally {
                    restoreBiome(helper);
                }
            });
        });
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 200, batch = "item_drain_nether_evap_empty")
    public static void evaporationEmptiesPoolWithoutGoingNegative(GameTestHelper helper) {
        withBiome(helper, Biomes.NETHER_WASTES, () -> {
            placeDrain(helper);
            // 100 mB < 4 拍 * 25 mB：排空后余量清零，不能因"欠账"继续扣/报负
            fill(helper, new FluidStack(Fluids.WATER, 100), 100);
            helper.runAfterDelay(WAIT_TICKS, () -> {
                try {
                    helper.assertTrue(storedMb(helper) == 0,
                            "small pool must be completely emptied, stored " + storedMb(helper));
                    helper.succeed();
                } finally {
                    restoreBiome(helper);
                }
            });
        });
    }

    /** 测试开始时改写群系；记录原群系供 {@link #restoreBiome} 恢复（失败路径也恢复）。 */
    private static void withBiome(GameTestHelper helper, ResourceKey<Biome> biome, Runnable setup) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(DRAIN);
        BIOME.set(new BiomeSnapshot(pos, GameTestBiomes.keyOf(level.getBiome(pos))));
        GameTestBiomes.overwrite(level, pos, biome);
        try {
            setup.run();
        } catch (RuntimeException | Error e) {
            restoreBiome(helper);
            throw e;
        }
    }

    /** 恢复 {@link #withBiome} 记录的原群系；多次调用安全（异步回调与失败路径可能各调一次）。 */
    private static void restoreBiome(GameTestHelper helper) {
        BiomeSnapshot snapshot = BIOME.get();
        if (snapshot != null) {
            BIOME.remove();
            GameTestBiomes.overwrite(helper.getLevel(), snapshot.pos(), snapshot.previous());
        }
    }

    // GameTest 用例串行执行；用 ThreadLocal 避免 static 可变态跨用例串味。
    private static final ThreadLocal<BiomeSnapshot> BIOME = new ThreadLocal<>();

    private record BiomeSnapshot(BlockPos pos, ResourceKey<Biome> previous) {
    }

    private static void placeDrain(GameTestHelper helper) {
        helper.setBlock(DRAIN, AllBlocks.ITEM_DRAIN.get().defaultBlockState());
    }

    private static void fill(GameTestHelper helper, FluidStack stack, int expected) {
        // Create 原生分液池水箱 forbidInsertion；生产中由 ItemDrainPipeRefillMixin 逐 tick 置位，
        // 同拍填充等不到首拍，直接调公开口（与 ItemDrainGrowthFireGameTests 同法）
        ItemDrainPipeRefill.openInsertion((ItemDrainBlockEntity) helper.getBlockEntity(DRAIN));
        IFluidHandler handler = handler(helper);
        int filled = handler.fill(stack, IFluidHandler.FluidAction.EXECUTE);
        helper.assertTrue(filled == expected, "tank must accept " + expected + " mb, filled " + filled);
    }

    private static int storedMb(GameTestHelper helper) {
        return storedFluid(helper).getAmount();
    }

    private static FluidStack storedFluid(GameTestHelper helper) {
        return handler(helper).getFluidInTank(0);
    }

    private static IFluidHandler handler(GameTestHelper helper) {
        IFluidHandler handler = helper.getLevel().getCapability(
                Capabilities.FluidHandler.BLOCK, helper.absolutePos(DRAIN), Direction.DOWN);
        helper.assertTrue(handler != null && handler.getTanks() >= 1,
                "item drain must expose a DOWN fluid capability");
        return handler;
    }
}
