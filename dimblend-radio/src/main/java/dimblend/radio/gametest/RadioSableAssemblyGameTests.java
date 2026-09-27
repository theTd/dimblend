package dimblend.radio.gametest;

import java.util.List;

import dimblend.radio.RadioCatalog;
import dimblend.radio.RadioControl;
import dimblend.radio.RadioSignals;
import dimblend.radio.RadioState;
import dimblend.radio.SubLevelProjection;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Sable 组装场景运行时验证：电台随结构组装搬到 plot 坐标后，服务端仍须能维持/发现它
 * （客户端 BGM 压制、实例播放都依赖这份状态）。
 *
 * <p>机制背景：Sable 组装用自己实现的 {@code markAndNotifyBlock} 写 plot 区块，该路径会投递
 * vanilla {@code neighborChanged}（本测试验证其即时建成状态），而 NeoForge
 * {@code NeighborNotifyEvent} 不走；20 tick 发现扫描（{@code plotCentersNear} 反投影 +
 * plot 区块 {@code getChunkNow}）是兜底。注意 {@code SubLevel.globalBounds} 构造时是零盒，
 * 首个 subLevel tick 后才就位——组装当 tick 的空间查询必然 miss，核验须等结构 tick 过。</p>
 *
 * <p>需要 Sable 在场（localRuntime 的 cui-modded jar 提供，与 TrainTripWorld 实例一致）；
 * 模组运行时仍只依赖 companion shim。</p>
 */
@GameTestHolder("dimblend_radio")
@PrefixGameTestTemplate(false)
public final class RadioSableAssemblyGameTests {

    private static final BlockPos JUKEBOX = new BlockPos(2, 1, 2);
    private static final BlockPos TOP = new BlockPos(2, 2, 2);
    private static final BlockPos EAST = new BlockPos(3, 1, 2);

    /** 组装体：恰好包住整套 rig（唱片机 + 顶部选台塔 + 侧面红石块/拉杆）。 */
    private static final BlockPos BOX_MIN = new BlockPos(2, 1, 0);
    private static final BlockPos BOX_MAX = new BlockPos(3, 3, 2);

    private static final int STATION = 14;
    private static final int SIDE = 15;

    /** 等结构 tick 过（globalBounds 就位）再核验空间查询。 */
    private static final int ASSEMBLY_SETTLE_TICKS = 5;

    private RadioSableAssemblyGameTests() {
    }

    /**
     * 播放中组装：状态应在组装当 tick 经 neighborChanged 即时落到 plot 坐标；
     * 结构 tick 后发现链逐环可用；看门狗清掉旧世界坐标的 stale 状态。
     */
    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void sableAssemblyKeepsRadioDiscoverable(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();

        // 基线：地面上接好电台（台14/侧15），事件路径当场建状态
        helper.setBlock(JUKEBOX, Blocks.JUKEBOX);
        buildTopStation(helper);
        helper.setBlock(EAST, Blocks.REDSTONE_BLOCK);
        BlockPos jukeboxWorld = helper.absolutePos(JUKEBOX);
        helper.assertTrue(RadioState.get(dim, jukeboxWorld).isPresent(),
                "baseline: radio state must exist before assembly");

        ServerSubLevel subLevel = assembleRig(helper);
        BlockPos plotPos = jukeboxPlotPos(subLevel, jukeboxWorld);

        // 事件路径：组装当 tick 状态已落到 plot 坐标（neighborChanged 即时重算）
        helper.assertTrue(RadioState.get(dim, plotPos).isPresent(),
                "radio state must exist at plot pos right after assembly");

        helper.runAfterDelay(ASSEMBLY_SETTLE_TICKS, () -> {
            verifyDiscoveryLinks(helper, level, dim, jukeboxWorld, plotPos);
        });
        // 看门狗 20 tick 内清掉旧世界坐标的 stale 状态（旧位置已被搬空）
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(RadioState.get(dim, jukeboxWorld).isEmpty(),
                    "stale state at pre-assembly pos must be removed by watchdog");
            helper.assertTrue(RadioState.get(dim, plotPos).isPresent(),
                    "radio state must still be alive at plot pos");
            helper.succeed();
        });
    }

    /**
     * 组装后再通电：rig 未通电组装（side=0 不开播），之后在 plot 坐标拨侧面拉杆，
     * 须即时开播（plot 坐标下普通方块更新的 neighborChanged 投递 + 即时重算）。
     */
    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void sableAssemblyPoweringAfterAssemblyStartsRadio(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        String dim = level.dimension().location().toString();

        helper.setBlock(JUKEBOX, Blocks.JUKEBOX);
        buildTopStation(helper);
        // 侧面贴附拉杆（贴唱片机东侧面，朝东），未通电：side=0 不开播
        helper.setBlock(EAST, wallLever(Direction.EAST));
        BlockPos jukeboxWorld = helper.absolutePos(JUKEBOX);
        BlockPos leverWorld = helper.absolutePos(EAST);
        helper.assertTrue(RadioState.get(dim, jukeboxWorld).isEmpty(),
                "baseline: unpowered side lever must not start the radio");

        ServerSubLevel subLevel = assembleRig(helper);
        BlockPos plotPos = jukeboxPlotPos(subLevel, jukeboxWorld);
        BlockPos leverPlot = BlockPos.containing(
                subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(leverWorld)));

        // 在 plot 坐标拨拉杆（等同玩家右键：setBlock 带电 + 邻居通知）
        BlockState lever = level.getBlockState(leverPlot);
        helper.assertTrue(lever.is(Blocks.LEVER), "lever must exist at plot pos, got " + lever);
        level.setBlock(leverPlot, lever.setValue(BlockStateProperties.POWERED, true), 3);

        helper.assertTrue(RadioSignals.readSide(level, plotPos) == SIDE,
                "plot side signal must read " + SIDE + " after pulling the lever");
        helper.assertTrue(
                RadioState.get(dim, plotPos).map(RadioState.Entry::playing).orElse(false),
                "radio must start immediately when powered on the structure");
        helper.succeed();
    }

    private static ServerSubLevel assembleRig(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos min = helper.absolutePos(BOX_MIN);
        BlockPos max = helper.absolutePos(BOX_MAX);
        ServerSubLevel subLevel = SubLevelAssemblyHelper.assembleBlocks(level, min,
                BlockPos.betweenClosed(min, max), new BoundingBox3i(min, max));
        helper.assertTrue(subLevel != null, "assembly must succeed");
        return subLevel;
    }

    /** 唱片机的 plot 坐标 = 原世界坐标按结构位姿反投影。 */
    private static BlockPos jukeboxPlotPos(ServerSubLevel subLevel, BlockPos jukeboxWorld) {
        Vec3 plotCenter = subLevel.logicalPose().transformPositionInverse(Vec3.atCenterOf(jukeboxWorld));
        return BlockPos.containing(plotCenter);
    }

    /**
     * 发现扫描依赖的事实逐环核验（与 {@code RadioSync.discoverLoadedJukeboxes} 的
     * plot 分支逐行对应）：反投影命中 → plot 区块可见 → plot 下读数正确 → recompute 建状态。
     */
    private static void verifyDiscoveryLinks(GameTestHelper helper, ServerLevel level, String dim,
            BlockPos jukeboxWorld, BlockPos plotPos) {
        // 环 1：getAllIntersecting 按世界坐标框到结构，反投影点落在唱片机 plot 格
        List<Vec3> centers = SubLevelProjection.plotCentersNear(level, Vec3.atCenterOf(jukeboxWorld), 72);
        helper.assertTrue(centers.stream().anyMatch(c -> BlockPos.containing(c).equals(plotPos)),
                "plotCentersNear must include the jukebox plot pos " + plotPos + ", got " + centers);

        // 环 2：plot 区块对 getChunkNow 可见（发现扫描的取块通道）
        ChunkPos plotChunk = new ChunkPos(plotPos);
        helper.assertTrue(level.getChunkSource().getChunkNow(plotChunk.x, plotChunk.z) != null,
                "plot chunk " + plotChunk + " must be visible via getChunkNow");

        // 环 3：plot 坐标下空盘判定与红石读数正确
        helper.assertTrue(RadioControl.isEmpty(level, plotPos),
                "plot jukebox must read as empty-disc at " + plotPos);
        int top = RadioSignals.readTop(level, plotPos);
        int side = RadioSignals.readSide(level, plotPos);
        helper.assertTrue(top == STATION, "plot top signal expected " + STATION + " but was " + top);
        helper.assertTrue(side == SIDE, "plot side signal expected " + SIDE + " but was " + side);

        // 环 4：发现扫描命中后执行的动作（recompute）在 plot 坐标保持播放态
        RadioState.recompute(level, plotPos,
                (station, avoid) -> RadioCatalog.pickNext(station, avoid, level.random));
        helper.assertTrue(RadioState.get(dim, plotPos).map(RadioState.Entry::playing).orElse(false),
                "radio state must exist and be playing at plot pos " + plotPos);
    }

    /** 顶部选台：唱片机顶上石头，石头上红石线由红石块喂入（15→14），经石头读出 14。 */
    private static void buildTopStation(GameTestHelper helper) {
        helper.setBlock(TOP, Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 3, 0), Blocks.REDSTONE_BLOCK);
        helper.setBlock(new BlockPos(2, 3, 1), Blocks.REDSTONE_WIRE);
        helper.setBlock(new BlockPos(2, 3, 2), Blocks.REDSTONE_WIRE);
    }

    /** 贴在唱片机侧面的拉杆：facing 朝外（背离所贴方块），未通电。 */
    private static BlockState wallLever(Direction outward) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, outward)
                .setValue(BlockStateProperties.POWERED, false);
    }
}
