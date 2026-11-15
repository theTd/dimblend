package dimblend.experience.chassis;

import com.simibubi.create.AllBlocks;
import dimblend.experience.reward.RewardWagonBuilder;
import dimblend.experience.reward.RewardWagonPlan;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

/**
 * 把一个 {@link ChassisPreset} 真正生成到驾驶台脚下的主线轨道上（2026-11-15 创造调试）。
 *
 * <p><b>三种来源用各自的真实生成器。</b>标准底盘走 {@link ChassisConstruction#start}（逐排施工、
 * 强制加载、转向架配置全部照旧）；奖励车厢走 {@code RewardWagonBuilder}；末影龙追逐的刹车/水晶
 * 走该 mod 自己的 {@code DragonCars}。调试生成必须与正常生成完全同一条路径，否则测的就不是那节车。
 *
 * <p><b>中心坐标一致。</b>驾驶台下方必须是天然主线（{@link ChassisSelection#applicable}），而
 * {@code CorridorRails} 把主线钉在 {@code Y=64, Z=0}，所以驾驶台恒为 {@code (x, 65, 0)}——正是
 * {@code RewardWagonBuilder.center(x)} 与 {@code CarAssembler.center(x)} 的中心，两种生成器都不需要
 * 额外的偏移参数。
 *
 * <p><b>驾驶台先移除。</b>生成器只装配自己写的方块；留在中心格的 {@code create:controls} 既不是它们的
 * 方块，也不会被装配进 plot，必须先删掉。生成失败时退回一个驾驶台，调试者不用再做一个。
 */
public final class ChassisPresetSpawner {

    private ChassisPresetSpawner() {
    }

    /**
     * @return 是否已开始/完成生成；false 表示场地或生成器拒绝，调用方不再重复提示
     */
    public static boolean spawn(ServerLevel level, BlockPos controlsPos, ChassisPreset preset) {
        if (preset.family() == ChassisPreset.Family.STANDARD) {
            // start() 自己移除驾驶台并登记 45 排施工，失败时什么都不动。
            boolean started = ChassisConstruction.start(level, controlsPos, preset.blueprint());
            if (!started) ChassisConstruction.broadcast(level, "unsafe");
            return started;
        }
        if (!ChassisSite.clearCar(level, controlsPos, preset.halfLength(), preset.halfWidth(), preset.height(),
                Map.of(controlsPos, level.getBlockState(controlsPos)))) {
            ChassisConstruction.broadcast(level, "unsafe");
            return false;
        }
        level.removeBlock(controlsPos, false);
        boolean built = switch (preset.family()) {
            case REWARD -> spawnReward(level, controlsPos, preset);
            case DRAGON -> spawnDragon(level, preset, controlsPos.getX());
            case STANDARD -> false;
        };
        if (!built) {
            // 两个生成器都回滚了自己写的方块；把驾驶台还回去，方便改个位置再试。
            Block.popResource(level, controlsPos, new ItemStack(AllBlocks.TRAIN_CONTROLS.asItem()));
            ChassisConstruction.broadcast(level, "spawn_failed");
        }
        return built;
    }

    /**
     * 奖励车厢：真实生成器 + 真实溶解监视。
     *
     * <p>不登记站点的话，调试车永远不会“清空后消失”，也就测不到 2026-11-15 要求的粒子量 x4 与
     * 金库关闭音效；所以装配成功后立刻交给 {@code RewardWagonSites}。
     */
    private static boolean spawnReward(ServerLevel level, BlockPos controlsPos, ChassisPreset preset) {
        var car = RewardWagonBuilder.build(level, new RewardWagonPlan.Wagon(
                controlsPos.getX(), preset.content(), level.getRandom().nextLong()));
        if (car == null) {
            return false;
        }
        RewardWagonBuilder.registerDebugWagon(level, controlsPos.getX(), car);
        return true;
    }

    /**
     * Loaded only after the {@code dimblend_dragonchase} presence check, like
     * {@code DragonChaseTuningBridge}: the class body is the only place that names Dragon Chase
     * types, so the optional mod being absent cannot break loading here.
     */
    private static boolean spawnDragon(ServerLevel level, ChassisPreset preset, int centerX) {
        if (!ModList.get().isLoaded(ChassisPreset.DRAGON_CHASE_MOD_ID)) return false;
        return ChassisDragonSpawner.spawn(level, preset, centerX);
    }
}
