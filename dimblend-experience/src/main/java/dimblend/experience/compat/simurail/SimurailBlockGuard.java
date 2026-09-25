package dimblend.experience.compat.simurail;

import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * E1 Simurail 自带方块保护（用户拍板口径：Simurail 自带方块防爆防拆，
 * 仅创造模式可编辑）。
 *
 * <p><b>覆盖面（如实）</b>：玩家破坏（BreakEvent）/挖掘（LeftClickBlock）/交互
 * （RightClickBlock）/爆炸（Detonate）——四处理器全部覆盖且经复核闭环核证。
 * <b>机器/非玩家破坏是已知接受风险</b>：NeoForge 21.1 的 BreakEvent 仅由
 * ServerPlayerGameMode 经 fireBlockBreak 触发（契约非空 Player），Create 静态
 * 钻头/锯、机械臂、滚压机、凋灵等 null-player 路径不触发任何事件。已用
 * Create 的 {@code create:non_breakable} 方块标签数据包补强关闭最大旁路面
 * （钻头/锯/机械臂两类破坏均消费该 tag，见
 * {@code data/create/tags/block/non_breakable.json}）；滚压机/部署器工具挖掘/
 * 凋灵残余旁路在案接受。若需全覆盖需 mixin BlockHelper.destroyBlockAs（重）。</p>
 *
 * <p><b>方块清单</b>（运行 jar 0.0.0-a 注册表，两方块；CenteredGangwayJoint
 * 为未注册死类——见 SimurailBlocksHolder 注释）。</p>
 *
 * <p><b>放置路径不拦</b>（口径在案）：Simurail 方块无生存配方且不可被生存
 * 获得（本防护使其无掉落），放置风险趋零。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class SimurailBlockGuard {

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!Config.SIMURAIL_PROTECT.get()) {
            return;
        }
        if (!isSimurailBlock(event.getState())) {
            return;
        }
        Player player = event.getPlayer();
        if (player == null || !player.isCreative()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        // 创造模式外玩家左键（徒手/工具）也拦，避免靠"开始挖掘进度"绕过 BreakEvent
        if (!Config.SIMURAIL_PROTECT.get() || event.getEntity().isCreative()) {
            return;
        }
        if (isSimurailBlock(event.getLevel().getBlockState(event.getPos()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!Config.SIMURAIL_PROTECT.get() || event.getEntity().isCreative()) {
            return;
        }
        if (isSimurailBlock(event.getLevel().getBlockState(event.getPos()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplode(ExplosionEvent.Detonate event) {
        if (!Config.SIMURAIL_PROTECT.get()) {
            return;
        }
        event.getAffectedBlocks().removeIf(pos -> isSimurailBlock(event.getLevel().getBlockState(pos)));
    }

    private static boolean isSimurailBlock(BlockState state) {
        if (!ModList.get().isLoaded("simurail")) {
            return false;
        }
        return SimurailBlocksHolder.isSimurailBlock(state);
    }

    private SimurailBlockGuard() {
    }
}