package dimblend.experience.compat.cdg;

import java.util.ArrayList;
import java.util.List;

import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.HugeDieselEngineBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.huge.PoweredEngineShaftBlockEntity;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlock;
import com.jesz.createdieselgenerators.content.diesel_engine.normal.DieselEngineBlock;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * B8 柴油机放置守卫（用户拍板 2026-10-10）：放置三类 CDG 柴油机时，所接入的
 * Create 动力网络必须已静止；接入运转中网络的柴油机放行放置后破坏自身按 loot 掉落。
 *
 * <p>拦截方式为「放行放置 + 破坏自身掉落」，<b>不取消</b> {@code EntityPlaceEvent}——
 * 理由同 {@code PhysicsAssemblerGuard}：取消只回滚服务端，客户端预测已扣物品会鬼影。
 * 与 F1 不同：拆除延迟一个完整 tick。BE 在放置当 tick 进入 pending 缓冲，首个
 * tick 才 {@code attachKinetics} 写入网络传导转速（{@code RotationPropagator#handleAdded}
 * 同步传遍已连邻居），当 tick 末读 {@code getSpeed()} 恒为 0；故登记后跳过下一个
 * {@code ServerTickEvent.Post}，再下一个 Post 才判定。</p>
 *
 * <p>转速判据：普通/组合式读引擎 BE 的 {@code getSpeed()}（首 tick 后即为网络传导
 * 转速；未加油的新机自身产出为 0，非零只可能来自所接网络）；巨型机本体非 Kinetic，
 * 读 {@code getShaft()} 轴 BE 的 {@code getSpeed()}（轴为 null = 前方第 2 格没有
 * Create 传动杆，未接入任何网络，放行）。</p>
 *
 * <p>不豁免创造模式（需求口径：每次放置都算）。<b>已知覆盖边界</b>：只拦
 * {@code EntityPlaceEvent} 路径（玩家手持、Create 机械手等经 {@code BlockItem.place}
 * 的放置）；蓝图大炮、{@code /setblock}、其他 mod 直接 {@code setBlock} 不触发该事件。
 * CDG 缺席时事件直接放行（CDG 类型只出现在本类方法体内，JVM 惰性解析不会误加载）。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class CdgPlacementGuard {

    /** 本 tick 登记的放置：跳过下一个 Post 让 BE 完成首 tick 入网，再下一个 Post 判定。 */
    private record PendingCheck(ServerLevel level, BlockPos pos) {
    }

    private static final List<PendingCheck> PENDING = new ArrayList<>();
    private static final List<PendingCheck> READY = new ArrayList<>();

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!ModList.get().isLoaded("createdieselgenerators")) {
            return;
        }
        if (!Config.DIESEL_PLACEMENT_GUARD.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // getPlacedBlock() 对非玩家实体返回放置前状态（BlockEvent 构造器差异），
        // 统一用快照的目标状态判定（同 PhysicsAssemblerGuard）
        if (!isDieselEngine(event.getBlockSnapshot().getCurrentState().getBlock())) {
            return;
        }
        PENDING.add(new PendingCheck(level, event.getPos()));
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!READY.isEmpty()) {
            List<PendingCheck> batch = List.copyOf(READY);
            READY.clear();
            for (PendingCheck pending : batch) {
                checkAndDestroy(pending);
            }
        }
        // 本 tick 新登记的整体顺延：下个 Post 才进 READY，下下个 Post 判定
        READY.addAll(PENDING);
        PENDING.clear();
    }

    /** 三类柴油机方块显式枚举：普通 / 组合式（每格同一块型）/ 巨型。 */
    private static boolean isDieselEngine(Block block) {
        return block instanceof DieselEngineBlock
                || block instanceof ModularDieselEngineBlock
                || block instanceof HugeDieselEngineBlock;
    }

    private static void checkAndDestroy(PendingCheck pending) {
        ServerLevel level = pending.level();
        BlockPos pos = pending.pos();
        BlockState state = level.getBlockState(pos);
        float networkSpeed;
        if (state.getBlock() instanceof HugeDieselEngineBlock) {
            if (!(level.getBlockEntity(pos) instanceof HugeDieselEngineBlockEntity engine)) {
                return; // 放置被其他监听取消，或方块已被替换：无事可做
            }
            PoweredEngineShaftBlockEntity shaft = engine.getShaft();
            networkSpeed = shaft == null ? 0.0F : shaft.getSpeed();
        } else if (isDieselEngine(state.getBlock())) {
            if (!(level.getBlockEntity(pos) instanceof KineticBlockEntity kbe)) {
                return;
            }
            networkSpeed = kbe.getSpeed();
        } else {
            return;
        }
        if (networkSpeed == 0.0F) {
            return;
        }
        // destroyBlock 统一走原版破坏路径：破碎粒子 + GameEvent.BLOCK_DESTROY + loot 掉落
        level.destroyBlock(pos, true);
    }

    private CdgPlacementGuard() {
    }
}
