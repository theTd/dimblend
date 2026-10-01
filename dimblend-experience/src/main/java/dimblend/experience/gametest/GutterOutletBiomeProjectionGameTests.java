package dimblend.experience.gametest;

import com.adonis.fluid.block.GutterOutlet.GutterOutletBlockEntity;
import com.adonis.fluid.registry.CFBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Sable 结构上的集水器按结构当前所在世界位置判定降水（{@code GutterOutletBiomeProjectionMixin} +
 * Sable 本体 {@code biome_projection}）的端到端复现：结构在雪地创建，随后世界侧群系变成会下雨的
 * 平原，雨天集水器应收到水。修前（结构方块读 plot 烤入的雪地群系）按雪地判定，收不到水
 * （细雪收集开启则收细雪，关闭则什么都不收）。
 *
 * <p>需要 Sable 与 Create: Fluid 同时进 dev 运行：{@code -PwithFluid}（Sable 为 localRuntime 常驻）。
 * 不加 {@code @GameTestHolder}：引用 Fluid 类，只由 DimBlend 在两者都在场时显式注册。模板复用
 * {@code item_drain_refill}（3×3×3 空平台），集水器放 y=1 z=1。</p>
 */
@PrefixGameTestTemplate(false)
public final class GutterOutletBiomeProjectionGameTests {

    private static final BlockPos GUTTER_REL = new BlockPos(1, 1, 1);
    private static final int SETTLE_TICKS = 5;
    /** 降水 1 mB/s 的累加器每 tick +1、满 20 灌 1 mB；留足余量。下雨强度从 1.0 起每 tick 衰减 0.01，须在 80 tick 内测完。 */
    private static final int COLLECT_TICKS = 50;

    private GutterOutletBiomeProjectionGameTests() {
    }

    // 独立 batch：改写世界群系/天气会与同区块邻域的其他用例互踩，batch 之间顺序执行
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", batch = "gutter_biome_projection", timeoutTicks = 200)
    public static void gutterOnSubLevelCollectsRainWhereWorldIsNotSnowy(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos world = helper.absolutePos(GUTTER_REL);

        // 雪地里创建结构：plot 区块烤成雪地群系
        GameTestBiomes.overwrite(level, world, Biomes.SNOWY_PLAINS);
        helper.setBlock(GUTTER_REL, CFBlocks.GUTTER_OUTLET.get().defaultBlockState());
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, world,
                BlockPos.betweenClosed(world, world), new BoundingBox3i(world, world));
        helper.assertTrue(subLevel != null, "assembly must succeed");

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos plot = BlockPos.containing(
                    subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(world)));
            GutterOutletBlockEntity gutter = (GutterOutletBlockEntity) level.getBlockEntity(plot);
            helper.assertTrue(gutter != null, "gutter outlet must have moved to plot pos " + plot);
            helper.assertTrue(GameTestBiomes.keyOf(level.getBiome(plot)).equals(Biomes.SNOWY_PLAINS),
                    "baseline: structure starts in the snowy biome");

            // 结构"开到"会下雨的平原，并开始下雨
            GameTestBiomes.overwrite(level, world, Biomes.PLAINS);
            level.setWeatherParameters(0, 6000, true, false);
            level.setRainLevel(1.0F);
            helper.assertTrue(level.isRaining(), "precondition: must be raining");
            helper.assertTrue(level.canSeeSky(plot.above()), "precondition: gutter must see the sky at " + plot.above());

            helper.runAfterDelay(COLLECT_TICKS, () -> {
                FluidStack collected = gutter.getFluid();
                helper.assertFalse(collected.isEmpty(), "gutter must have collected precipitation");
                helper.assertTrue(collected.getFluid().isSame(Fluids.WATER),
                        "rain over a non-snowy world must collect water, got " + collected.getFluidType());
                helper.succeed();
            });
        });
    }
}
