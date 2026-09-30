package dimblend.experience.gametest;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.motor.CreativeMotorBlockEntity;

import dimblend.experience.mixin.compat.create.KineticNetworkUnloadedAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Create 应力网络未加载份额修正（{@code KineticUnloadedShareMixin}）运行时验证。
 *
 * <p>复现读档后的网络重建：创造马达 - 传动杆 - 鼓风机（有应力）沿 X 排开，转起来后
 * 按"区块卸载 + 新会话读档"重建三个 BE（旧 BE 标记区块卸载，Create 不调 remove；
 * 网络对象回到未初始化，由首个 tick 的成员按存档总量 initFromTE），只手动 tick
 * 马达与传动杆，鼓风机保持"已加载未 tick"。此时改马达转速触发 Create
 * reapply source（detach + attach），鼓风机被重建经 {@code add()} 收进 members——
 * 修正前它的存档应力仍留在 unloadedStress，被算两遍且首 tick 的 addSilently
 * 因已在 members 提前 return，永不扣回。</p>
 *
 * <p>模板复用 {@code item_drain_refill}（3×3×3 空平台），y=1 z=1。仅 Create 在场时注册。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class KineticUnloadedShareGameTests {

    private static final BlockPos MOTOR = new BlockPos(0, 1, 1);
    private static final BlockPos SHAFT = new BlockPos(1, 1, 1);
    private static final BlockPos FAN = new BlockPos(2, 1, 1);
    private static final int LOADED_SPEED = 16;
    private static final int REBUILT_SPEED = 32;
    private static final float EPSILON = 1.0E-3F;

    private KineticUnloadedShareGameTests() {
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void rebuildBeforeFirstTickCountsStressOnce(GameTestHelper helper) {
        placeLine(helper);
        helper.runAfterDelay(5, () -> {
            Reloaded line = reloadAsFreshSession(helper);
            line.motor.tick();
            line.shaft.tick();
            // 鼓风机已加载未 tick；马达变速 → Create reapply source 重建整张网
            line.motor.generatedSpeed.setValue(REBUILT_SPEED);
            helper.assertTrue(Math.abs(line.fan.getTheoreticalSpeed()) == REBUILT_SPEED,
                    "precondition: rebuild must re-propagate the fan at the new speed");
            assertLedger(helper, line, 3, "after rebuild");
            // 鼓风机迟到的首 tick：addSilently 已在 members 提前 return，账本不得再动
            line.fan.tick();
            assertLedger(helper, line, 3, "after fan first tick");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void plainLoadSettlesThroughAddSilently(GameTestHelper helper) {
        placeLine(helper);
        helper.runAfterDelay(5, () -> {
            Reloaded line = reloadAsFreshSession(helper);
            line.motor.tick();
            line.shaft.tick();
            line.fan.tick();
            helper.assertTrue(Math.abs(line.fan.getTheoreticalSpeed()) == LOADED_SPEED,
                    "precondition: no rebuild, fan keeps its saved speed");
            assertLedger(helper, line, 3, "plain load");
            helper.succeed();
        });
    }

    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 100)
    public static void removedBeforeFirstTickLeavesNoStress(GameTestHelper helper) {
        placeLine(helper);
        helper.runAfterDelay(5, () -> {
            Reloaded line = reloadAsFreshSession(helper);
            line.motor.tick();
            line.shaft.tick();
            // 鼓风机首 tick 前被拆：remove() 里 network.remove 因不在 members 提前 return
            helper.destroyBlock(FAN);
            KineticNetwork network = line.motor.getOrCreateNetwork();
            KineticNetworkUnloadedAccessor ledger = (KineticNetworkUnloadedAccessor) network;
            helper.assertTrue(Math.abs(ledger.dimblend$unloadedStress()) < EPSILON,
                    "removed fan must leave no unloaded stress, got " + ledger.dimblend$unloadedStress());
            helper.assertTrue(Math.abs(network.calculateStress()) < EPSILON,
                    "network without the fan must carry no stress, got " + network.calculateStress());
            helper.assertTrue(network.getSize() == 2, "network size must be 2, got " + network.getSize());
            helper.succeed();
        });
    }

    private static void placeLine(GameTestHelper helper) {
        helper.setBlock(MOTOR, AllBlocks.CREATIVE_MOTOR.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
        helper.setBlock(SHAFT, AllBlocks.SHAFT.get().defaultBlockState()
                .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        // 鼓风机朝东，传动面在西侧（EncasedFanBlock#hasShaftTowards = FACING 反向）
        helper.setBlock(FAN, AllBlocks.ENCASED_FAN.get().defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));
    }

    /** 读档重建的三个 BE（均未 tick）。 */
    private record Reloaded(CreativeMotorBlockEntity motor, KineticBlockEntity shaft, KineticBlockEntity fan) {
    }

    /**
     * 按"区块卸载 → 新会话读档"重建：存档快照走区块保存同一路径（含 id/坐标/附件）；
     * 旧 BE 标记区块卸载（Create 此时不调 remove()）；网络对象清回未初始化；
     * 新 BE 由 NBT 实例化放回区块，尚未 tick（SmartBlockEntity.initialize 未跑）。
     */
    private static Reloaded reloadAsFreshSession(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();
        KineticBlockEntity oldMotor = helper.getBlockEntity(MOTOR);
        KineticBlockEntity oldShaft = helper.getBlockEntity(SHAFT);
        KineticBlockEntity oldFan = helper.getBlockEntity(FAN);
        helper.assertTrue(Math.abs(oldFan.getTheoreticalSpeed()) == LOADED_SPEED,
                "precondition: motor must drive the fan before reload");
        helper.assertTrue(oldFan.calculateStressApplied() > 0.0F, "precondition: fan must apply stress");

        CompoundTag motorTag = oldMotor.saveWithFullMetadata(registries);
        CompoundTag shaftTag = oldShaft.saveWithFullMetadata(registries);
        CompoundTag fanTag = oldFan.saveWithFullMetadata(registries);

        KineticNetwork network = oldMotor.getOrCreateNetwork();
        oldMotor.onChunkUnloaded();
        oldShaft.onChunkUnloaded();
        oldFan.onChunkUnloaded();
        network.initialized = false;
        network.members.clear();
        network.sources.clear();
        KineticNetworkUnloadedAccessor ledger = (KineticNetworkUnloadedAccessor) network;
        ledger.dimblend$setUnloadedCapacity(0.0F);
        ledger.dimblend$setUnloadedStress(0.0F);
        ledger.dimblend$setUnloadedMembers(0);

        return new Reloaded(
                (CreativeMotorBlockEntity) replace(level, helper.absolutePos(MOTOR), motorTag, registries),
                (KineticBlockEntity) replace(level, helper.absolutePos(SHAFT), shaftTag, registries),
                (KineticBlockEntity) replace(level, helper.absolutePos(FAN), fanTag, registries));
    }

    private static BlockEntity replace(ServerLevel level, BlockPos pos, CompoundTag tag, HolderLookup.Provider registries) {
        BlockEntity fresh = BlockEntity.loadStatic(pos, level.getBlockState(pos), tag, registries);
        if (fresh == null) {
            throw new IllegalStateException("failed to reload block entity at " + pos);
        }
        level.setBlockEntity(fresh);
        return fresh;
    }

    /** 账本闭合：未加载份额清零、应力 = 鼓风机实时份额（只算一遍）、网络大小 = 在网成员数。 */
    private static void assertLedger(GameTestHelper helper, Reloaded line, int size, String stage) {
        KineticNetwork network = line.motor.getOrCreateNetwork();
        KineticNetworkUnloadedAccessor ledger = (KineticNetworkUnloadedAccessor) network;
        float expected = line.fan.calculateStressApplied() * Math.abs(line.fan.getTheoreticalSpeed());
        float actual = network.calculateStress();
        helper.assertTrue(Math.abs(ledger.dimblend$unloadedStress()) < EPSILON,
                stage + ": unloaded stress must be settled, got " + ledger.dimblend$unloadedStress());
        helper.assertTrue(Math.abs(actual - expected) < EPSILON,
                stage + ": fan stress must be counted once, expected " + expected + " got " + actual);
        helper.assertTrue(network.getSize() == size,
                stage + ": network size must be " + size + ", got " + network.getSize());
    }
}
