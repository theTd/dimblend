package dimblend.experience.gametest;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Sable 结构上的方块按结构当前世界位置取群系（Sable 本体 {@code biome_projection} 织入）的运行时检查。
 *
 * <p>plot 区块的群系是结构创建时烤进去的；修前结构开到别的群系后，结构方块的
 * {@code Level#getBiome} 仍返回创建时的群系。本用例组装后把世界侧群系改成与创建时不同的
 * 群系，断言结构方块读到的是世界侧的新群系；同时断言 plot 区块自身存的群系没被改动
 *（投影发生在查询层，不写存档）。</p>
 *
 * <p>依赖带 {@code biome_projection} 的 Sable 构建（localRuntime 的 cui-modded jar）；
 * 旧构建下本用例失败即为修前复现。不加 {@code @GameTestHolder}：引用 Sable 类，
 * 只由 DimBlend 在 Sable 在场时显式注册。</p>
 */
@PrefixGameTestTemplate(false)
public final class SableBiomeProjectionGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    private static final BlockPos BLOCK_REL = new BlockPos(1, 1, 1);
    /** 结构 tick 过（位姿/包围盒就位）再核验。 */
    private static final int SETTLE_TICKS = 5;

    private SableBiomeProjectionGameTests() {
    }

    // 独立 batch：改写世界群系会与同区块邻域的其他用例互踩，batch 之间顺序执行
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_biome_projection", timeoutTicks = 100)
    public static void subLevelBlockReadsWorldBiome(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(BLOCK_REL, Blocks.STONE);
        BlockPos world = helper.absolutePos(BLOCK_REL);

        // 先把世界侧定为平原：plot 区块创建时烤成它，随后改写成雪地
        ResourceKey<Biome> created = Biomes.PLAINS;
        ResourceKey<Biome> target = Biomes.SNOWY_PLAINS;
        GameTestBiomes.overwrite(level, world, created);

        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, world,
                BlockPos.betweenClosed(world, world), new BoundingBox3i(world, world));
        helper.assertTrue(subLevel != null, "assembly must succeed");

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos plot = BlockPos.containing(
                    subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(world)));
            helper.assertTrue(level.getBlockState(plot).is(Blocks.STONE),
                    "stone must have moved to plot pos " + plot);

            helper.assertTrue(created.equals(GameTestBiomes.keyOf(level.getBiome(plot))),
                    "baseline: plot block reads the creation biome " + created + " but was " + GameTestBiomes.keyOf(level.getBiome(plot)));

            GameTestBiomes.overwrite(level, world, target);
            helper.assertTrue(target.equals(GameTestBiomes.keyOf(level.getBiome(world))),
                    "world biome overwrite must take effect, got " + GameTestBiomes.keyOf(level.getBiome(world)));

            // 修后：结构方块读到世界侧的新群系
            ResourceKey<Biome> seen = GameTestBiomes.keyOf(level.getBiome(plot));
            helper.assertTrue(target.equals(seen),
                    "plot block must read the world biome " + target + " but read " + seen);

            // 投影只发生在查询层：plot 区块自身存的群系不变
            ResourceKey<Biome> stored = GameTestBiomes.keyOf(level.getChunk(new ChunkPos(plot).x, new ChunkPos(plot).z)
                    .getNoiseBiome(plot.getX() >> 2, plot.getY() >> 2, plot.getZ() >> 2));
            helper.assertTrue(created.equals(stored),
                    "plot chunk stored biome must stay " + created + " but was " + stored);
            helper.succeed();
        });
    }
}
