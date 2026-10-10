package dimblend.experience.gametest;

import java.util.List;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * G6 岩浆熔毁（SableLavaMelt）的运行时检查：熔毁走 plot 改写、接触扫描与掉落生成
 * 都在真实世界里发生，纯单测覆盖不到，故全用 GameTest 核验。
 *
 * <p>节律事实（断言窗口的依据）：熔毁扫描每 5 tick 一轮，单轮预算 2 个载具游标轮询
 * （套件并发下载具众多，单载具扫描间隔可达数十 tick）；新接触从 0 起算不预支空窗。
 * 石头（硬度 1.5、需正确工具）熔毁需 37.5 tick 连续接触，黑曜石（50）需 1250 tick。
 * 因此熔穿断言一律轮询（不定死窗口），存活断言取远低于熔穿耗时的固定点。</p>
 *
 * <p>维度门/拟合/熔毁开关经 {@link GameTestSableRules} 引用计数共享覆写（vanilla
 * 不同 batch 并发执行，私有还原会互踩）；用例中段的临时翻转自己翻回。
 * 每条用例结束前清掉自己放的岩浆（±5 格），避免残源/残流熔穿并发用例的载具。
 * 不加 {@code @GameTestHolder}：引用 Sable 类，只由 DimBlend 在 Sable 在场时显式注册。</p>
 */
@PrefixGameTestTemplate(false)
public final class SableLavaMeltGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    private static final BlockPos VEHICLE_REL = new BlockPos(1, 2, 1);
    /** 结构 tick、物理静止与至少一次拟合/熔毁扫描的就绪窗口。 */
    private static final int SETTLE_TICKS = 15;

    private record SavedConfig(boolean limitedWater) {
    }

    private static SavedConfig enableRules() {
        GameTestSableRules.acquire();
        SavedConfig saved = new SavedConfig(Config.LIMITED_WATER.get());
        Config.LIMITED_WATER.set(false);
        return saved;
    }

    private static void restore(SavedConfig saved) {
        Config.LIMITED_WATER.set(saved.limitedWater());
        GameTestSableRules.release();
    }

    /** 组装结果：载具 + 方块在 plot 内的固定坐标（载具移动不改变 plot 内坐标）。 */
    private record Assembled(ServerSubLevel subLevel, Vec3 plotCenter) {
    }

    /** 组装单方块载具并断言成功（方块随即被搬进 plot，世界格留空）。 */
    private static Assembled assemble(GameTestHelper helper, BlockPos rel, Block block) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(rel, block);
        BlockPos world = helper.absolutePos(rel);
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, world,
                List.of(world), new BoundingBox3i(world, world));
        helper.assertTrue(subLevel != null, "assembly must succeed");
        Vec3 plotCenter = subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(world));
        return new Assembled(subLevel, plotCenter);
    }

    /** 方块当前的世界格：plot 内固定坐标经当前位姿正投影。 */
    private static BlockPos projectedCell(Assembled assembled) {
        return BlockPos.containing(assembled.subLevel().logicalPose().transformPosition(assembled.plotCenter()));
    }

    /** plot 方块当前状态：plot 存储就是同一 ServerLevel 里的真实区块（全局存储坐标）。 */
    private static BlockState plotState(ServerLevel level, Assembled assembled) {
        return level.getBlockState(BlockPos.containing(assembled.plotCenter()));
    }

    /** 清掉用例放置的岩浆（源与流）：残留会熔穿并发用例的载具。 */
    private static void clearLavaAround(ServerLevel level, BlockPos center, int radius) {
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.LAVA)) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /**
     * 轮询等待 plot 方块熔穿：每 5 tick 一查，熔穿后回调；attempts 耗尽则断言失败。
     * 不定死窗口——扫描间隔随并发载具数伸缩，固定窗口必然抖动。
     */
    private static void pollUntilMolten(GameTestHelper helper, ServerLevel level, Assembled assembled,
            int attemptsLeft, Runnable onMolten) {
        helper.assertTrue(attemptsLeft > 0,
                "block must melt within the polling window but was " + plotState(level, assembled));
        helper.runAfterDelay(5, () -> {
            if (plotState(level, assembled).isAir()) {
                onMolten.run();
            } else {
                pollUntilMolten(helper, level, assembled, attemptsLeft - 1, onMolten);
            }
        });
    }

    /**
     * 同格浇淋（"自身"格内有岩浆）：石头熔穿 + 浇淋期间岩浆不被拟合驱逐。
     * 掉落物会落进熔岩烧毁，故掉落断言挪到贴壳用例（那里熔穿格无岩浆）。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_basic", timeoutTicks = 300)
    public static void lavaMeltsStoneBlockAndKeepsLava(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.STONE);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos cell = projectedCell(assembled);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                    "precondition: projected cell must be fitted");
            level.setBlockAndUpdate(cell, Blocks.LAVA.defaultBlockState());
            helper.runAfterDelay(15, () -> {
                helper.assertTrue(plotState(level, assembled).is(Blocks.STONE),
                        "stone must survive 15 ticks of contact (needs ~37.5) but was "
                                + plotState(level, assembled));
                pollUntilMolten(helper, level, assembled, 24, () -> {
                    try {
                        helper.assertTrue(level.getFluidState(cell).is(FluidTags.LAVA),
                                "lava must not be evicted by the fit while melt is enabled but was "
                                        + level.getBlockState(cell));
                    } finally {
                        clearLavaAround(level, cell, 5);
                        restore(saved);
                    }
                    helper.succeed();
                });
            });
        });
    }

    /**
     * 贴壳浇淋（"周边半径 0.25 以内"）+ 掉落：岩浆只在投影格六邻、自身格保持空位——
     * 方块投影立方体必与至少一侧相邻格相触（亚格偏移只会把接触推向另一侧），
     * 纯邻接浇淋同样熔毁；熔穿格无岩浆，掉落物存活可断言（按掉落规则掉圆石）。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_adj", timeoutTicks = 300)
    public static void adjacentLavaMeltsShellBlockWithDrops(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.STONE);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos cell = projectedCell(assembled);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                    "precondition: projected cell must be fitted");
            for (Direction direction : Direction.values()) {
                level.setBlockAndUpdate(cell.relative(direction), Blocks.LAVA.defaultBlockState());
            }
            pollUntilMolten(helper, level, assembled, 24, () -> {
                try {
                    List<ItemEntity> drops = level.getEntities(
                            EntityTypeTest.forClass(ItemEntity.class),
                            new AABB(cell).inflate(3.0D),
                            entity -> entity.getItem().is(Items.COBBLESTONE));
                    helper.assertTrue(!drops.isEmpty(),
                            "molten stone must drop cobblestone near the projected cell " + cell);
                } finally {
                    clearLavaAround(level, cell, 6);
                    restore(saved);
                }
                helper.succeed();
            });
        });
    }

    /** 硬度只决定速度：黑曜石级（50）需 1250 tick，100 tick 浇淋后必须仍在。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_hard", timeoutTicks = 300)
    public static void obsidianResistsLavaWithinWindow(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.OBSIDIAN);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos cell = projectedCell(assembled);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                    "precondition: projected cell must be fitted");
            level.setBlockAndUpdate(cell, Blocks.LAVA.defaultBlockState());
            helper.runAfterDelay(100, () -> {
                try {
                    helper.assertTrue(plotState(level, assembled).is(Blocks.OBSIDIAN),
                            "obsidian must survive 100 ticks of contact (needs 1250) but was "
                                    + plotState(level, assembled));
                } finally {
                    clearLavaAround(level, cell, 5);
                    restore(saved);
                }
                helper.succeed();
            });
        });
    }

    /**
     * 配对开关语义：熔毁开时拟合不驱逐岩浆；熔毁关后退回旧行为——
     * 下一轮 refit 把岩浆顶成结构空位（且熔毁停摆、方块保留）。
     * 驱逐走载具 refit 轮询（verify 级联，多载具时十余 tick 才轮到），轮询等待。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_fit", timeoutTicks = 300)
    public static void fitEvictsLavaOnlyWhenMeltDisabled(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.STONE);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos cell = projectedCell(assembled);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                    "precondition: projected cell must be fitted");
            level.setBlockAndUpdate(cell, Blocks.LAVA.defaultBlockState());
            helper.runAfterDelay(10, () -> {
                helper.assertTrue(level.getFluidState(cell).is(FluidTags.LAVA),
                        "lava must persist while melt is enabled but was " + level.getBlockState(cell));
                Config.SABLE_LAVA_MELT.set(false);
                pollUntilEvicted(helper, level, assembled, cell, 24, () -> {
                    try {
                        helper.assertTrue(plotState(level, assembled).is(Blocks.STONE),
                                "stone must survive with melt disabled but was " + plotState(level, assembled));
                    } finally {
                        // 共享覆写口径：临时翻转须自己翻回（GameTestSableRules 中途不改值）
                        Config.SABLE_LAVA_MELT.set(true);
                        clearLavaAround(level, cell, 5);
                        restore(saved);
                    }
                    helper.succeed();
                });
            });
        });
    }

    /** 轮询等待拟合把熔岩格顶回结构空位（熔毁开关已关）。 */
    private static void pollUntilEvicted(GameTestHelper helper, ServerLevel level, Assembled assembled,
            BlockPos cell, int attemptsLeft, Runnable onEvicted) {
        helper.assertTrue(attemptsLeft > 0,
                "lava must be evicted by the fit once melt is disabled but was " + level.getBlockState(cell));
        helper.runAfterDelay(5, () -> {
            if (level.getBlockState(cell).is(Blocks.STRUCTURE_VOID)) {
                onEvicted.run();
            } else {
                pollUntilEvicted(helper, level, assembled, cell, attemptsLeft - 1, onEvicted);
            }
        });
    }

    private SableLavaMeltGameTests() {
    }
}
