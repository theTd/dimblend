package dimblend.experience.chassis;

import com.simibubi.create.AllBlocks;
import dimblend.experience.DimBlend;
import dimblend.experience.compat.sable.ChassisRailLock;
import dimblend.experience.compat.sable.ChassisWorldAnchorProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@EventBusSubscriber(modid = DimBlend.MODID)
public final class ChassisEvents {
    private record Placement(ServerLevel level, BlockPos pos) {}
    private static final List<Placement> PENDING = new ArrayList<>();
    private ChassisEvents() {}

    public static boolean available() {
        var mods = ModList.get();
        return mods.isLoaded("dimblend") && mods.isLoaded("dimblend_blocks") && mods.isLoaded("create")
                && mods.isLoaded("copycats") && mods.isLoaded("simurail") && mods.isLoaded("sable") && mods.isLoaded("railways");
    }

    public static boolean inDimension(ServerLevel level) {
        return level.dimension().location().toString().equals("dimblend:rotating");
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!available() || !(event.getEntity() instanceof Player)
                || !(event.getLevel() instanceof ServerLevel level) || !inDimension(level)
                || !event.getBlockSnapshot().getCurrentState().is(AllBlocks.TRAIN_CONTROLS.get())) return;
        PENDING.add(new Placement(level, event.getPos().immutable()));
    }

    /**
     * 驾驶台右键：主手空手或 Create 扳手、未潜行、且驾驶台当前可用时才开选择页。
     *
     * <p>副手事件直接放行（{@code getHand() != MAIN_HAND}）；潜行放行，保留扳手拆卸；
     * 其他物品放行，不吞掉放置/使用。服务端只有在 {@link ChassisSelection#open} 真正建立
     * 会话后才取消事件；客户端用同一份 {@link ChassisSelection#applicable} 判据取消，
     * 避免“点了没菜单”。</p>
     *
     * <p><b>物品到触发器的映射只走一处。</b>主手是否算触发器由
     * {@link ChassisInteraction#menuTrigger} 回答，这里不重复写物品判断：两端事件同一个方法，
     * 若映射散落在事件体里，单测就碰不到“非空即触发”这类回归，而它会把普通方块/食物的右键
     * 一并吞掉——客户端预测已扣物品、服务端却按原版放行，正是要避免的分叉。</p>
     *
     * <p><b>取消点在后，服务端权威。</b>客户端取消只是提前结束本地预测；包照发（NeoForge
     * {@code MultiPlayerGameMode.startPrediction} 无条件发包），服务端在
     * {@link ChassisSelection#open} 建立会话后才取消，失败就放行原版路径。这样“客户端取消、
     * 服务端照常执行”只可能发生在重开瞬间的极窄窗口，而不是默认结果。</p>
     */
    @SubscribeEvent
    public static void onUse(PlayerInteractEvent.RightClickBlock event) {
        if (!available() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof Player player)) return;
        boolean menuTrigger = ChassisInteraction.menuTrigger(event.getItemStack().isEmpty(),
                event.getItemStack().is(com.simibubi.create.AllItems.WRENCH));
        boolean applicable = ChassisSelection.applicable(event.getLevel(), player, event.getPos());
        if (ChassisInteraction.decide(true, player.isShiftKeyDown(), menuTrigger, applicable)
                != ChassisInteraction.Action.OPEN) return;
        if (event.getLevel() instanceof ServerLevel) {
            if (!(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)
                    || !ChassisSelection.open(serverPlayer, event.getPos())) {
                return;
            }
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        if (ModList.get().isLoaded("sable") && ModList.get().isLoaded("simurail")) {
            try {
                ChassisWorldAnchorProbe.tick(event.getServer());
            } catch (RuntimeException failure) {
                DimBlend.LOGGER.warn("Chassis world anchor probe tick failed", failure);
                ChassisWorldAnchorProbe.stop("tick_error");
            }
        }
        if (!available()) return;
        List<Placement> placements = List.copyOf(PENDING);
        PENDING.clear();
        for (Placement placement : placements) ChassisSelection.validatePlacement(placement.level(), placement.pos());
        ChassisSelection.tick(event.getServer());
        for (ServerLevel level : event.getServer().getAllLevels()) {
            if (inDimension(level)) {
                ChassisConstruction.tick(level);
                // R6 depot chassis: blocks were written by worldgen, Sable assembly
                // happens here, the first time a player comes near each site.
                StationDepotAssembly.tick(level);
                // Production rail lock: registered standard chassis only, after the
                // assembly ticks above have had a chance to register a new car.
                ChassisRailLock.tick(level);
            }
        }
    }

    @SubscribeEvent
    public static void onStop(ServerStoppedEvent event) {
        if (ModList.get().isLoaded("sable") && ModList.get().isLoaded("simurail")) {
            ChassisWorldAnchorProbe.shutdown();
            ChassisRailLock.shutdown();
        }
        PENDING.clear();
        ChassisSelection.clear();
    }

    /** Release every rail lock belonging to a dimension that is going away. */
    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level
                && ModList.get().isLoaded("sable") && ModList.get().isLoaded("simurail")) {
            ChassisRailLock.forget(level);
        }
    }

    /**
     * Command-facing rail-lock facade. Keeps {@link ChassisProbeCommand} free of executable
     * references to the Sable-dependent {@link ChassisRailLock} class when Sable/SimuRail
     * are not installed.
     */
    public static void railLockRequest(ServerLevel level, UUID id) {
        if (ModList.get().isLoaded("sable") && ModList.get().isLoaded("simurail")) {
            ChassisRailLock.request(level, id);
        }
    }

    public static void railLockRelease(ServerLevel level, UUID id, String reason) {
        if (ModList.get().isLoaded("sable") && ModList.get().isLoaded("simurail")) {
            ChassisRailLock.release(level, id, reason);
        }
    }

    public static String railLockStatus(ServerLevel level, UUID id) {
        if (!ModList.get().isLoaded("sable") || !ModList.get().isLoaded("simurail")) {
            return "UNAVAILABLE";
        }
        return ChassisRailLock.status(level, id);
    }
}
