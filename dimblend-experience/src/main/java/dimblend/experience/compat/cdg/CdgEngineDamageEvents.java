package dimblend.experience.compat.cdg;

import com.jesz.createdieselgenerators.content.diesel_engine.IEngine;
import com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * 损坏柴油机的防反拆（2026-11-25 口径，提示文案 2026-11-27 改）：<b>扳手</b>拆不走坏机器。
 *
 * <p>Create 的 {@code IWrenchable#onSneakWrenched} 在动手前会 post 可取消的
 * {@link BlockEvent.BreakEvent}（{@code javap} 已核，默认方法里带可取消的
 * {@code NeoForgeCommonHooks.onBlockBreakEvent} 路径），于是在这里取消即可，
 * 不必去碰 CDG 的方块类结构。动作条文案走 {@link CdgEngineDamage#WRENCH_DENIED_KEY}
 * （"坏掉了 要先修好"），与 {@code CdgEnginePistonRepairMixin} 挡非潜行扳手右键用同一条。</p>
 *
 * <p>为什么只拦扳手：其它任何方式（镐子/手/钻头/爆炸）破坏都<b>允许</b>，但产物已经改成
 * {@link CdgEngineDamage#DAMAGED_SCRAP_COUNT} 张黄铜板，机器本体不会进背包，所以不存在
 * "拆了再装一台新的从而洗掉损坏"——见 {@code CdgPistonDamageDropMixin}。拦扳手的理由只剩
 * 扳手拆除本来就不掉本体，留着会让"扳手一转一拆"变成静默销毁机器，不如给一句解释。</p>
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

    /**
     * 主手或副手拿扳手。
     *
     * <p>判据收在 {@link CdgEngineDamage#isWrench(ItemStack)}：
     * 除 Create 的扳手本体外还认 {@code c:tools/wrench} 标签，附属模组的扳手（原口径只认
     * {@code AllItems.WRENCH}）也就挡得住。</p>
     */
    private static boolean holdsWrench(ServerPlayer player) {
        return CdgEngineDamage.isWrench(player.getMainHandItem())
                || CdgEngineDamage.isWrench(player.getOffhandItem());
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
