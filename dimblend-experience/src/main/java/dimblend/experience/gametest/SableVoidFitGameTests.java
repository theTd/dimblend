package dimblend.experience.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dimblend.experience.Config;
import dimblend.experience.compat.sable.VoidFitStrip;
import dimblend.experience.compat.sable.VoidFitTracker;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Sable 载具世界侧结构空位拟合（G3 配套）的运行时检查：放置走事件调度、剥离走 mixin，
 * 纯单测覆盖不到，故全用 GameTest 在真实世界里核验。
 *
 * <p>载具是物理体：组装后会下落/微沉到静止，落点格与初始格可能不同，且保守覆盖可能
 * 跨格。所有断言一律先经位姿投影求"石块当前的世界格"再核验，不断言初始格。</p>
 *
 * <p>维度门/拟合开关/扫描周期经 {@link GameTestSableRules} 引用计数共享覆写——
 * vanilla 不同 batch 在同一世界并发执行，私有"保存-设值-还原"会被先结束者的还原
 * 踩踏（在途用例门控被拨回生产值）；{@code LIMITED_WATER} 取值与 limited_water
 * 用例互斥，仍按本类私有保存/还原（挡水用例需要水真实存续：G3 会把孤立源水改写为
 * 流动水，而原版流动水无供养下一流体 tick 即干涸——与本特性无关，关掉避免干扰）。
 * 不加 {@code @GameTestHolder}：引用 Sable 类，只由 DimBlend 在 Sable 在场时显式注册。</p>
 */
@PrefixGameTestTemplate(false)
public final class SableVoidFitGameTests {

    private static final String TEMPLATE = "item_drain_refill";
    private static final String NAMESPACE = "dimblend_experience";
    private static final BlockPos VEHICLE_REL = new BlockPos(1, 2, 1);
    /** 结构 tick、物理静止与至少一次拟合扫描的就绪窗口。 */
    private static final int SETTLE_TICKS = 15;
    /** 等慢速自愈周期（20 扫描 × 1 tick）富余量。 */
    private static final int VERIFY_TICKS = 40;

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

    /** 组装结果：载具 + 石块在 plot 内的固定坐标（载具移动不改变 plot 内坐标）。 */
    private record Assembled(ServerSubLevel subLevel, Vec3 plotCenter) {
    }

    /**
     * 组装单方块载具并断言成功（方块随即被搬进 plot，世界格留空）。
     * plot 坐标在组装当下用初始位姿逆投影钉死——载具随后物理移动后仍有效。
     */
    private static Assembled assemble(GameTestHelper helper, BlockPos rel) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(rel, Blocks.STONE);
        BlockPos world = helper.absolutePos(rel);
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, world,
                List.of(world), new BoundingBox3i(world, world));
        helper.assertTrue(subLevel != null, "assembly must succeed");
        Vec3 plotCenter = subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(world));
        return new Assembled(subLevel, plotCenter);
    }

    /** 石块当前的世界格：plot 内固定坐标经当前位姿正投影。 */
    private static BlockPos projectedCell(Assembled assembled) {
        return BlockPos.containing(assembled.subLevel().logicalPose().transformPosition(assembled.plotCenter()));
    }

    /** 从 from 沿 direction 找第一格不是 structure_void 的格（探测拟合覆盖的边界）。 */
    private static BlockPos firstNonVoid(ServerLevel level, BlockPos from, Direction direction, int maxSteps) {
        BlockPos pos = from;
        for (int i = 0; i < maxSteps && level.getBlockState(pos).is(Blocks.STRUCTURE_VOID); i++) {
            pos = pos.relative(direction);
        }
        return pos;
    }

    /** 漏格取证：世界现状 + 各载具对该格的 target/materialized 归属与位姿。 */
    private static String diagnose(ServerLevel level, List<BlockPos> leaked) {
        if (leaked.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        VoidFitTracker tracker = VoidFitTracker.peek(level.dimension());
        for (BlockPos pos : leaked) {
            sb.append(" [").append(pos).append(" world=").append(level.getBlockState(pos));
            if (tracker != null) {
                for (Map.Entry<UUID, VoidFitTracker.VehicleFit> entry : tracker.vehicles().entrySet()) {
                    boolean t = entry.getValue().targetCells.contains(pos.asLong());
                    boolean m = entry.getValue().materializedCells.contains(pos.asLong());
                    if (t || m) {
                        sb.append(" vehicle=").append(entry.getKey()).append(" t=").append(t)
                                .append(" m=").append(m);
                    }
                }
            }
            sb.append(']');
        }
        return sb.toString();
    }

    /** 载具占据格被拟合为结构空位。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_void_fit_air", timeoutTicks = 120)
    public static void fitsVehicleCell(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            try {
                BlockPos cell = projectedCell(assembled);
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                        "projected vehicle cell " + cell + " must be fitted with structure_void but was "
                                + level.getBlockState(cell));
            } finally {
                restore(saved);
            }
            helper.succeed();
        });
    }

    /** 拟合格挡水：向覆盖边界外第一格放水，水不渗入载具格、但确实存在于开放格。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_void_fit_water", timeoutTicks = 120)
    public static void fittedCellBlocksWaterIngress(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos cell = projectedCell(assembled);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                    "precondition: projected cell must be fitted");
            // 放水点：覆盖边界外第一格（东向）；开放核验格再放远一格——单方块载具的
            // 保守覆盖每轴最多 2 格宽，waterAt.east() 必在覆盖外且与放水点相邻
            BlockPos waterAt = firstNonVoid(level, cell, Direction.EAST, 6);
            BlockPos openAt = waterAt.east();
            // 环境参照点：远离任何载具覆盖，同法放水——若它也消失则与拟合无关
            BlockPos reference = cell.north(2).west(2);
            level.setBlockAndUpdate(waterAt, Blocks.WATER.defaultBlockState());
            level.setBlockAndUpdate(reference, Blocks.WATER.defaultBlockState());
            helper.runAfterDelay(15, () -> {
                try {
                    helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                            "fitted cell must keep water out but was " + level.getBlockState(cell));
                    helper.assertTrue(!level.getFluidState(reference).isEmpty(),
                            "sanity: reference water far from any vehicle must persist but was "
                                    + level.getBlockState(reference));
                    helper.assertTrue(!level.getFluidState(openAt).isEmpty(),
                            "sanity: water must have spread to the open neighbour cell; waterAt="
                                    + level.getBlockState(waterAt) + " openAt=" + level.getBlockState(openAt));
                } finally {
                    restore(saved);
                }
                helper.succeed();
            });
        });
    }

    /**
     * 结构空位对交互射线透明（玩家侧兜底，StructureVoidBlockPassthroughMixin）：
     * 开关开 + 旋转维度内，OUTLINE 射线穿过空位格心小盒区域命中其后石块。
     * 两条负向对照保证正例非空转：开关关、维度拨离旋转维度（均恢复原版 6×6×6
     * shape）时同一射线必须被空位拦下。
     * 不经载具：拟合格与普通 structure_void 是同种 BlockState，mixin 按格判定；
     * 且穿过载具本体的射线会被 Sable 的 sublevel 选取合法拦截，无法构造稳定断言。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_void_fit_pass", timeoutTicks = 120)
    public static void structureVoidTransparentToRaycast(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        BlockPos voidAt = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos startAt = voidAt.west();
        BlockPos stoneAt = voidAt.east();
        level.setBlockAndUpdate(voidAt, Blocks.STRUCTURE_VOID.defaultBlockState());
        level.setBlockAndUpdate(stoneAt, Blocks.STONE.defaultBlockState());
        ClipContext ray = new ClipContext(Vec3.atCenterOf(startAt), Vec3.atCenterOf(stoneAt),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, CollisionContext.empty());
        try {
            BlockHitResult hit = level.clip(ray);
            helper.assertTrue(hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(stoneAt),
                    "OUTLINE raycast must pass through the void and hit the stone behind but hit "
                            + hit.getType() + " at " + hit.getBlockPos());
            Config.SABLE_STRUCTURE_VOID_FIT.set(false);
            BlockHitResult blockedByToggle = level.clip(ray);
            helper.assertTrue(blockedByToggle.getType() == HitResult.Type.BLOCK
                            && blockedByToggle.getBlockPos().equals(voidAt),
                    "control: with the feature off the ray must be stopped by the void but hit "
                            + blockedByToggle.getType() + " at " + blockedByToggle.getBlockPos());
            Config.SABLE_STRUCTURE_VOID_FIT.set(true);
            Config.ROTATING_DIMENSION_ID.set("dimblend:rotating");
            BlockHitResult blockedByDimension = level.clip(ray);
            helper.assertTrue(blockedByDimension.getType() == HitResult.Type.BLOCK
                            && blockedByDimension.getBlockPos().equals(voidAt),
                    "control: outside the rotating dimension the ray must be stopped by the void but hit "
                            + blockedByDimension.getType() + " at " + blockedByDimension.getBlockPos());
            // 共享覆写口径：临时翻转须自己翻回（GameTestSableRules 中途不改值）
            Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
        } finally {
            // 断言中途失败也得把临时翻转翻回来（共享覆写中途不改值，泄漏会波及其他用例）
            Config.ROTATING_DIMENSION_ID.set("minecraft:overworld");
            Config.SABLE_STRUCTURE_VOID_FIT.set(true);
            restore(saved);
        }
        helper.succeed();
    }

    /** 拟合格不落盘：剥成空气后序列化，palette 里不得出现 structure_void。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_void_fit_save", timeoutTicks = 120)
    public static void fittedVoidsNeverSaved(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            try {
                BlockPos cell = projectedCell(assembled);
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                        "precondition: projected cell must be fitted");
                ChunkAccess chunk = level.getChunkAt(cell);
                LongList stripped = VoidFitStrip.stripFittedVoids(level, chunk);
                helper.assertTrue(!stripped.isEmpty(), "strip must have peeled the fitted cell");
                CompoundTag savedChunk;
                try {
                    savedChunk = ChunkSerializer.write(level, chunk);
                } finally {
                    VoidFitStrip.restoreFittedVoids(level, chunk, stripped);
                }
                helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                        "restore must put the structure_void back");

                ListTag sections = savedChunk.getList("sections", ListTag.TAG_COMPOUND);
                for (int i = 0; i < sections.size(); i++) {
                    CompoundTag sectionTag = sections.getCompound(i);
                    CompoundTag blockStates = sectionTag.getCompound("block_states");
                    if (blockStates.isEmpty()) {
                        continue;
                    }
                    ListTag palette = blockStates.getList("palette", ListTag.TAG_COMPOUND);
                    int voidIndex = -1;
                    for (int p = 0; p < palette.size(); p++) {
                        if ("minecraft:structure_void".equals(palette.getCompound(p).getString("Name"))) {
                            voidIndex = p;
                            break;
                        }
                    }
                    if (voidIndex < 0) {
                        continue;
                    }
                    // 解码 packed data 定位引用空位的格子（LSB 紧排，index 不跨 long）
                    int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
                    long[] data = blockStates.getLongArray("data");
                    int perLong = 64 / bits;
                    int mask = (1 << bits) - 1;
                    StringBuilder leaked = new StringBuilder();
                    List<BlockPos> leakedCells = new ArrayList<>();
                    int sectionY = sectionTag.getByte("Y");
                    for (int w = 0; w < data.length; w++) {
                        for (int k = 0; k < perLong; k++) {
                            int index = w * perLong + k;
                            if (index >= 4096) {
                                break;
                            }
                            if (((data[w] >>> (k * bits)) & mask) == voidIndex) {
                                BlockPos leakPos = new BlockPos(chunk.getPos().getMinBlockX() + (index & 15),
                                        (sectionY << 4) + (index >> 8),
                                        chunk.getPos().getMinBlockZ() + ((index >> 4) & 15));
                                leaked.append(leakPos).append(' ');
                                leakedCells.add(leakPos);
                            }
                        }
                    }
                    helper.assertTrue(leakedCells.isEmpty(),
                            "saved chunk must not contain structure_void; leaked=" + leaked
                                    + diagnose(level, leakedCells));
                }
            } finally {
                restore(saved);
            }
            helper.succeed();
        });
    }

    /** 载具消失后已写入的空位被拆除，占据格回到空气。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_void_fit_gone", timeoutTicks = 120)
    public static void voidsRemovedWhenVehicleGone(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos cell = projectedCell(assembled);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                    "precondition: projected cell must be fitted");
            assembled.subLevel().markRemoved();
            helper.runAfterDelay(SETTLE_TICKS, () -> {
                try {
                    helper.assertTrue(level.getBlockState(cell).isAir(),
                            "void must be removed with the vehicle but was " + level.getBlockState(cell));
                } finally {
                    restore(saved);
                }
                helper.succeed();
            });
        });
    }

    /** 真实方块不被覆盖：玩家顶掉空位（replaceable）后，自愈核验也不得顶回。 */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_void_fit_repl", timeoutTicks = 120)
    public static void realBlocksNotOverwritten(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos cell = projectedCell(assembled);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                    "precondition: projected cell must be fitted");
            level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
            helper.runAfterDelay(VERIFY_TICKS, () -> {
                try {
                    helper.assertTrue(level.getBlockState(cell).is(Blocks.STONE),
                            "player-placed block must survive the self-heal refit but was "
                                    + level.getBlockState(cell));
                } finally {
                    restore(saved);
                }
                helper.succeed();
            });
        });
    }

    /**
     * 含水方块不被驱逐（B1 回归）：含水台阶的 fluidState 非空，若放置守卫把"含流体"
     * 误当"纯流体"，行驶载具会沿途删除海草/海带/含水方块。含水台阶必须在自愈后存活。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_void_fit_wlog", timeoutTicks = 120)
    public static void waterloggedBlocksNotEvicted(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        Assembled assembled = assemble(helper, VEHICLE_REL);
        helper.runAfterDelay(SETTLE_TICKS, () -> {
            BlockPos cell = projectedCell(assembled);
            helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                    "precondition: projected cell must be fitted");
            BlockState waterlogged = Blocks.STONE_SLAB.defaultBlockState()
                    .setValue(SlabBlock.WATERLOGGED, true);
            level.setBlockAndUpdate(cell, waterlogged);
            helper.runAfterDelay(VERIFY_TICKS, () -> {
                try {
                    helper.assertTrue(level.getBlockState(cell).is(Blocks.STONE_SLAB)
                                    && level.getBlockState(cell).getValue(SlabBlock.WATERLOGGED),
                            "waterlogged slab must survive refits but was " + level.getBlockState(cell));
                } finally {
                    restore(saved);
                }
                helper.succeed();
            });
        });
    }

    /**
     * 多载具同时出现全部拟合（B2 回归：retry 空转饿死尾部载具）。
     * 4 个载具超过单次扫描的载具预算（2），尾部必须经 retry 建账补齐而非永久饥饿。
     */
    @GameTest(template = TEMPLATE, templateNamespace = NAMESPACE, batch = "sable_void_fit_multi", timeoutTicks = 120)
    public static void multipleVehiclesAllFitted(GameTestHelper helper) {
        SavedConfig saved = enableRules();
        ServerLevel level = helper.getLevel();
        List<Assembled> vehicles = new ArrayList<>();
        for (BlockPos rel : new BlockPos[]{new BlockPos(0, 2, 0), new BlockPos(2, 2, 0),
                new BlockPos(0, 2, 2), new BlockPos(2, 2, 2)}) {
            vehicles.add(assemble(helper, rel));
        }
        helper.runAfterDelay(SETTLE_TICKS + 10, () -> {
            try {
                for (Assembled vehicle : vehicles) {
                    BlockPos cell = projectedCell(vehicle);
                    helper.assertTrue(level.getBlockState(cell).is(Blocks.STRUCTURE_VOID),
                            "every vehicle must be fitted; " + cell + " was " + level.getBlockState(cell));
                }
            } finally {
                restore(saved);
            }
            helper.succeed();
        });
    }

    private SableVoidFitGameTests() {
    }
}
