package dimblend.radio.gametest;

import java.util.Optional;

import dimblend.radio.RadioSignals;
import dimblend.radio.RadioState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 电台红石输入运行时验证（{@code RadioSignals} + {@code JukeboxRedstoneInputMixin}）。
 *
 * <ul>
 *   <li>贴在侧面的拉杆、贴着侧面的红石块：侧面读 15，放上/拉动当场开播、撤掉当场停播
 *       （neighborChanged 即时重算，断言在同一调用栈内完成，不靠 20 tick 看门狗）。</li>
 *   <li>贴附拉杆强充能空盘唱片机，不串到其它面的红石线：顶部拉杆不抬侧面红石线，
 *       侧面拉杆不抬顶部红石线。开发运行时无 Create，模拟拉杆用原版拉杆替身——两者都经
 *       {@code getDirectSignal} 强充能所贴方块，走同一条中继路径。</li>
 *   <li>对照：有盘唱片机保持原版导体行为（照常中继）。</li>
 * </ul>
 *
 * <p>布局：唱片机 {@code J=(2,1,2)}；顶部选台 = 顶上石头 + 其上红石线（红石块→15→14，台 14）。
 * 模板 {@code data/dimblend_radio/structure/radio_signal_input.nbt}（5×5×5 空气）。</p>
 */
@GameTestHolder("dimblend_radio")
@PrefixGameTestTemplate(false)
public final class RadioSignalInputGameTests {

    private static final BlockPos JUKEBOX = new BlockPos(2, 1, 2);
    private static final BlockPos TOP = new BlockPos(2, 2, 2);
    private static final BlockPos WEST = new BlockPos(1, 1, 2);
    private static final BlockPos EAST = new BlockPos(3, 1, 2);
    private static final BlockPos BELOW_EAST = new BlockPos(3, 0, 2);

    private static final int STATION = 14;

    private RadioSignalInputGameTests() {
    }

    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void sideAttachedLeverStartsAndStopsRadio(GameTestHelper helper) {
        helper.setBlock(JUKEBOX, Blocks.JUKEBOX);
        buildTopStation(helper);
        helper.setBlock(WEST, wallLever(Direction.WEST));
        assertTop(helper, STATION);
        assertNoRadio(helper, "unpowered side lever must not start the radio");

        helper.pullLever(WEST);
        assertSide(helper, 15);
        assertRadio(helper, STATION, 15, "side lever on");

        helper.pullLever(WEST);
        assertSide(helper, 0);
        assertNoRadio(helper, "side lever off must stop the radio");
        helper.succeed();
    }

    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void sideRedstoneBlockStartsAndStopsRadio(GameTestHelper helper) {
        helper.setBlock(JUKEBOX, Blocks.JUKEBOX);
        buildTopStation(helper);
        assertNoRadio(helper, "no side signal yet");

        helper.setBlock(EAST, Blocks.REDSTONE_BLOCK);
        assertSide(helper, 15);
        assertRadio(helper, STATION, 15, "redstone block placed against the side");

        helper.setBlock(EAST, Blocks.AIR);
        assertSide(helper, 0);
        assertNoRadio(helper, "redstone block removed must stop the radio");
        helper.succeed();
    }

    /** 模拟拉杆替身：顶部贴附拉杆强充能唱片机，侧面红石线不得被抬起（否则音量跟台号走、侧面停不了）。 */
    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void topAttachedLeverDoesNotLeakIntoSideWire(GameTestHelper helper) {
        helper.setBlock(JUKEBOX, Blocks.JUKEBOX);
        placeSideWire(helper);
        helper.setBlock(TOP, floorLever());
        helper.pullLever(TOP);

        assertTop(helper, 15);
        assertWirePower(helper, EAST, 0, "top lever must not power the side wire through an empty jukebox");
        assertSide(helper, 0);
        helper.succeed();
    }

    /** 侧面贴附拉杆强充能唱片机，顶部红石线不得被顶成 15（否则旁路不开播）。 */
    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void sideAttachedLeverDoesNotLeakIntoTopWire(GameTestHelper helper) {
        helper.setBlock(JUKEBOX, Blocks.JUKEBOX);
        helper.setBlock(TOP, Blocks.REDSTONE_WIRE);
        helper.setBlock(WEST, wallLever(Direction.WEST));
        helper.pullLever(WEST);

        assertSide(helper, 15);
        assertWirePower(helper, TOP, 0, "side lever must not power the top wire through an empty jukebox");
        assertTop(helper, 0);
        helper.succeed();
    }

    /** 对照：有盘唱片机保持原版导体行为，贴附拉杆照常经唱片机中继到相邻红石线。 */
    @GameTest(template = "radio_signal_input", templateNamespace = "dimblend_radio")
    public static void recordJukeboxKeepsVanillaRelay(GameTestHelper helper) {
        helper.setBlock(JUKEBOX, Blocks.JUKEBOX.defaultBlockState().setValue(JukeboxBlock.HAS_RECORD, true));
        placeSideWire(helper);
        helper.setBlock(TOP, floorLever());
        helper.pullLever(TOP);

        assertWirePower(helper, EAST, 15, "jukebox with a record must still relay strong power (vanilla)");
        helper.succeed();
    }

    /** 顶部选台：唱片机顶上石头，石头上红石线由红石块喂入（15→14），经石头读出 14。 */
    private static void buildTopStation(GameTestHelper helper) {
        helper.setBlock(TOP, Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 3, 0), Blocks.REDSTONE_BLOCK);
        helper.setBlock(new BlockPos(2, 3, 1), Blocks.REDSTONE_WIRE);
        helper.setBlock(new BlockPos(2, 3, 2), Blocks.REDSTONE_WIRE);
    }

    private static void placeSideWire(GameTestHelper helper) {
        helper.setBlock(BELOW_EAST, Blocks.STONE);
        helper.setBlock(EAST, Blocks.REDSTONE_WIRE);
    }

    /** 贴在唱片机侧面的拉杆：facing 朝外（背离所贴方块）。 */
    private static BlockState wallLever(Direction outward) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.WALL)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, outward)
                .setValue(BlockStateProperties.POWERED, false);
    }

    /** 贴在唱片机顶面的拉杆。 */
    private static BlockState floorLever() {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.POWERED, false);
    }

    private static void assertTop(GameTestHelper helper, int expected) {
        int top = RadioSignals.readTop(helper.getLevel(), helper.absolutePos(JUKEBOX));
        helper.assertTrue(top == expected, "top signal expected " + expected + " but was " + top);
    }

    private static void assertSide(GameTestHelper helper, int expected) {
        int side = RadioSignals.readSide(helper.getLevel(), helper.absolutePos(JUKEBOX));
        helper.assertTrue(side == expected, "side signal expected " + expected + " but was " + side);
    }

    private static void assertWirePower(GameTestHelper helper, BlockPos wire, int expected, String message) {
        BlockState state = helper.getBlockState(wire);
        helper.assertTrue(state.is(Blocks.REDSTONE_WIRE), "expected redstone wire at " + wire + " but was " + state);
        int power = state.getValue(RedStoneWireBlock.POWER);
        helper.assertTrue(power == expected, message + " (wire power " + power + ", expected " + expected + ")");
    }

    private static void assertRadio(GameTestHelper helper, int station, int side, String context) {
        Optional<RadioState.Entry> entry = radioEntry(helper);
        helper.assertTrue(entry.isPresent(), context + ": radio must start immediately");
        helper.assertTrue(entry.get().station() == station && entry.get().side() == side,
                context + ": expected station " + station + " side " + side + " but was " + entry.get());
    }

    private static void assertNoRadio(GameTestHelper helper, String message) {
        Optional<RadioState.Entry> entry = radioEntry(helper);
        helper.assertTrue(entry.isEmpty(), message + " (state " + entry.orElse(null) + ")");
    }

    private static Optional<RadioState.Entry> radioEntry(GameTestHelper helper) {
        return RadioState.get(helper.getLevel().dimension().location().toString(),
                helper.absolutePos(JUKEBOX));
    }
}
