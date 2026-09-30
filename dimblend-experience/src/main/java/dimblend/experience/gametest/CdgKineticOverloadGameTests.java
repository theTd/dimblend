package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dimblend.experience.compat.cdg.CdgKineticOverload;
import dimblend.experience.mixin.compat.create.KineticNetworkUnloadedAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 柴油机过载实时复核（{@link CdgKineticOverload}）运行时验证。复核只依赖 Create 网络，
 * CDG 不在开发运行类路径上，这里用创造马达 - 传动杆 - 鼓风机替身：
 * <ul>
 * <li>缓存位粘住：只把鼓风机的缓存位写成过载、网络总量不动——{@code updateNetwork()}
 * 因总量未变不 sync，复核必须按实时容量/应力判否，并把这个缓存位刷掉。</li>
 * <li>真实过载：未加载应力抬过实时容量（账本口径真过载），复核必须判是。</li>
 * </ul>
 * 模板复用 {@code item_drain_refill}（3×3×3 空平台），y=1 z=1。仅 Create 在场时注册。
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class CdgKineticOverloadGameTests {

    private static final BlockPos MOTOR = new BlockPos(0, 1, 1);
    private static final BlockPos SHAFT = new BlockPos(1, 1, 1);
    private static final BlockPos FAN = new BlockPos(2, 1, 1);

    private CdgKineticOverloadGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void stickyCachedFlagIsNotOverload(GameTestHelper helper) {
        placeLine(helper);
        helper.runAfterDelay(5, () -> {
            KineticBlockEntity fan = helper.getBlockEntity(FAN);
            KineticNetwork network = fan.getOrCreateNetwork();
            helper.assertTrue(network.calculateCapacity() >= network.calculateStress(),
                    "precondition: motor must cover the fan");
            // 只改本 BE 的缓存视图（与读档/漏 sync 同形态），网络总量不动
            fan.updateFromNetwork(0.0F, 1.0F, network.getSize());
            helper.assertTrue(fan.isOverStressed(), "precondition: cached flag must be stuck at overstressed");
            helper.assertFalse(CdgKineticOverload.refreshedOverstressed(fan),
                    "live capacity covers stress: sticky cached flag must not count as overload");
            helper.assertFalse(fan.isOverStressed(), "recheck must clear the sticky cached flag");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void liveOverloadCounts(GameTestHelper helper) {
        placeLine(helper);
        helper.runAfterDelay(5, () -> {
            KineticBlockEntity fan = helper.getBlockEntity(FAN);
            KineticNetwork network = fan.getOrCreateNetwork();
            KineticNetworkUnloadedAccessor ledger = (KineticNetworkUnloadedAccessor) network;
            // 账本口径真过载：未加载应力抬过实时容量，updateNetwork 同步过载位
            ledger.dimblend$setUnloadedStress(network.calculateCapacity() * 2.0F + 1.0F);
            network.updateNetwork();
            helper.assertTrue(fan.isOverStressed(), "precondition: network must be overstressed");
            helper.assertTrue(CdgKineticOverload.refreshedOverstressed(fan),
                    "live capacity below stress must count as overload");
            helper.assertTrue(fan.isOverStressed(), "genuine overload must keep the cached flag");
            ledger.dimblend$setUnloadedStress(0.0F);
            network.updateNetwork();
            helper.succeed();
        });
    }

    private static void placeLine(GameTestHelper helper) {
        helper.setBlock(MOTOR, AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
        helper.setBlock(SHAFT, AllBlocks.SHAFT.get().defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        helper.setBlock(FAN, AllBlocks.ENCASED_FAN.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
    }
}
