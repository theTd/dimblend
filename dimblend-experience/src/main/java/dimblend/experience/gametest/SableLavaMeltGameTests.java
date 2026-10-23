package dimblend.experience.gametest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.VoidFitColumnTops;
import dimblend.experience.compat.sable.VoidFitTracker;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * G6 岩浆熔毁（SableLavaMelt）的运行时检查：熔毁走 plot 改写、接触扫描与掉落生成
 * 都在真实世界里发生，纯单测覆盖不到，故全用 GameTest 核验。
 *
 * <p>节律事实（断言窗口的依据）：熔毁扫描每 5 tick 一轮，拟合扫描每 REFIT_TICKS（钉 1）
 * tick 一轮，单扫描预算经 {@link GameTestSableRules} 钉满 64，避免同 batch 载具或
 * 上轮残留载具造成扫描饥饿。石头在默认 4 倍倍率下需 37.5 tick 连续接触，黑曜石需
 * 1250 tick；熔穿断言一律轮询，存活断言取远低于熔穿耗时的固定点。</p>
 *
 * <p>拟合就绪一律走 {@link #awaitFitted} 门（同步预载 plot 区块、拟合格与物理速度
 * 连续稳定才放行、双超时上限带转储）：GameTestServer 不限速，固定 tick 就绪窗口
 * 只对应几十毫秒墙钟，等不到 plot 区块经 Sable force-load ticket 异步兑现。</p>
 *
 * <p>维度门/拟合/熔毁开关经 {@link GameTestSableRules} 引用计数共享覆写，防止同
 * batch 并发用例的私有还原互踩；用例中段的临时翻转自己翻回。每条用例结束（含失败
 * 路径）清掉岩浆与围封、并 {@code markRemoved()} 自己的载具——结构拆解不会清理
 * 远处 plot 中的 Sable 子层级，残留会占用后续用例的扫描预算与游标。</p>
 *
 * <p>岩浆落点一律加围封：非 target 的邻格垫黑曜石后再放源，既防自家岩浆外流，
 * 也防外部水把源转成黑曜石。浇淋一律浇遍所有柱顶格：投影柱层随载具沉降的亚格
 * 偏移逐例不同，只浇单列柱顶可能构不成接触。</p>
 *
 * <p>不加 {@code @GameTestHolder}：引用 Sable 类，只由 DimBlend 在 Sable 在场时显式注册。
 * 浇淋音效用例经 NeoForge 的 {@code PlayLevelSoundEvent.AtPosition} 服务端观测：
 * {@code ServerLevel#playSound} 在服务端也发布该事件（播音位置/音效/声源可断言），
 * 无需真人客户端。</p>
 */
@PrefixGameTestTemplate(false)
public final class SableLavaMeltGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    private static final BlockPos VEHICLE_REL = new BlockPos(1, 2, 1);

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
        return assemble(helper, List.of(rel), i -> block);
    }

    /**
     * 组装多方块载具（{@code rels} 全放同一种方块），锚点取首方块。
     *
     * <p>组装后同步预载 plot 区块：拟合体素化只读 {@code getLoadedChunks()}，生产靠 Sable
     * 的 force-load ticket 异步兑现（墙钟耗时）；GameTestServer 不限速，固定 tick 窗口
     * 等不到兑现（2026-10-24 R5 实测：13 例误败于"15 tick 内未拟合"，R5-DBG 进一步拍到
     * plotChunks=1 而 targets=0 的"区块在但内容没就位"窗口）。测试只关心拟合/熔毁行为
     * 本身，预载把环境噪声挤出断言窗口。</p>
     */
    private static Assembled assemble(GameTestHelper helper, List<BlockPos> rels,
            java.util.function.IntFunction<Block> blockAt) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> worlds = new ArrayList<>();
        for (int i = 0; i < rels.size(); i++) {
            helper.setBlock(rels.get(i), blockAt.apply(i));
            worlds.add(helper.absolutePos(rels.get(i)));
        }
        BlockPos first = worlds.get(0);
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, first, worlds,
                new BoundingBox3i(first, worlds.get(worlds.size() - 1)));
        helper.assertTrue(subLevel != null, "assembly must succeed");
        preloadPlotChunks(level, subLevel);
        Vec3 plotCenter = subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(first));
        return new Assembled(subLevel, plotCenter);
    }

    /** 同步装载 plot 包围盒覆盖的全部区块（测试载具只有 1-4 个）。 */
    private static void preloadPlotChunks(ServerLevel level, ServerSubLevel subLevel) {
        BoundingBox3ic box = subLevel.getPlot().getBoundingBox();
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    /**
     * 拟合就绪门：tracker 建账且 target 非空、投影格为 structure_void、物理速度
     * 收敛且投影坐标跨轮不变才放行，避免定点浇淋后载具继续沉降跨格形成孤儿岩浆。
     * 超时由 await 先跑 {@code cleanup} 再失败。
     */
    private static void awaitFitted(GameTestHelper helper, ServerLevel level, Assembled assembled,
            Runnable cleanup, Runnable onReady) {
        GameTestSableRules.await(helper, "vehicle fit", new BooleanSupplier() {
            private BlockPos settledCell;

            @Override
            public boolean getAsBoolean() {
                VoidFitTracker tracker = VoidFitTracker.peek(level.dimension());
                VoidFitTracker.VehicleFit fit = tracker == null ? null
                        : tracker.vehicles().get(assembled.subLevel().getUniqueId());
                BlockPos cell = projectedCell(assembled);
                boolean fitted = fit != null && !fit.targetCells.isEmpty()
                        && level.getBlockState(cell).is(Blocks.STRUCTURE_VOID)
                        && GameTestSableRules.vehicleSettled(assembled.subLevel());
                if (!fitted) {
                    settledCell = null;
                    return false;
                }
                boolean settled = cell.equals(settledCell);
                settledCell = cell;
                return settled;
            }
        }, () -> dumpFit(level, assembled), cleanup, onReady);
    }

    /** 方块当前的世界格：plot 内固定坐标经当前位姿正投影。 */
    private static BlockPos projectedCell(Assembled assembled) {
        return BlockPos.containing(assembled.subLevel().logicalPose().transformPosition(assembled.plotCenter()));
    }

    /** plot 方块当前状态：plot 存储就是同一 ServerLevel 里的真实区块（全局存储坐标）。 */
    private static BlockState plotState(ServerLevel level, Assembled assembled) {
        return level.getBlockState(BlockPos.containing(assembled.plotCenter()));
    }

    /** 本用例载具的拟合账目（断言已被 tracker 跟踪）。 */
    private static VoidFitTracker.VehicleFit trackedFit(GameTestHelper helper, ServerLevel level,
            Assembled assembled) {
        VoidFitTracker.VehicleFit fit = VoidFitTracker.of(level.dimension()).vehicles()
                .get(assembled.subLevel().getUniqueId());
        helper.assertTrue(fit != null, "tracker must track the assembled vehicle");
        return fit;
    }

    /** 同柱浇淋对：已物化的柱顶格 + 同柱低于柱顶的已物化格。 */
    private record PourPair(BlockPos top, BlockPos belowTop) {
    }

    /**
     * 选浇淋柱：某 (x,z) 柱内已物化的真柱顶格 + 同柱低于柱顶的已物化格。低于柱顶的目标
     * 格若压中模板地板等真方块，放置守卫拒绝覆盖、永不物化——浇在那里既毁地板也无人
     * 驱逐（驱逐只经 verify 级 refit 重放"曾被物化"的格），故浇淋格一律从
     * {@code materializedCells} 里选（2026-10-24 定案）。
     */
    private static PourPair pickPourPair(GameTestHelper helper, ServerLevel level,
            VoidFitTracker.VehicleFit fit) {
        Map<Long, List<BlockPos>> byColumn = new HashMap<>();
        for (long cellLong : fit.targetCells) {
            if (!fit.materializedCells.contains(cellLong)) {
                continue;
            }
            BlockPos pos = BlockPos.of(cellLong);
            byColumn.computeIfAbsent(VoidFitColumnTops.columnKey(pos.getX(), pos.getZ()),
                    key -> new ArrayList<>()).add(pos);
        }
        for (List<BlockPos> column : byColumn.values()) {
            column.sort(Comparator.comparingInt(BlockPos::getY));
            if (column.size() < 2) {
                continue;
            }
            BlockPos top = column.get(column.size() - 1);
            // 柱顶口径以 target 全集的柱顶索引为准：物化格里的最高格未必是真柱顶
            // （真柱顶可能是压中真方块而未物化的格，那种柱整柱跳过）
            if (!VoidFitColumnTops.isTop(fit.columnTops, top.asLong())) {
                continue;
            }
            BlockPos belowTop = column.get(column.size() - 2);
            helper.assertTrue(level.getBlockState(top).is(Blocks.STRUCTURE_VOID)
                            && level.getBlockState(belowTop).is(Blocks.STRUCTURE_VOID),
                    "pour cells must be fitted with structure_void");
            return new PourPair(top, belowTop);
        }
        helper.fail("no column offers a materialized below-top cell; targets=" + dumpCells(fit.targetCells)
                + " materialized=" + dumpCells(fit.materializedCells));
        throw new IllegalStateException("unreachable");
    }

    /**
     * 在全部已物化柱顶格放岩浆源并逐格围封。压中模板结构块等真实方块的 target
     * 不会物化，既不能浇淋也不属于拟合驱逐语义，必须跳过。
     */
    private static List<BlockPos> placeLavaAtColumnTops(GameTestHelper helper, ServerLevel level,
            VoidFitTracker.VehicleFit fit, Set<BlockPos> sealed) {
        List<BlockPos> placed = new ArrayList<>();
        for (long cellLong : fit.targetCells) {
            if (!VoidFitColumnTops.isTop(fit.columnTops, cellLong)
                    || !fit.materializedCells.contains(cellLong)) {
                continue;
            }
            BlockPos top = BlockPos.of(cellLong);
            helper.assertTrue(level.getBlockState(top).is(Blocks.STRUCTURE_VOID),
                    "precondition: materialized column-top cell must be fitted but was "
                            + level.getBlockState(top) + " at " + top.toShortString());
            placed.add(top);
        }
        helper.assertTrue(!placed.isEmpty(), "at least one materialized column top must accept lava");
        Set<BlockPos> keepFluid = new LinkedHashSet<>(placed);
        for (BlockPos top : placed) {
            sealAroundLava(level, fit, top, keepFluid, sealed);
        }
        for (BlockPos top : placed) {
            level.setBlockAndUpdate(top, Blocks.LAVA.defaultBlockState());
        }
        return placed;
    }

    /**
     * 围封岩浆格：把非 target、非岩浆同伴的邻格（空气/ stray 流体）垫成黑曜石，阻断一切
     * 进出流体——自家岩浆不外流，并发用例的水也进不来（target 邻格是结构空位，流体
     * 本就进不去，跳过）。垫过的格子记入 {@code sealed}（去重）供 {@link #unseal} 清理。
     */
    private static void sealAroundLava(ServerLevel level, VoidFitTracker.VehicleFit fit,
            BlockPos lavaCell, Set<BlockPos> keepFluid, Set<BlockPos> sealed) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbor = lavaCell.relative(direction);
            if (fit.targetCells.contains(neighbor.asLong()) || keepFluid.contains(neighbor)
                    || sealed.contains(neighbor)) {
                continue;
            }
            BlockState state = level.getBlockState(neighbor);
            if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
                level.setBlockAndUpdate(neighbor, Blocks.OBSIDIAN.defaultBlockState());
                sealed.add(neighbor);
            }
        }
    }

    /** 撤掉围封（只拆本用例垫的黑曜石）。 */
    private static void unseal(ServerLevel level, Set<BlockPos> sealed) {
        for (BlockPos pos : sealed) {
            if (level.getBlockState(pos).is(Blocks.OBSIDIAN)) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** 断言失败时的诊断转储：格集合全集（本套件用单方块载具，只有数格）。 */
    private static String dumpCells(LongSet cells) {
        List<String> out = new ArrayList<>();
        for (long cell : cells) {
            BlockPos pos = BlockPos.of(cell);
            out.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
        }
        return out.toString();
    }

    /** 轮询失败时的拟合状态转储（扫描饥饿/未驱逐诊断用）：账目 + 存活载具数 + 每格实况。 */
    private static String dumpFit(ServerLevel level, Assembled assembled) {
        VoidFitTracker.VehicleFit fit = VoidFitTracker.of(level.dimension()).vehicles()
                .get(assembled.subLevel().getUniqueId());
        ServerSubLevelContainer container = ServerSubLevelContainer.getContainer(level);
        int alive = 0;
        if (container != null) {
            for (ServerSubLevel subLevel : container.getAllSubLevels()) {
                if (!subLevel.isRemoved()) {
                    alive++;
                }
            }
        }
        if (fit == null) {
            return "<untracked> aliveVehicles=" + alive;
        }
        List<String> states = new ArrayList<>();
        for (long cell : fit.targetCells) {
            BlockPos pos = BlockPos.of(cell);
            states.add(pos.getX() + "," + pos.getY() + "," + pos.getZ() + "="
                    + level.getBlockState(pos).getBlock());
        }
        return "pose=" + assembled.subLevel().logicalPose()
                + " verifyCountdown=" + fit.verifyCountdown
                + " targets=" + dumpCells(fit.targetCells)
                + " materialized=" + dumpCells(fit.materializedCells)
                + " aliveVehicles=" + alive
                + " cellStates=" + states;
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

    /** 失败路径统一出口：先自清（载具/岩浆/围封/配置翻转），再断言失败——不自清的残留会让后续批次连锁误败。 */
    private static void failAfter(GameTestHelper helper, Runnable cleanup, String message) {
        cleanup.run();
        helper.assertTrue(false, message);
    }

    /**
     * 轮询等待 plot 方块熔穿：每 5 tick 一查，熔穿后回调；attempts 耗尽先自清再失败。
     * 不定死窗口——扫描间隔随并发载具数伸缩，固定窗口必然抖动。
     */
    private static void pollUntilMolten(GameTestHelper helper, ServerLevel level, Assembled assembled,
            int attemptsLeft, Runnable onMolten, Runnable cleanup) {
        if (attemptsLeft <= 0) {
            failAfter(helper, cleanup, "block must melt within the polling window but was "
                    + plotState(level, assembled) + "; fit=" + dumpFit(level, assembled));
            return;
        }
        helper.runAfterDelay(5, () -> {
            if (plotState(level, assembled).isAir()) {
                onMolten.run();
            } else {
                pollUntilMolten(helper, level, assembled, attemptsLeft - 1, onMolten, cleanup);
            }
        });
    }

    /**
     * 轮询等待拟合把给定熔岩格全部顶回结构空位（经 verify 级 refit 核验漂移后重放）。
     * {@code cadenceTicks} 为轮询间隔：熔毁开关的临时翻转窗口要压到最小的场合传 1。
     */
    private static void pollUntilEvicted(GameTestHelper helper, ServerLevel level, Assembled assembled,
            List<BlockPos> cells, int cadenceTicks, int attemptsLeft, Runnable onEvicted, Runnable cleanup) {
        if (attemptsLeft <= 0) {
            List<String> states = new ArrayList<>();
            for (BlockPos cell : cells) {
                states.add(cell.toShortString() + "=" + level.getBlockState(cell));
            }
            failAfter(helper, cleanup, "lava must be evicted by the fit but was " + states
                    + "; fit=" + dumpFit(level, assembled));
            return;
        }
        helper.runAfterDelay(cadenceTicks, () -> {
            boolean allVoid = true;
            for (BlockPos cell : cells) {
                if (!level.getBlockState(cell).is(Blocks.STRUCTURE_VOID)) {
                    allVoid = false;
                    break;
                }
            }
            if (allVoid) {
                onEvicted.run();
            } else {
                pollUntilEvicted(helper, level, assembled, cells, cadenceTicks, attemptsLeft - 1,
                        onEvicted, cleanup);
            }
        });
    }

    /** 轮询等待浇淋嘶声出现：每 5 tick 一查，听到后回调；attempts 耗尽先自清再失败。 */
    private static void pollForSizzle(GameTestHelper helper,
            Consumer<PlayLevelSoundEvent.AtPosition> listener, int[] sizzles,
            int attemptsLeft, Runnable onHeard, Runnable cleanup) {
        if (sizzles[0] > 0) {
            onHeard.run();
            return;
        }
        if (attemptsLeft <= 0) {
            failAfter(helper, cleanup,
                    "melting block must sizzle block.fire.extinguish at its projected position within the window");
            return;
        }
        helper.runAfterDelay(5, () -> pollForSizzle(helper, listener, sizzles, attemptsLeft - 1, onHeard, cleanup));
    }

    /**
     * 柱顶浇淋（投影柱顶格内有岩浆）：石头熔穿 + 浇淋期间柱顶岩浆不被拟合驱逐。
     * （2026-10-23 柱顶收窄前本例为同格浇淋；收窄后低于柱顶的投影格岩浆照常驱逐，
     * 由 {@link #fitKeepsColumnTopLavaOnlyWhileMeltEnabled} 专测。）
     * 掉落物会落进熔岩烧毁，故掉落断言挪到贴壳用例（那里熔穿格无岩浆）。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_basic", timeoutTicks = 24000)
    public static void lavaMeltsStoneBlockAndKeepsLava(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.STONE);
        Set<BlockPos> sealed = new LinkedHashSet<>();
        Runnable cleanup = () -> {
            clearLavaAround(level, projectedCell(assembled), 5);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                BlockPos cell = projectedCell(assembled);
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                        "precondition: projected cell must be fitted");
                List<BlockPos> lavaCells = placeLavaAtColumnTops(helper, level, fit, sealed);
                helper.runAfterDelay(15, () -> {
                    if (!plotState(level, assembled).is(Blocks.STONE)) {
                        failAfter(helper, cleanup,
                                "stone must survive 15 ticks of contact (needs ~37.5) but was "
                                        + plotState(level, assembled));
                        return;
                    }
                    pollUntilMolten(helper, level, assembled, 40, () -> {
                        try {
                            for (BlockPos top : lavaCells) {
                                helper.assertTrue(level.getFluidState(top).is(FluidTags.LAVA),
                                        "column-top lava must not be evicted by the fit while melt is enabled but was "
                                                + level.getBlockState(top) + " at " + top.toShortString());
                            }
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    }, cleanup);
                });
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    /**
     * 贴壳浇淋（"周边半径 0.25 以内"）+ 掉落：岩浆只在投影格六邻、自身格保持空位。
     * 掉落物生成后可能立即落入周围熔岩烧毁，因此用实体入图事件核验圆石确实生成，
     * 不把后续是否存活误当成掉落规则。六邻岩浆逐格围封，防外部流体把源染成黑曜石。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_adj", timeoutTicks = 24000)
    public static void adjacentLavaMeltsShellBlockWithDrops(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.STONE);
        Set<BlockPos> sealed = new LinkedHashSet<>();
        int[] cobblestoneDrops = new int[1];
        Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> dropListener = event -> {
            if (event.getLevel() == level && event.getEntity() instanceof ItemEntity item
                    && item.getItem().is(Items.COBBLESTONE)
                    && item.position().distanceToSqr(Vec3.atCenterOf(projectedCell(assembled))) <= 36.0D) {
                cobblestoneDrops[0]++;
            }
        };
        Runnable cleanup = () -> {
            NeoForge.EVENT_BUS.unregister(dropListener);
            clearLavaAround(level, projectedCell(assembled), 6);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                BlockPos cell = projectedCell(assembled);
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                        "precondition: projected cell must be fitted");
                NeoForge.EVENT_BUS.addListener(
                        net.neoforged.neoforge.event.entity.EntityJoinLevelEvent.class, dropListener);
                Set<BlockPos> lavaCells = new LinkedHashSet<>();
                for (Direction direction : Direction.values()) {
                    lavaCells.add(cell.relative(direction));
                }
                for (BlockPos lavaCell : lavaCells) {
                    sealAroundLava(level, fit, lavaCell, lavaCells, sealed);
                }
                for (BlockPos lavaCell : lavaCells) {
                    level.setBlockAndUpdate(lavaCell, Blocks.LAVA.defaultBlockState());
                }
                pollUntilMolten(helper, level, assembled, 40, () -> {
                    try {
                        helper.assertTrue(cobblestoneDrops[0] > 0,
                                "molten stone must spawn cobblestone near the projected cell " + cell);
                    } finally {
                        cleanup.run();
                    }
                    helper.succeed();
                }, cleanup);
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    /** 硬度只决定速度：黑曜石级（50）需 1250 tick，100 tick 浇淋后必须仍在。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_hard", timeoutTicks = 24000)
    public static void obsidianResistsLavaWithinWindow(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.OBSIDIAN);
        Set<BlockPos> sealed = new LinkedHashSet<>();
        Runnable cleanup = () -> {
            clearLavaAround(level, projectedCell(assembled), 5);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                BlockPos cell = projectedCell(assembled);
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                        "precondition: projected cell must be fitted");
                // 柱顶收窄（2026-10-23）后岩浆须放在柱顶格才不被驱逐、才构成浇淋；浇遍所有柱顶（沉降深度逐例不同）
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                placeLavaAtColumnTops(helper, level, fit, sealed);
                helper.runAfterDelay(100, () -> {
                    try {
                        helper.assertTrue(plotState(level, assembled).is(Blocks.OBSIDIAN),
                                "obsidian must survive 100 ticks of contact (needs 1250) but was "
                                        + plotState(level, assembled));
                    } finally {
                        cleanup.run();
                    }
                    helper.succeed();
                });
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    /**
     * 浇淋音效：熔毁进行中的方块每轮扫描在其世界侧投影位置播
     * {@code block.fire.extinguish}。黑曜石（50）需 1250 tick，窗口内只会累计进度
     * 不会熔穿——听到嘶声时方块必须还在。观测断言四要素：同一 level、同一音效、
     * BLOCKS 声源、位置在投影格附近（音效若落在 plot 存储区则远处听不见，
     * 正是 {@code PhysicsBogeyBrakeSoundMixin} 防过的坐标混用事故）。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_sound", timeoutTicks = 24000)
    public static void meltingBlockSizzlesAtProjectedPosition(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.OBSIDIAN);
        Set<BlockPos> sealed = new LinkedHashSet<>();
        int[] sizzles = new int[1];
        Consumer<PlayLevelSoundEvent.AtPosition> listener = event -> {
            if (event.getLevel() != level || event.getSound() == null
                    || event.getSound().value() != SoundEvents.FIRE_EXTINGUISH
                    || event.getSource() != SoundSource.BLOCKS) {
                return;
            }
            if (event.getPosition().distanceToSqr(Vec3.atCenterOf(projectedCell(assembled))) <= 4.0D) {
                sizzles[0]++;
            }
        };
        Runnable cleanup = () -> {
            NeoForge.EVENT_BUS.unregister(listener);
            clearLavaAround(level, projectedCell(assembled), 5);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                BlockPos cell = projectedCell(assembled);
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                        "precondition: projected cell must be fitted");
                NeoForge.EVENT_BUS.addListener(PlayLevelSoundEvent.AtPosition.class, listener);
                // 柱顶收窄（2026-10-23）后岩浆须放在柱顶格才不被驱逐、才构成浇淋；浇遍所有柱顶（沉降深度逐例不同）
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                placeLavaAtColumnTops(helper, level, fit, sealed);
                pollForSizzle(helper, listener, sizzles, 40, () -> {
                    try {
                        helper.assertTrue(plotState(level, assembled).is(Blocks.OBSIDIAN),
                                "sizzle must play while the block is still melting but was "
                                        + plotState(level, assembled));
                    } finally {
                        cleanup.run();
                    }
                    helper.succeed();
                }, cleanup);
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    /**
     * 柱顶收窄（2026-10-23，G6 遮顶不导流）的配对开关语义，同一载具两阶段核验：
     * 熔毁<b>开启</b>时同柱低于柱顶的投影格内岩浆照常驱逐成结构空位、柱顶格保留浇淋
     * （从上方流下的岩浆在车顶线截断、舱内不积不导流，仅柱顶保留并从车顶逐层熔穿）；
     * 熔毁<b>关闭</b>后下一轮 verify 级 refit 把柱顶岩浆也顶成结构空位。
     * 两阶段挂同一载具，消除两条用例并发争抢全局熔毁开关的互踩窗口（本例是唯一的
     * 开关翻转者，翻转窗口压到"关→驱逐→立即翻回"的几十 tick）。
     *
     * <p>载具用两格高黑曜石柱：单方块载具沉降静止后投影柱只有 2 格高、低于柱顶的目标格
     * 恰是模板地板真方块（放置守卫拒绝覆盖真方块，永不物化，浇了也无人驱逐——
     * 2026-10-24 R4/R5 该设计两度误败的归因之一）；加高一格后柱中段才有可物化的
     * 低于柱顶格。黑曜石需 1250 tick 才熔穿，窗口内排除熔穿改变柱结构的干扰。</p>
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_fit", timeoutTicks = 24000)
    public static void fitKeepsColumnTopLavaOnlyWhileMeltEnabled(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper,
                List.of(VEHICLE_REL, VEHICLE_REL.above()), i -> Blocks.OBSIDIAN);
        Set<BlockPos> sealed = new LinkedHashSet<>();
        Runnable cleanup = () -> {
            // 共享覆写口径：临时翻转须自己翻回（GameTestSableRules 中途不改值）
            Config.SABLE_LAVA_MELT.set(true);
            clearLavaAround(level, projectedCell(assembled), 5);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                PourPair pair = pickPourPair(helper, level, fit);
                Set<BlockPos> pairCells = Set.of(pair.top(), pair.belowTop());
                sealAroundLava(level, fit, pair.top(), pairCells, sealed);
                sealAroundLava(level, fit, pair.belowTop(), pairCells, sealed);
                level.setBlockAndUpdate(pair.top(), Blocks.LAVA.defaultBlockState());
                level.setBlockAndUpdate(pair.belowTop(), Blocks.LAVA.defaultBlockState());
                // 阶段一（熔毁开）：低于柱顶的岩浆被 verify 级 refit 驱逐，柱顶岩浆保留
                pollUntilEvicted(helper, level, assembled, List.of(pair.belowTop()), 5, 24, () -> {
                    if (!level.getFluidState(pair.top()).is(FluidTags.LAVA)) {
                        failAfter(helper, cleanup,
                                "column-top lava must be kept while melt is enabled but was "
                                        + level.getBlockState(pair.top()) + " at "
                                        + pair.top().toShortString());
                        return;
                    }
                    if (!plotState(level, assembled).is(Blocks.OBSIDIAN)) {
                        failAfter(helper, cleanup, "obsidian must survive phase one but was "
                                + plotState(level, assembled));
                        return;
                    }
                    // 阶段二（熔毁关）：柱顶岩浆同样被驱逐；观察到立即翻回，压缩互踩窗口
                    Config.SABLE_LAVA_MELT.set(false);
                    pollUntilEvicted(helper, level, assembled, List.of(pair.top()), 1, 120, () -> {
                        Config.SABLE_LAVA_MELT.set(true);
                        try {
                            helper.assertTrue(plotState(level, assembled).is(Blocks.OBSIDIAN),
                                    "obsidian must survive with melt toggled off but was "
                                            + plotState(level, assembled));
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    }, cleanup);
                }, cleanup);
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    /**
     * 免疫标签接线（2026-10-14 增补）：lava_melt_immune 必须在运行时解析出标签绑定——
     * 标签文件名与 {@code SableLavaMelt#LAVA_MELT_IMMUNE} 常量漂移会让转向架等部件的
     * 豁免静默失效（正是本特性修过的事故型）。本环境无 simurail，嵌套条目按可选跳过、
     * 标签内容为空，但绑定必须存在（全可选跳过的空标签仍绑定，见 TagLoader#build 按
     * 文件键建表）。id 字面量与 SableLavaMelt 常量互为镜像，改动须同步。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_immune", timeoutTicks = 100)
    public static void lavaMeltImmuneTagIsBound(GameTestHelper helper) {
        TagKey<Block> immune = TagKey.create(Registries.BLOCK,
                ResourceLocation.fromNamespaceAndPath(NAMESPACE, "lava_melt_immune"));
        helper.assertTrue(
                helper.getLevel().registryAccess().lookupOrThrow(Registries.BLOCK).get(immune).isPresent(),
                "lava_melt_immune tag must be bound (data/dimblend_experience/tags/block/lava_melt_immune.json)");
        helper.succeed();
    }

    private SableLavaMeltGameTests() {
    }
}
