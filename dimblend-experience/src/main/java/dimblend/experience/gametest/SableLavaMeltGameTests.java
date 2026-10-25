package dimblend.experience.gametest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.VoidFitApplier;
import dimblend.experience.compat.sable.VoidFitColumnTops;
import dimblend.experience.compat.sable.VoidFitTracker;
import dimblend.experience.compat.sable.VoidFitVoxelizer;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.joml.Vector3d;
import org.joml.Vector3dc;
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
 * 也防外部水把源转成黑曜石。同格浇淋用例浇遍所有柱顶格：投影柱层随载具沉降的亚格
 * 偏移逐例不同，只浇单列柱顶可能构不成接触。</p>
 *
 * <p>2026-10-26 屋顶阻断补测：自然浇淋（{@link #placeLavaAboveColumnTops}，熔岩停在
 * 柱顶空位上一格、靠 {@code SableLavaMelt} 的柱顶 +1 格接触口径浇熔）覆盖空心车厢、
 * 外挑甲板与逐层下降三个形状；柱规则（单格柱/间隙的封口格、无碰撞封口回退驱逐、
 * 缩放载具 refit 补封口）用合成 tracker 账本同步核验，不依赖真实载具物理。</p>
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

    /**
     * 组装结果：载具 + 锚点方块在 plot 内的固定坐标（载具移动不改变 plot 内坐标）+
     * 组装瞬间位姿快照（把任意 rel 格换算成 plot 坐标用，含空气格/舱内格）。
     */
    private record Assembled(ServerSubLevel subLevel, Vec3 plotCenter, Pose3d assemblyPose) {
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
        return new Assembled(subLevel, plotCenter, new Pose3d(subLevel.logicalPose()));
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

    /** rel 格在 plot 内的固定坐标：组装瞬间位姿逆投影（载具移动后仍有效，含空气/舱内格）。 */
    private static Vec3 plotPoint(GameTestHelper helper, Assembled assembled, BlockPos rel) {
        return assembled.assemblyPose().transformPositionInverse(Vec3.atCenterOf(helper.absolutePos(rel)));
    }

    /** plot 点当前的世界格。 */
    private static BlockPos projectPoint(Assembled assembled, Vec3 plotPoint) {
        return BlockPos.containing(assembled.subLevel().logicalPose().transformPosition(plotPoint));
    }

    /** rel 格当前的世界格（组装位姿钉 plot 坐标，当前位姿正投影）。 */
    private static BlockPos projectRel(GameTestHelper helper, Assembled assembled, BlockPos rel) {
        return projectPoint(assembled, plotPoint(helper, assembled, rel));
    }

    /** rel 方块在 plot 存储里的状态（全局存储坐标）。 */
    private static BlockState plotStateAt(GameTestHelper helper, ServerLevel level,
            Assembled assembled, BlockPos rel) {
        return level.getBlockState(BlockPos.containing(plotPoint(helper, assembled, rel)));
    }

    /** 世界格是否为岩浆/岩浆流（LiquidBlock + lava 标签，与 SableLavaMelt 的判定同口径）。 */
    private static boolean isLava(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().is(FluidTags.LAVA);
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
     * 自然浇淋：在全部已物化柱顶的上一格（拟合面之上的空气格）放岩浆源并围封。
     *
     * <p>这是"岩浆从上方流下"的真实落点——拟合格是保守覆盖，柱顶空位挡流体，熔岩停在
     * 柱顶上一格；{@code SableLavaMelt#touchesLava} 的柱顶 +1 格口径让它与柱顶方块
     * 构成接触。只在上一格不是 target、且是空气时放（避免压在别的投影/真实方块上）。</p>
     */
    private static List<BlockPos> placeLavaAboveColumnTops(ServerLevel level,
            VoidFitTracker.VehicleFit fit, Set<BlockPos> sealed) {
        List<BlockPos> placed = new ArrayList<>();
        for (long cellLong : fit.targetCells) {
            if (!VoidFitColumnTops.isTop(fit.columnTops, cellLong)
                    || !fit.materializedCells.contains(cellLong)) {
                continue;
            }
            BlockPos top = BlockPos.of(cellLong);
            if (!level.getBlockState(top).is(Blocks.STRUCTURE_VOID)) {
                continue;
            }
            BlockPos above = top.above();
            if (fit.targetCells.contains(above.asLong())) {
                continue;
            }
            if (!level.getBlockState(above).isAir()) {
                continue;
            }
            placed.add(above);
        }
        if (placed.isEmpty()) {
            return placed;
        }
        Set<BlockPos> keepFluid = new LinkedHashSet<>(placed);
        for (BlockPos above : placed) {
            sealAroundLava(level, fit, above, keepFluid, sealed);
        }
        for (BlockPos above : placed) {
            level.setBlockAndUpdate(above, Blocks.LAVA.defaultBlockState());
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
     * （2026-10-26 起"安全柱顶"还要求正下方仍在 target 内；本例的单方块载具在 scale=1
     * 下保守覆盖 ≥2 格、柱顶天然安全。低于柱顶的投影格岩浆照常驱逐，由
     * {@link #fitKeepsColumnTopLavaOnlyWhileMeltEnabled} 专测。）
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
                // 安全柱顶（2026-10-26）下岩浆放在柱顶格才不被驱逐、才构成同格浇淋；浇遍所有柱顶（沉降深度逐例不同）
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
                // 安全柱顶（2026-10-26）下岩浆放在柱顶格才不被驱逐、才构成同格浇淋；浇遍所有柱顶（沉降深度逐例不同）
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
     * 安全柱顶（2026-10-26，G6 遮顶不导流）的配对开关语义，同一载具两阶段核验：
     * 熔毁<b>开启</b>时同柱低于柱顶的投影格内岩浆照常驱逐成结构空位、安全柱顶格保留
     * 浇淋（从上方流下的岩浆在车顶线截断、舱内不积不导流，仅柱顶保留并从车顶逐层
     * 熔穿）；熔毁<b>关闭</b>后 verify 级 refit 把柱顶岩浆也顶成结构空位。
     *
     * <p>两阶段在同一服务端 tick 内直接调用生产 {@code refit(verify=true)}，避免等待
     * 自愈扫描期间载具继续漂移、浇淋格脱离 target 的测试时序噪声。载具用两格高黑曜石柱，
     * 保证柱中段存在可物化的低于柱顶格；黑曜石不会在断言期间熔穿。</p>
     *
     * <p>2026-10-26 稳定化：进本例先按当前位姿同步 refit 一次，浇淋对（
     * {@link #pickPourPair}）读到的 target 与第二次 refit 同 tick/同一位姿——套件并发
     * 载具增多后，就绪门只钉锚点格，全 target 仍可能在上次调度 refit 后微移，浇淋格
     * 脱靶成孤儿岩浆（HANDOFF §3 R6 同型）。</p>
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_fit", timeoutTicks = 24000)
    public static void fitKeepsColumnTopLavaOnlyWhileMeltEnabled(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper,
                List.of(VEHICLE_REL, VEHICLE_REL.above()), i -> Blocks.OBSIDIAN);
        Set<BlockPos> sealed = new LinkedHashSet<>();
        Runnable cleanup = () -> {
            Config.SABLE_LAVA_MELT.set(true);
            clearLavaAround(level, projectedCell(assembled), 5);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                VoidFitTracker tracker = VoidFitTracker.of(level.dimension());
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                // 就绪门只钉锚点格稳定，全 target 仍可能在上次调度 refit 后微移：先按
                // 当前位姿同步 refit 一次，让 pickPourPair 与下面第二次 refit 读到同一
                // tick/同一位姿的 target（2026-10-26 加测后并发载具增多，柱顶/次高格
                // 跨格抖动会让浇淋格脱靶——HANDOFF §3 R6 同型孤儿岩浆）
                helper.assertTrue(VoidFitApplier.refit(level, tracker, assembled.subLevel(), true),
                        "pre-refit must complete with melt enabled");
                PourPair pair = pickPourPair(helper, level, fit);
                Set<BlockPos> pairCells = Set.of(pair.top(), pair.belowTop());
                sealAroundLava(level, fit, pair.top(), pairCells, sealed);
                sealAroundLava(level, fit, pair.belowTop(), pairCells, sealed);
                level.setBlockAndUpdate(pair.top(), Blocks.LAVA.defaultBlockState());
                level.setBlockAndUpdate(pair.belowTop(), Blocks.LAVA.defaultBlockState());

                helper.assertTrue(VoidFitApplier.refit(level, tracker, assembled.subLevel(), true),
                        "verify refit must complete with melt enabled");
                helper.assertTrue(level.getBlockState(pair.belowTop()).is(Blocks.STRUCTURE_VOID),
                        "below-top lava must be evicted while melt is enabled");
                helper.assertTrue(level.getFluidState(pair.top()).is(FluidTags.LAVA),
                        "column-top lava must be kept while melt is enabled");
                helper.assertTrue(plotState(level, assembled).is(Blocks.OBSIDIAN),
                        "obsidian must survive phase one");

                Config.SABLE_LAVA_MELT.set(false);
                try {
                    helper.assertTrue(VoidFitApplier.refit(level, tracker, assembled.subLevel(), true),
                            "verify refit must complete with melt disabled");
                    helper.assertTrue(level.getBlockState(pair.top()).is(Blocks.STRUCTURE_VOID),
                            "column-top lava must be evicted when melt is disabled");
                    helper.assertTrue(plotState(level, assembled).is(Blocks.OBSIDIAN),
                            "obsidian must survive with melt toggled off");
                } finally {
                    Config.SABLE_LAVA_MELT.set(true);
                }
                cleanup.run();
                helper.succeed();
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }


    // ------------------------------------------------------------------
    // 2026-10-26 岩浆屋顶阻断：形状用例 + 柱规则（单格柱/间隙/多载具交集）
    // ------------------------------------------------------------------

    /**
     * 不变式：target 格只要不是该柱柱顶，就不得是熔岩——柱顶下方的投影格/封口格必须
     * 被空位顶住，熔岩不能停在柱内。
     */
    private static void assertNoLavaBelowColumnTops(GameTestHelper helper, ServerLevel level,
            VoidFitTracker.VehicleFit fit, String when) {
        for (long cellLong : fit.targetCells) {
            if (VoidFitColumnTops.isTop(fit.columnTops, cellLong)) {
                continue;
            }
            BlockPos pos = BlockPos.of(cellLong);
            helper.assertTrue(!isLava(level, pos),
                    "lava must not occupy a target cell below its column top (" + when + ") at "
                            + pos.toShortString() + "; targets=" + dumpCells(fit.targetCells));
        }
    }

    /** 不变式：最低一层拟合目标格的下一格不得是熔岩——熔岩没有从车底漏出。 */
    private static void assertNoLavaUnderHull(GameTestHelper helper, ServerLevel level,
            VoidFitTracker.VehicleFit fit, String when) {
        int minY = Integer.MAX_VALUE;
        for (long cellLong : fit.targetCells) {
            minY = Math.min(minY, BlockPos.getY(cellLong));
        }
        if (minY == Integer.MAX_VALUE) {
            return;
        }
        for (long cellLong : fit.targetCells) {
            BlockPos pos = BlockPos.of(cellLong);
            if (pos.getY() != minY) {
                continue;
            }
            BlockPos below = pos.below();
            helper.assertTrue(!isLava(level, below),
                    "lava must not leak below the hull's lowest fitted layer (" + when + ") at "
                            + below.toShortString());
        }
    }

    /** 这些 rel 格（舱内空气/外挑下沿）当前世界格不得有熔岩。 */
    private static void assertNoLavaAt(GameTestHelper helper, ServerLevel level,
            Assembled assembled, List<BlockPos> rels, String when) {
        for (BlockPos rel : rels) {
            BlockPos world = projectRel(helper, assembled, rel);
            helper.assertTrue(!isLava(level, world),
                    "lava must not appear at " + world.toShortString()
                            + " (" + when + ", rel=" + rel.toShortString() + ")");
        }
    }

    /** 该 (x,z) 世界柱的最高 target y（无柱返回 MIN_VALUE）。 */
    private static int columnTopY(VoidFitTracker.VehicleFit fit, int x, int z) {
        return fit.columnTops.get(VoidFitColumnTops.columnKey(x, z));
    }

    /** 该 (x,z) 世界柱在 [fromY,toY] 内最低的熔岩格 y；没有则 MAX_VALUE。 */
    private static int lowestLavaY(ServerLevel level, int x, int z, int fromY, int toY) {
        for (int y = fromY; y <= toY; y++) {
            if (isLava(level, new BlockPos(x, y, z))) {
                return y;
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * 3x3 空心车厢：地板 y=2、四周两格高墙 y=3/4、3x3 屋顶 y=5；舱内 (2,3,2)/(2,4,2)
     * 两层空气。屋顶中心柱只剩 1 格厚屋顶、其下就是舱内空气——单格柱/悬空柱顶缺口的
     * 典型形状（体素化不收舱内空气）。
     */
    private static Map<BlockPos, Block> hollowCabinShape() {
        Map<BlockPos, Block> shape = new LinkedHashMap<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                shape.put(new BlockPos(x, 2, z), Blocks.STONE);
                shape.put(new BlockPos(x, 5, z), Blocks.STONE);
                if (x != 2 || z != 2) {
                    shape.put(new BlockPos(x, 3, z), Blocks.STONE);
                    shape.put(new BlockPos(x, 4, z), Blocks.STONE);
                }
            }
        }
        return shape;
    }

    /**
     * 外挑甲板：3x3x2 实心基座（y=2/3）+ 3x3 甲板（y=4）+ 东侧 1x3 外挑甲板
     * （x=4, y=4）——外挑段下方全是空气，检验熔岩不会从甲板下沿漏下去。
     */
    private static Map<BlockPos, Block> deckOverhangShape() {
        Map<BlockPos, Block> shape = new LinkedHashMap<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                shape.put(new BlockPos(x, 2, z), Blocks.STONE);
                shape.put(new BlockPos(x, 3, z), Blocks.STONE);
                shape.put(new BlockPos(x, 4, z), Blocks.STONE);
            }
        }
        for (int z = 1; z <= 3; z++) {
            shape.put(new BlockPos(4, 4, z), Blocks.STONE);
        }
        return shape;
    }

    /**
     * 3x3 两层石板（y=3 黑曜石 / y=4 石头）：屋顶层熔掉后熔岩应恰好下降一层，被下一层
     * 投影挡住。下层用黑曜石（默认倍率需 1250 tick）保证断言窗口内不会跟着熔掉。
     */
    private static Map<BlockPos, Block> twoLayerDeckShape() {
        Map<BlockPos, Block> shape = new LinkedHashMap<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                shape.put(new BlockPos(x, 3, z), Blocks.OBSIDIAN);
                shape.put(new BlockPos(x, 4, z), Blocks.STONE);
            }
        }
        return shape;
    }

    /** 异型载具：按坐标排序保证首末为对角，再走统一组装路径。 */
    private static Assembled assembleShape(GameTestHelper helper, Map<BlockPos, Block> shape) {
        List<BlockPos> rels = new ArrayList<>(shape.keySet());
        rels.sort(Comparator.comparingInt((BlockPos pos) -> pos.getX())
                .thenComparingInt((BlockPos pos) -> pos.getY())
                .thenComparingInt((BlockPos pos) -> pos.getZ()));
        List<Block> blocks = new ArrayList<>(rels.size());
        for (BlockPos rel : rels) {
            blocks.add(shape.get(rel));
        }
        return assemble(helper, rels, i -> blocks.get(i));
    }

    /**
     * 空心车厢：从上方浇岩浆。屋顶完整期间舱内/柱顶下方/车底不得有熔岩；屋顶必须先熔，
     * 且屋顶熔掉时地板仍在（熔毁从屋顶开始）。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_roof", timeoutTicks = 24000)
    public static void hollowCabinRoofBlocksLavaAndMeltsFirst(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assembleShape(helper, hollowCabinShape());
        Set<BlockPos> sealed = new LinkedHashSet<>();
        List<BlockPos> interior = List.of(new BlockPos(2, 3, 2), new BlockPos(2, 4, 2));
        BlockPos roofRel = new BlockPos(2, 5, 2);
        BlockPos floorRel = new BlockPos(2, 2, 2);
        Runnable cleanup = () -> {
            clearLavaAround(level, projectedCell(assembled), 6);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                List<BlockPos> poured = placeLavaAboveColumnTops(level, fit, sealed);
                helper.assertTrue(!poured.isEmpty(), "cabin roof must accept lava poured from above");
                // 先给接触/扫描一点时间，再进轮询（15 tick 远低于石头 37.5 tick 熔穿点）
                helper.runAfterDelay(15, () -> pollCabinRoof(helper, level, assembled, interior,
                        roofRel, floorRel, 60, cleanup));
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    private static void pollCabinRoof(GameTestHelper helper, ServerLevel level, Assembled assembled,
            List<BlockPos> interior, BlockPos roofRel, BlockPos floorRel, int attemptsLeft,
            Runnable cleanup) {
        if (attemptsLeft <= 0) {
            failAfter(helper, cleanup, "cabin roof must melt within the polling window but was "
                    + plotStateAt(helper, level, assembled, roofRel) + "; fit=" + dumpFit(level, assembled));
            return;
        }
        helper.runAfterDelay(5, () -> {
            BlockState roof = plotStateAt(helper, level, assembled, roofRel);
            if (roof.isAir()) {
                try {
                    helper.assertTrue(plotStateAt(helper, level, assembled, floorRel).is(Blocks.STONE),
                            "cabin floor must outlive the roof layer but was "
                                    + plotStateAt(helper, level, assembled, floorRel));
                    VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                    assertNoLavaBelowColumnTops(helper, level, fit, "after roof melt");
                    assertNoLavaUnderHull(helper, level, fit, "after roof melt");
                } finally {
                    cleanup.run();
                }
                helper.succeed();
                return;
            }
            try {
                helper.assertTrue(roof.is(Blocks.STONE), "roof must stay stone while melting but was " + roof);
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                assertNoLavaBelowColumnTops(helper, level, fit, "roof intact");
                assertNoLavaUnderHull(helper, level, fit, "roof intact");
                assertNoLavaAt(helper, level, assembled, interior, "roof intact");
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
            pollCabinRoof(helper, level, assembled, interior, roofRel, floorRel, attemptsLeft - 1, cleanup);
        });
    }

    /**
     * 1 格厚外挑甲板：从上方浇岩浆，外挑段下方（含甲板下沿与更低的空气格）在甲板完整
     * 期间不得出现熔岩；甲板先熔，基座下一层仍在。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_deck", timeoutTicks = 24000)
    public static void deckOverhangBlocksLavaUntilDeckMelts(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assembleShape(helper, deckOverhangShape());
        Set<BlockPos> sealed = new LinkedHashSet<>();
        BlockPos deckRel = new BlockPos(4, 4, 2);
        BlockPos baseTopRel = new BlockPos(2, 3, 2);
        List<BlockPos> underside = new ArrayList<>();
        for (int dy = 1; dy <= 3; dy++) {
            for (int z = 1; z <= 3; z++) {
                underside.add(new BlockPos(4, 4 - dy, z));
            }
        }
        Runnable cleanup = () -> {
            clearLavaAround(level, projectedCell(assembled), 6);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                List<BlockPos> poured = placeLavaAboveColumnTops(level, fit, sealed);
                helper.assertTrue(!poured.isEmpty(), "deck must accept lava poured from above");
                helper.runAfterDelay(15, () -> pollDeckMelt(helper, level, assembled, underside,
                        deckRel, baseTopRel, 60, cleanup));
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    private static void pollDeckMelt(GameTestHelper helper, ServerLevel level, Assembled assembled,
            List<BlockPos> underside, BlockPos deckRel, BlockPos baseTopRel, int attemptsLeft,
            Runnable cleanup) {
        if (attemptsLeft <= 0) {
            failAfter(helper, cleanup, "deck overhang must melt within the polling window but was "
                    + plotStateAt(helper, level, assembled, deckRel) + "; fit=" + dumpFit(level, assembled));
            return;
        }
        helper.runAfterDelay(5, () -> {
            BlockState deck = plotStateAt(helper, level, assembled, deckRel);
            if (deck.isAir()) {
                try {
                    helper.assertTrue(plotStateAt(helper, level, assembled, baseTopRel).is(Blocks.STONE),
                            "base layer below the deck must outlive the deck layer but was "
                                    + plotStateAt(helper, level, assembled, baseTopRel));
                    VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                    assertNoLavaBelowColumnTops(helper, level, fit, "after deck melt");
                    assertNoLavaUnderHull(helper, level, fit, "after deck melt");
                } finally {
                    cleanup.run();
                }
                helper.succeed();
                return;
            }
            try {
                helper.assertTrue(deck.is(Blocks.STONE), "deck must stay stone while melting but was " + deck);
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                assertNoLavaBelowColumnTops(helper, level, fit, "deck intact");
                assertNoLavaUnderHull(helper, level, fit, "deck intact");
                assertNoLavaAt(helper, level, assembled, underside, "deck intact");
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
            pollDeckMelt(helper, level, assembled, underside, deckRel, baseTopRel, attemptsLeft - 1, cleanup);
        });
    }

    /**
     * 逐层推进：两层石板从上方浇岩浆。上层熔掉后投影收缩，熔岩只下降一层（落在新柱顶
     * 空位上），下层仍是石头；不会直接穿到车底。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_layer", timeoutTicks = 24000)
    public static void lavaDescendsExactlyOneLayerAfterRoofMelts(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assembleShape(helper, twoLayerDeckShape());
        Set<BlockPos> sealed = new LinkedHashSet<>();
        BlockPos topRel = new BlockPos(2, 4, 2);
        BlockPos belowRel = new BlockPos(2, 3, 2);
        Runnable cleanup = () -> {
            clearLavaAround(level, projectedCell(assembled), 6);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            try {
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                BlockPos topCell = projectRel(helper, assembled, topRel);
                int oldTopY = columnTopY(fit, topCell.getX(), topCell.getZ());
                helper.assertTrue(oldTopY != Integer.MIN_VALUE,
                        "top layer must have a fitted column at " + topCell.toShortString());
                List<BlockPos> poured = placeLavaAboveColumnTops(level, fit, sealed);
                helper.assertTrue(!poured.isEmpty(), "two-layer deck must accept lava poured from above");
                helper.assertTrue(lowestLavaY(level, topCell.getX(), topCell.getZ(),
                        oldTopY, oldTopY + 3) != Integer.MAX_VALUE,
                        "lava must rest above the top layer's fitted column");
                helper.runAfterDelay(15, () -> pollLayerDescent(helper, level, assembled,
                        topRel, belowRel, oldTopY, 80, cleanup));
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    private static void pollLayerDescent(GameTestHelper helper, ServerLevel level, Assembled assembled,
            BlockPos topRel, BlockPos belowRel, int oldTopY, int attemptsLeft, Runnable cleanup) {
        if (attemptsLeft <= 0) {
            failAfter(helper, cleanup, "top layer must melt and the fit shrink within the window; fit="
                    + dumpFit(level, assembled));
            return;
        }
        helper.runAfterDelay(2, () -> {
            BlockState top = plotStateAt(helper, level, assembled, topRel);
            if (!top.isAir()) {
                try {
                    helper.assertTrue(top.is(Blocks.STONE), "top layer must stay stone while melting but was " + top);
                    VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                    assertNoLavaBelowColumnTops(helper, level, fit, "top layer intact");
                    assertNoLavaUnderHull(helper, level, fit, "top layer intact");
                } catch (RuntimeException | Error e) {
                    cleanup.run();
                    throw e;
                }
                pollLayerDescent(helper, level, assembled, topRel, belowRel, oldTopY, attemptsLeft - 1, cleanup);
                return;
            }
            try {
                helper.assertTrue(plotStateAt(helper, level, assembled, belowRel).is(Blocks.OBSIDIAN),
                        "second layer must survive the top layer's melt but was "
                                + plotStateAt(helper, level, assembled, belowRel));
                BlockPos topCell = projectRel(helper, assembled, topRel);
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                int newTopY = columnTopY(fit, topCell.getX(), topCell.getZ());
                if (newTopY == Integer.MIN_VALUE || newTopY >= oldTopY) {
                    // fit 尚未到自愈周期：投影还没收缩，继续等（熔岩仍被旧空位挡住）
                    pollLayerDescent(helper, level, assembled, topRel, belowRel, oldTopY, attemptsLeft - 1, cleanup);
                    return;
                }
                helper.assertTrue(oldTopY - newTopY == 1,
                        "projection must shrink exactly one layer after the top layer melts: oldTopY="
                                + oldTopY + " newTopY=" + newTopY);
                // refit 在 LevelTickEvent.Post 里拆空位，而流体 tick 在 level.tick 前半段：
                // 投影刚收缩的当 tick 熔岩还没落下，给几 tick 让流体真实下移一层再断言
                helper.runAfterDelay(3, () -> pollLavaDescent(helper, level, assembled, topRel,
                        oldTopY, newTopY, 30, cleanup));
                return;
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
        });
    }

    /** 投影收缩后轮询熔岩：只下降一层（最低格 = 新柱顶 + 1），且不进入新投影。 */
    private static void pollLavaDescent(GameTestHelper helper, ServerLevel level, Assembled assembled,
            BlockPos topRel, int oldTopY, int newTopY, int attemptsLeft, Runnable cleanup) {
        if (attemptsLeft <= 0) {
            failAfter(helper, cleanup, "lava must settle exactly one layer below the melted layer but did not; oldTopY="
                    + oldTopY + " newTopY=" + newTopY + " fit=" + dumpFit(level, assembled));
            return;
        }
        helper.runAfterDelay(2, () -> {
            BlockPos topCell = projectRel(helper, assembled, topRel);
            VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
            int lowest = lowestLavaY(level, topCell.getX(), topCell.getZ(), newTopY, oldTopY + 3);
            if (lowest != newTopY + 1) {
                pollLavaDescent(helper, level, assembled, topRel, oldTopY, newTopY,
                        attemptsLeft - 1, cleanup);
                return;
            }
            try {
                helper.assertTrue(columnTopY(fit, topCell.getX(), topCell.getZ()) == newTopY,
                        "projection must stay at the one-layer-down column top while the lower layer melts; fit="
                                + dumpFit(level, assembled));
                assertNoLavaBelowColumnTops(helper, level, fit, "after one-layer descent");
                assertNoLavaUnderHull(helper, level, fit, "after one-layer descent");
            } catch (RuntimeException | Error e) {
                cleanup.run();
                throw e;
            }
            cleanup.run();
            helper.succeed();
        });
    }

    /**
     * 合成 tracker 用例：单格柱（正下方悬空）。封口格补在正下方后，熔岩留在柱顶、
     * 被封口空位挡住；不会从完整船壳漏下去。
     *
     * <p>直接向 tracker 装一架"目标格"账本，不组装真实载具：柱规则与封口格的接线
     * 是纯 world 写入，不需要 Sable 物理；载具用例只覆盖自然浇淋的形状行为。</p>
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_column", timeoutTicks = 200)
    public static void singleCellColumnTopLavaIsSealedByVoidBelow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SavedConfig saved = enableRules();
        VoidFitTracker tracker = VoidFitTracker.of(level.dimension());
        UUID id = UUID.randomUUID();
        BlockPos top = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos seal = top.below();
        Set<BlockPos> sealed = new LinkedHashSet<>();
        LongOpenHashSet base = new LongOpenHashSet();
        base.add(top.asLong());
        LongOpenHashSet target = new LongOpenHashSet(base);
        target.addAll(VoidFitColumnTops.sealCellsFor(base, level.getMinBuildHeight()));
        Runnable cleanup = () -> {
            tracker.removeVehicle(id);
            clearLavaAround(level, top, 3);
            unseal(level, sealed);
            level.setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(seal, Blocks.AIR.defaultBlockState());
            restore(saved);
        };
        try {
            helper.assertTrue(target.size() == 2 && target.contains(seal.asLong()),
                    "single-cell column must get a seal cell directly below; target=" + dumpCells(target));
            level.setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(seal, Blocks.AIR.defaultBlockState());
            VoidFitTracker.VehicleFit fit = new VoidFitTracker.VehicleFit();
            tracker.vehicles().put(id, fit);
            tracker.setTargetCells(id, target);
            sealAroundLava(level, fit, top, Set.of(top), sealed);
            level.setBlockAndUpdate(top, Blocks.LAVA.defaultBlockState());
            helper.assertTrue(VoidFitApplier.placeInChunk(level, tracker, target, 64),
                    "placeInChunk must finish within budget");
            helper.assertTrue(level.getFluidState(top).is(FluidTags.LAVA),
                    "sealed single-cell column top must keep its lava but was " + level.getBlockState(top));
            helper.assertTrue(level.getBlockState(seal).is(Blocks.STRUCTURE_VOID),
                    "the cell below the kept top must be structure_void but was " + level.getBlockState(seal));
            helper.assertTrue(!isLava(level, top.below()), "lava must not fall through the single-cell column");
        } catch (RuntimeException | Error e) {
            cleanup.run();
            throw e;
        }
        cleanup.run();
        helper.succeed();
    }

    /**
     * 合成 tracker 用例：柱顶与下一格之间有 1 格间隙（外挑/悬空下沿）。封口格补在
     * 间隙里，熔岩留在柱顶；间隙与更低的柱内目标格都不得有熔岩。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_gap", timeoutTicks = 200)
    public static void gapBelowColumnTopLavaIsSealedByVoid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SavedConfig saved = enableRules();
        VoidFitTracker tracker = VoidFitTracker.of(level.dimension());
        UUID id = UUID.randomUUID();
        BlockPos top = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos gap = top.below();
        BlockPos lower = gap.below();
        Set<BlockPos> sealed = new LinkedHashSet<>();
        LongOpenHashSet base = new LongOpenHashSet();
        base.add(top.asLong());
        base.add(lower.asLong());
        LongOpenHashSet target = new LongOpenHashSet(base);
        target.addAll(VoidFitColumnTops.sealCellsFor(base, level.getMinBuildHeight()));
        Runnable cleanup = () -> {
            tracker.removeVehicle(id);
            clearLavaAround(level, top, 4);
            unseal(level, sealed);
            level.setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(gap, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(lower, Blocks.AIR.defaultBlockState());
            restore(saved);
        };
        try {
            helper.assertTrue(target.contains(gap.asLong()) && target.size() == 3,
                    "gap column must get a seal cell in the gap; target=" + dumpCells(target));
            for (BlockPos pos : List.of(top, gap, lower)) {
                level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
            VoidFitTracker.VehicleFit fit = new VoidFitTracker.VehicleFit();
            tracker.vehicles().put(id, fit);
            tracker.setTargetCells(id, target);
            sealAroundLava(level, fit, top, Set.of(top), sealed);
            level.setBlockAndUpdate(top, Blocks.LAVA.defaultBlockState());
            helper.assertTrue(VoidFitApplier.placeInChunk(level, tracker, target, 64),
                    "placeInChunk must finish within budget");
            helper.assertTrue(level.getFluidState(top).is(FluidTags.LAVA),
                    "gap column top must keep its lava but was " + level.getBlockState(top));
            helper.assertTrue(level.getBlockState(gap).is(Blocks.STRUCTURE_VOID),
                    "gap below the kept top must be sealed with structure_void but was " + level.getBlockState(gap));
            helper.assertTrue(level.getBlockState(lower).is(Blocks.STRUCTURE_VOID),
                    "lower target cell must be evacuated to structure_void but was " + level.getBlockState(lower));
            helper.assertTrue(!isLava(level, gap) && !isLava(level, lower.below()),
                    "lava must not enter the gap or below the lower target cell");
        } catch (RuntimeException | Error e) {
            cleanup.run();
            throw e;
        }
        cleanup.run();
        helper.succeed();
    }

    /**
     * 安全回退：封口格被无碰撞方块（火把）占据时，结构空位写不进去、正下方不挡熔岩
     * ——柱顶熔岩必须驱逐（安全方向），不能为了保留接触把它留在没有屏障的柱顶。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_sealblocked", timeoutTicks = 200)
    public static void nonBlockingSealFallsBackToEviction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SavedConfig saved = enableRules();
        VoidFitTracker tracker = VoidFitTracker.of(level.dimension());
        UUID id = UUID.randomUUID();
        BlockPos top = helper.absolutePos(new BlockPos(1, 3, 1));
        BlockPos seal = top.below();
        Set<BlockPos> sealed = new LinkedHashSet<>();
        LongOpenHashSet target = new LongOpenHashSet();
        target.add(top.asLong());
        target.add(seal.asLong());
        Runnable cleanup = () -> {
            tracker.removeVehicle(id);
            clearLavaAround(level, top, 3);
            unseal(level, sealed);
            level.setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(seal, Blocks.AIR.defaultBlockState());
            restore(saved);
        };
        try {
            level.setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(seal, Blocks.TORCH.defaultBlockState());
            VoidFitTracker.VehicleFit fit = new VoidFitTracker.VehicleFit();
            tracker.vehicles().put(id, fit);
            tracker.setTargetCells(id, target);
            sealAroundLava(level, fit, top, Set.of(top), sealed);
            level.setBlockAndUpdate(top, Blocks.LAVA.defaultBlockState());
            LongOpenHashSet onlyTop = new LongOpenHashSet();
            onlyTop.add(top.asLong());
            helper.assertTrue(VoidFitApplier.placeInChunk(level, tracker, onlyTop, 64),
                    "placeInChunk must finish within budget");
            helper.assertTrue(level.getBlockState(top).is(Blocks.STRUCTURE_VOID),
                    "top lava must be evicted when the cell below cannot block it but was "
                            + level.getBlockState(top));
            helper.assertTrue(level.getBlockState(seal).is(Blocks.TORCH),
                    "non-solid seal cell must not be overwritten or destroyed but was " + level.getBlockState(seal));
        } catch (RuntimeException | Error e) {
            cleanup.run();
            throw e;
        }
        cleanup.run();
        helper.succeed();
    }

    /**
     * 缩放载具（scale 0.5）下保守覆盖退化为单格柱：refit 必须给每个柱顶补封口格，
     * 使每个柱顶都是"安全柱顶"。真实载具在 scale=1 时保守覆盖天然每轴 ≥2 格，缺口
     * 不会出现；本用例用临时 pose scale 复现缺口，验证 refit 的封口接线。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_lava_melt_scale", timeoutTicks = 24000)
    public static void scaledVehicleColumnGetsSealCell(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL, Blocks.STONE);
        Set<BlockPos> sealed = new LinkedHashSet<>();
        Runnable cleanup = () -> {
            clearLavaAround(level, projectedCell(assembled), 6);
            unseal(level, sealed);
            assembled.subLevel().markRemoved();
            restore(saved);
        };
        awaitFitted(helper, level, assembled, cleanup, () -> {
            ServerSubLevel subLevel = assembled.subLevel();
            Vector3d originalScale = new Vector3d(subLevel.logicalPose().scale());
            try {
                VoidFitTracker tracker = VoidFitTracker.of(level.dimension());
                // 0.01：保守覆盖半 extent 缩到 0.005，单方块几乎必然只覆盖 1 格（scale 1
                // 时每轴恒 ≥2 格，缺口不可能出现；这里临时复现缺口形状）
                subLevel.logicalPose().scale().set(0.01D, 0.01D, 0.01D);
                helper.assertTrue(VoidFitApplier.refit(level, tracker, subLevel, true),
                        "scaled refit must complete within budget");
                VoidFitTracker.VehicleFit fit = trackedFit(helper, level, assembled);
                LongOpenHashSet base = VoidFitVoxelizer.voxelize(level, subLevel);
                helper.assertTrue(base.size() == 1,
                        "scaled single block must project to exactly one cell; base=" + dumpCells(base));
                LongOpenHashSet expected = new LongOpenHashSet(base);
                expected.addAll(VoidFitColumnTops.sealCellsFor(base, level.getMinBuildHeight()));
                helper.assertTrue(expected.size() == 2,
                        "single-cell column must get exactly one seal cell; expected=" + dumpCells(expected));
                helper.assertTrue(expected.equals(fit.targetCells),
                        "refit target must equal voxelized target + seals; expected=" + dumpCells(expected)
                                + " actual=" + dumpCells(fit.targetCells));
                for (long cell : base) {
                    helper.assertTrue(VoidFitColumnTops.isSealedTop(fit.columnTops, fit.targetCells, cell),
                            "scaled single-cell column top must be sealed after refit: "
                                    + BlockPos.of(cell).toShortString());
                }
            } finally {
                subLevel.logicalPose().scale().set(originalScale);
                VoidFitApplier.refit(level, VoidFitTracker.of(level.dimension()), subLevel, true);
            }
            cleanup.run();
            helper.succeed();
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
