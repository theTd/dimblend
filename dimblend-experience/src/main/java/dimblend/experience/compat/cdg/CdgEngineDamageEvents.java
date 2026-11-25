package dimblend.experience.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import com.simibubi.create.AllItems;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 损坏柴油机的防反拆（2026-11-25 口径）：<b>扳手</b>拆不走坏机器。
 *
 * <p>Create 的 {@code IWrenchable#onSneakWrenched} 在动手前会 post 可取消的
 * {@link BlockEvent.BreakEvent}（{@code javap} 已核，默认方法里带可取消的
 * {@code NeoForgeCommonHooks.onBlockBreakEvent} 路径），于是在这里取消即可，
 * 不必去碰 CDG 的方块类结构。</p>
 *
 * <p>为什么只拦扳手：正常用镐挖是<b>允许</b>的——掉落物会带损坏标记，
 * 放回去还是坏的（见 {@code CdgPistonDamageDropMixin}/{@code CdgPistonDamageRestoreMixin}）。
 * 拦扳手的原因是扳手拆除<b>不掉落带标记的本体</b>、也不走 {@code playerDestroy}，
 * 会变成"拆了再装一台新的"从而洗掉损坏。</p>
 */
public final class CdgEngineDamageEvents {

    /** 由 {@code DimBlend} 构造期调用（manifest 里有调用行）。 */
    public static void register() {
        NeoForge.EVENT_BUS.register(CdgEngineDamageEvents.class);
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!CdgEngineDamage.isEngineBlock(event.getState())) {
            return;
        }
        if (!holdsWrench(player)) {
            return;
        }
        IEngine engine = resolveEngine(level, event.getPos());
        if (engine == null) {
            return;
        }
        if (!engine.self().getData(CdgAttachments.ENGINE_STATE).pistonDamaged) {
            return;
        }
        event.setCanceled(true);
        CdgEngineDamage.actionbar(player, CdgEngineDamage.WRENCH_DENIED_KEY, ChatFormatting.RED);
    }

    /** 主手或副手拿扳手（Create 的扳手物品）。 */
    private static boolean holdsWrench(ServerPlayer player) {
        return player.getMainHandItem().is(AllItems.WRENCH.get())
                || player.getOffhandItem().is(AllItems.WRENCH.get());
    }

    /** 组合式部件解析到 controller（损坏状态记在 controller 上）。 */
    private static IEngine resolveEngine(ServerLevel level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ModularDieselEngineBlockEntity modular && !modular.isController()) {
            return modular.getControllerBE();
        }
        return be instanceof IEngine engine ? engine : null;
    }

    private CdgEngineDamageEvents() {
    }
}
