package dimblend.experience.compat.simurail;

import com.crystaelix.simurail.content.bogey.PhysicsBogeyBlock;
import com.crystaelix.simurail.content.bogey.PhysicsBogeyMenu;
import dimblend.experience.Config;
import dimblend.experience.DimBlend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * E1 Simurail 自带方块保护与物理转向架交互规则（DECISIONS.md #1）。
 *
 * <p><b>覆盖面（如实）</b>：玩家破坏（BreakEvent）/挖掘（LeftClickBlock）/交互
 * （RightClickBlock）/爆炸（Detonate）——四处理器全部覆盖且经复核闭环核证。
 * 取消服务端交互或破坏时同步刷新 {@code ServerPlayer.inventoryMenu.sendAllDataToRemote()}，
 * 防止客户端预测扣减方块后栈数量失步。</p>
 *
 * <p><b>物理转向架右键口径</b>：无论生存或创造、站立或潜行，仅空手或扳手
 * （{@code AllItems.WRENCH} 或 {@code #c:tools/wrench}）打开 {@code PhysicsBogeyMenu}；
 * 扳手永不旋转或拆卸转向架（连创造模式潜行扳手也只开菜单）；其余任何手持物品均跳过菜单走正常
 * {@code stack.useOn} 放置/使用逻辑。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID)
public final class SimurailBlockGuard {

    /**
     * Tracks a third-party {@code #c:tools/wrench} right-click on a bogey across
     * {@code EventPriority.HIGH}, where Create's {@code WrenchEventHandler} unconditionally cancels
     * the event with {@code InteractionResult.PASS} even when {@code onWrenched}/{@code onSneakWrenched}
     * returned {@code PASS}.
     */
    private static final ThreadLocal<PlayerInteractEvent.RightClickBlock> THIRD_PARTY_BOGEY_WRENCH_EVENT =
            new ThreadLocal<>();

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
            resyncInventory(player);
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        THIRD_PARTY_BOGEY_WRENCH_EVENT.remove();
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!isSimurailBlock(state)) {
            return;
        }
        Player player = event.getEntity();
        boolean creative = player.isCreative();
        boolean survival = player instanceof ServerPlayer serverPlayer
                ? serverPlayer.gameMode.getGameModeForPlayer() == GameType.SURVIVAL
                : !creative && !player.isSpectator() && player.getAbilities().mayBuild;
        ItemStack stack = event.getItemStack();
        boolean holdingCreateWrench = isCreateWrench(stack);
        boolean holdingBogeyWrench = holdingCreateWrench || (!stack.isEmpty() && stack.is(Tags.Items.TOOLS_WRENCH));

        SimurailSurvivalInteraction.Decision decision = SimurailSurvivalInteraction.decideRightClick(
                Config.SIMURAIL_PROTECT.get(),
                creative,
                survival,
                SimurailBlocksHolder.isBogey(state),
                SimurailBlocksHolder.isCoupler(state),
                holdingBogeyWrench,
                holdingCreateWrench,
                Config.ALLOW_SURVIVAL_COUPLER_INTERACTION.get());
        switch (decision) {
            case ALLOW -> {
                // Fall through to vanilla block/item interaction.
            }
            case FORCE_BOGEY_MENU -> {
                event.setUseBlock(TriState.TRUE);
                event.setUseItem(TriState.FALSE);
                if (!holdingCreateWrench) {
                    THIRD_PARTY_BOGEY_WRENCH_EVENT.set(event);
                }
            }
            case DENY -> {
                event.setCanceled(true);
                resyncInventory(player);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.NORMAL, receiveCanceled = true)
    public static void restoreBogeyWrenchAfterCreateHandler(PlayerInteractEvent.RightClickBlock event) {
        PlayerInteractEvent.RightClickBlock tracked = THIRD_PARTY_BOGEY_WRENCH_EVENT.get();
        if (tracked != event) {
            return;
        }
        THIRD_PARTY_BOGEY_WRENCH_EVENT.remove();
        if (event.isCanceled() && event.getCancellationResult() == InteractionResult.PASS) {
            event.setCanceled(false);
            event.setUseBlock(TriState.TRUE);
            event.setUseItem(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public static void onExplode(ExplosionEvent.Detonate event) {
        if (!Config.SIMURAIL_PROTECT.get()) {
            return;
        }
        event.getAffectedBlocks().removeIf(pos -> isSimurailBlock(event.getLevel().getBlockState(pos)));
    }

    /**
     * Mixin target helper for {@code PhysicsBogeyBlock#useItemOn}. Parameter uses vanilla
     * {@link Block} so {@code @EventBusSubscriber} method-descriptor scanning never resolves
     * Simurail classes when Simurail is absent.
     */
    public static ItemInteractionResult onBogeyUseItemOn(Block block, ItemStack stack, BlockState state,
            Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        boolean opensMenu = SimurailSurvivalInteraction.bogeyOpensMenu(
                hand == InteractionHand.MAIN_HAND,
                player.getMainHandItem().isEmpty(),
                player.getOffhandItem().isEmpty(),
                isBogeyWrench(stack));
        if (opensMenu) {
            if (level.isClientSide()) {
                return ItemInteractionResult.SUCCESS;
            }
            BogeyMenuOpener.open(block, level, pos, player);
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    public static boolean isBogeyWrench(ItemStack stack) {
        return !stack.isEmpty() && (isCreateWrench(stack) || stack.is(Tags.Items.TOOLS_WRENCH));
    }

    private static boolean isCreateWrench(ItemStack stack) {
        return !stack.isEmpty() && ModList.get().isLoaded("create") && CreateWrenchHolder.isWrench(stack);
    }

    private static void resyncInventory(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.inventoryMenu.sendAllDataToRemote();
        }
    }

    private static boolean isSimurailBlock(BlockState state) {
        if (!ModList.get().isLoaded("simurail")) {
            return false;
        }
        return SimurailBlocksHolder.isSimurailBlock(state);
    }

    private static final class CreateWrenchHolder {
        static boolean isWrench(ItemStack stack) {
            return com.simibubi.create.AllItems.WRENCH.isIn(stack);
        }
    }

    private static final class BogeyMenuOpener {
        static void open(Block block, Level level, BlockPos pos, Player player) {
            if (block instanceof PhysicsBogeyBlock bogeyBlock) {
                bogeyBlock.withBlockEntityDo(level, pos,
                        be -> player.openMenu(be, buf -> PhysicsBogeyMenu.prepare(buf, be, player.isSecondaryUseActive())));
            }
        }
    }

    private SimurailBlockGuard() {
    }
}
