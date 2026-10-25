package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * N6 的 Sable 载具路径（列车开进旋转维度下界带）：分液池组装到子层级后，方块坐标变成
 * plot 坐标，但 {@link ItemDrainNetherEvaporation#isNetherBiome} 会经
 * {@code SableWorldPosition.projectBlock} 投影回世界坐标、再取世界群系；本用例把世界侧
 * 改成下界群系，断言 plot 上的分液池照样蒸发。与 {@code GutterOutletBiomeProjectionGameTests}
 * 同型（结构在旧群系创建、随后世界侧改群系），模板复用 {@code item_drain_refill}。
 *
 * <p>引用 Sable + Create 类，不加 {@code @GameTestHolder}：只由 {@code DimBlend} 在两者
 * 都在场时显式注册。群系改写收尾在 finally 恢复。</p>
 */
@PrefixGameTestTemplate(false)
public final class ItemDrainNetherEvaporationSableGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    private static final BlockPos DRAIN = new BlockPos(1, 1, 1);
    /** 结构组装/位姿就位后再读写 plot 方块实体。 */
    private static final int SETTLE_TICKS = 5;
    /** 105 tick：默认速率下落 10–11 拍（每拍 25 mB）。 */
    private static final int WAIT_TICKS = 105;

    private ItemDrainNetherEvaporationSableGameTests() {
    }

    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, timeoutTicks = 300,
            batch = "item_drain_nether_evap_sable")
    public static void subLevelDrainEvaporatesWhereWorldIsNether(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos world = helper.absolutePos(DRAIN);
        ResourceKey<Biome> previous = GameTestBiomes.keyOf(level.getBiome(world));
        GameTestBiomes.overwrite(level, world, Biomes.NETHER_WASTES);
        try {
            helper.setBlock(DRAIN, AllBlocks.ITEM_DRAIN.get().defaultBlockState());
            ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, world,
                    BlockPos.betweenClosed(world, world), new BoundingBox3i(world, world));
            helper.assertTrue(subLevel != null, "assembly must succeed");

            helper.runAfterDelay(SETTLE_TICKS, () -> {
                BlockPos plot = BlockPos.containing(
                        subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(world)));
                ItemDrainBlockEntity drain = (ItemDrainBlockEntity) level.getBlockEntity(plot);
                helper.assertTrue(drain != null, "item drain must have moved to plot pos " + plot);
                helper.assertTrue(ItemDrainNetherEvaporation.isNetherBiome(level, plot),
                        "precondition: projected world biome at plot " + plot + " must be nether");

                ItemDrainPipeRefill.openInsertion(drain);
                IFluidHandler handler = level.getCapability(
                        Capabilities.FluidHandler.BLOCK, plot, Direction.DOWN);
                helper.assertTrue(handler != null && handler.getTanks() >= 1,
                        "item drain on the sub-level must expose a DOWN fluid capability");
                int filled = handler.fill(new FluidStack(Fluids.WATER, 1000), IFluidHandler.FluidAction.EXECUTE);
                helper.assertTrue(filled == 1000, "tank must accept 1000 mb water, filled " + filled);

                helper.runAfterDelay(WAIT_TICKS, () -> {
                    try {
                        int stored = handler.getFluidInTank(0).getAmount();
                        int drained = 1000 - stored;
                        helper.assertTrue(drained >= 25,
                                "sub-level water in a nether world biome must evaporate, drained " + drained);
                        helper.assertTrue(drained % 25 == 0,
                                "evaporation must drain whole 25 mb beats, drained " + drained);
                        helper.succeed();
                    } finally {
                        GameTestBiomes.overwrite(level, world, previous);
                    }
                });
            });
        } catch (RuntimeException | Error e) {
            GameTestBiomes.overwrite(level, world, previous);
            throw e;
        }
    }
}
