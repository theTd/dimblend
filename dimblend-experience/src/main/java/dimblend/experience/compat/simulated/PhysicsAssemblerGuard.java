package dimblend.experience.compat.simulated;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * F1 Simulated 物理组装器摆放拦截（用户拍板口径：生存禁、创造放行）。
 *
 * <p>目标 {@code simulated:physics_assembler}（Simulated 模组，以 jarjar 内嵌于
 * Create Aeronautics 合集包，mod id {@code simulated}）。注册表名比对，无编译
 * 依赖；`simulated` 缺席时守卫直接放行。机器/非玩家实体放置一律拦截
 * （非创造玩家代理同样落入"非创造"分支）。</p>
 *
 * <p>拦截方式为「放行放置 + 破坏自身返还」，<b>不取消</b> {@code EntityPlaceEvent}：
 * 取消只回滚服务端，而客户端预测在生存下（{@code MultiPlayerGameMode#performUseItemOn}
 * 直接执行 {@code Item#useOn} 且事后不恢复物品数量）已经扣掉了物品；服务端因物品
 * 净变化为零不再下发槽位纠正，客户端手里的组装器表现为消失不返还。放行放置让
 * 客户端预测与服务端终态一致，随后在本服务端 tick 末尾拆除方块返还，无鬼影、
 * 无物品丢失。拆除必须延迟到 tick 末尾：事件回调期间放置事务尚未收尾（状态虽
 * 已写入区块，但快照的客户端同步/邻居通知未应用、取消回滚路径仍在场），当场
 * 拆会与其竞争——旧破坏自身方案的鬼影即源于此。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class PhysicsAssemblerGuard {

    private static final ResourceLocation PHYSICS_ASSEMBLER =
            ResourceLocation.fromNamespaceAndPath("simulated", "physics_assembler");

    /** 本 tick 待拆除的放置。登记时不拦截，tick 末尾放置事务收尾后再拆。 */
    private record PendingTeardown(ServerLevel level, BlockPos pos, Entity placer) {
    }

    private static final List<PendingTeardown> PENDING = new ArrayList<>();

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!Config.ASSEMBLER_GUARD.get()) {
            return;
        }
        if (!ModList.get().isLoaded("simulated")) {
            return;
        }
        // getPlacedBlock() 对非玩家实体返回放置前状态（BlockEvent 构造器差异），
        // 统一用快照的目标状态判定，null/非玩家放置者同样能识别
        if (!PHYSICS_ASSEMBLER.equals(BuiltInRegistries.BLOCK.getKey(event.getBlockSnapshot().getCurrentState().getBlock()))) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Player player && player.isCreative()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            PENDING.add(new PendingTeardown(level, event.getPos(), entity));
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) {
            return;
        }
        List<PendingTeardown> batch = List.copyOf(PENDING);
        PENDING.clear();
        for (PendingTeardown pending : batch) {
            teardown(pending);
        }
    }

    private static void teardown(PendingTeardown pending) {
        BlockState state = pending.level().getBlockState(pending.pos());
        if (!PHYSICS_ASSEMBLER.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
            return; // 放置被其他监听取消，或方块已被替换：无事可做
        }
        // 真人玩家用精确类判据：NeoForge FakePlayer（机械手等）继承 ServerPlayer，
        // instanceof 区分不了；真人必是原生 ServerPlayer，假代理一律按掉落处理。
        boolean realPlayer = pending.placer() != null && pending.placer().getClass() == ServerPlayer.class;
        // destroyBlock 统一走原版破坏路径：破碎粒子 + GameEvent.BLOCK_DESTROY + 流体恢复
        pending.level().destroyBlock(pending.pos(), !realPlayer, pending.placer());
        if (realPlayer) {
            ServerPlayer player = (ServerPlayer) pending.placer();
            ItemStack refund = new ItemStack(state.getBlock());
            player.getInventory().placeItemBackInInventory(refund);
        }
    }

    private PhysicsAssemblerGuard() {
    }
}
