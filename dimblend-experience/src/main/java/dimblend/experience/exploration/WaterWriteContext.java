package dimblend.experience.exploration;

import javax.annotation.Nullable;

import net.minecraft.world.entity.player.Player;

/**
 * G3 写入归因上下文：{@code Level#setBlock} 本身不带"是谁/什么渠道写入"的信息，
 * 各写入渠道的 mixin 在调用前后经本类置位/复位 ThreadLocal，供
 * {@link IsolatedWaterRules} 判定放行。
 *
 * <p>两条通道：</p>
 * <ul>
 * <li><b>bypass</b>：冰破坏产水写入期间置位（见 IceBlockWaterBypassMixin /
 * PackedIceBreakWaterMixin），置位期间的源水写入无条件绕过降级。</li>
 * <li><b>placingPlayer</b>：玩家桶倒水期间归因（见 BucketItemMixin，发射器等
 * 非玩家路径 player=null 不置位）；归因玩家为创造模式时放行，生存模式照旧降级。</li>
 * </ul>
 *
 * <p>均为服务端单线程语义（世界写入都在主线程），进出必须成对（调用方负责
 * try/finally 或 HEAD/RETURN 配对）。</p>
 */
public final class WaterWriteContext {

    private static final ThreadLocal<Boolean> BYPASS = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Player> PLACING_PLAYER = new ThreadLocal<>();

    /** 进入 bypass 区段（冰产水写入）。 */
    public static void enterBypass() {
        BYPASS.set(Boolean.TRUE);
    }

    /** 退出 bypass 区段。 */
    public static void exitBypass() {
        BYPASS.set(Boolean.FALSE);
    }

    /** 当前写入是否在 bypass 区段内（冰产水）。 */
    public static boolean isBypassed() {
        return BYPASS.get();
    }

    /** 归因本次写入的玩家（桶倒水）。 */
    public static void enterPlayer(Player player) {
        PLACING_PLAYER.set(player);
    }

    /** 清除玩家归因。 */
    public static void exitPlayer() {
        PLACING_PLAYER.remove();
    }

    /** 当前归因玩家；无归因（机器/自然写入）返回 {@code null}。 */
    @Nullable
    public static Player currentPlacingPlayer() {
        return PLACING_PLAYER.get();
    }

    private WaterWriteContext() {
    }
}
