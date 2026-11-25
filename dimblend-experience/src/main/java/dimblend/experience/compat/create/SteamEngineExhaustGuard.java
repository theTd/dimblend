package dimblend.experience.compat.create;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllItems;

import dimblend.experience.compat.create.SteamEngineExhaustAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

/**
 * 32 秒排气窗口的"不可破坏"守卫（NeoForge 事件层，零 Mixin）。
 *
 * <p>保护范围：正在排气的<b>蒸汽引擎本体</b> + 它贴着的<b>锅炉储罐格</b>
 * （{@code SteamEngineBlock.getFacing(state).getOpposite()}，与 {@code isValid()} 同源）。
 * 为什么连储罐一起保护：断轴那一下引擎已经不再输出，玩家真正的"止损"动作是拆锅炉；
 * 32 秒的排气动画如果一巴掌就能拆掉，就变成了纯装饰，且拆锅炉会让引擎 {@code isValid()==false}
 * 提前"复活"流程。</p>
 *
 * <p>四个入口：</p>
 * <ul>
 *   <li>{@code BreakSpeed} → 速度 0（进度永不累积，等效不可破坏，但不改 {@code getDestroySpeed}，
 *       免得把继承来的硬度语义整个吃掉）；</li>
 *   <li>{@code BlockEvent.BreakEvent} → 取消（覆盖绕过挖掘速度的路径）；</li>
 *   <li>{@code ExplosionEvent.Detonate} → 从受影响列表里摘掉（苦力怕/TNT 也炸不掉）；</li>
 *   <li>{@code PlayerInteractEvent.RightClickBlock} → 手持 Create 扳手时取消 + FAIL + 红色动作条
 *       （引擎的 {@code onWrenched}/{@code onSneakWrenched} 是继承方法，Mixin 打不进去；
 *       从物品使用这一层拦，两种扳手路径一次覆盖，储罐格也一并覆盖）。</li>
 * </ul>
 */
public final class SteamEngineExhaustGuard {

    /** 动作条文案键（中英见 manifest 的 lang 片段）。 */
    public static final String EXHAUSTING_MESSAGE_KEY = "message.dimblend_experience.steam_engine_exhausting";

    /** 该坐标是否被"正在排气的引擎"保护：引擎本体，或某台排气引擎的锅炉格。 */
    public static boolean isProtected(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        if (SteamEngineExhaustAccess.isExhaustingAt(level, pos)) {
            return true;
        }
        for (Direction direction : Direction.values()) {
            BlockPos enginePos = pos.relative(direction);
            BlockState engineState = SteamEngineExhaustAccess.loadedBlockState(level, enginePos);
            if (engineState == null || engineState.getBlock() != AllBlocks.STEAM_ENGINE.get()) {
                continue;
            }
            if (!SteamEngineOverload.boilerPos(engineState, enginePos).equals(pos)) {
                continue;
            }
            if (SteamEngineExhaustAccess.isExhaustingAt(level, enginePos)) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        if (event.getPosition().isEmpty()) {
            return;
        }
        if (isProtected(event.getEntity().level(), event.getPosition().get())) {
            event.setNewSpeed(0.0F);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && isProtected(level, event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        event.getAffectedBlocks().removeIf(pos -> isProtected(level, pos));
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!event.getItemStack().is(AllItems.WRENCH.get())) {
            return;
        }
        if (!isProtected(event.getLevel(), event.getPos())) {
            return;
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
        Player player = event.getEntity();
        if (!event.getLevel().isClientSide && player != null) {
            player.displayClientMessage(Component.translatable(EXHAUSTING_MESSAGE_KEY), true);
        }
    }

    private SteamEngineExhaustGuard() {
    }
}
