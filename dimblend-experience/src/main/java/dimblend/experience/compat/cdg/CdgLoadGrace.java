package dimblend.experience.compat.cdg;

import dimblend.experience.Config;
import net.minecraft.server.level.ServerLevel;

/**
 * 柴油机读档宽限（B6）：开服后 {@code dieselLoadGraceSeconds} 秒内不判爆。
 * 判定本体见 {@link CdgOverloadMath#isWithinLoadGrace}；这里只负责读配置与服务端 tick。
 * 调用方须已确认 {@code level} 是服务端（SERVER 配置仅服务端可读）。
 */
public final class CdgLoadGrace {

    private static final int TICKS_PER_SECOND = 20;

    private CdgLoadGrace() {
    }

    /** 当前是否处于读档宽限内：此时不累计过载确认、不推进引信倒计时。 */
    public static boolean active(ServerLevel level) {
        int graceTicks = Config.DIESEL_LOAD_GRACE_SECONDS.get() * TICKS_PER_SECOND;
        return CdgOverloadMath.isWithinLoadGrace(level.getServer().getTickCount(), graceTicks);
    }
}
