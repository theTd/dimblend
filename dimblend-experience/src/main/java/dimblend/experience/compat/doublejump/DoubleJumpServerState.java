package dimblend.experience.compat.doublejump;

/**
 * 单个服务端玩家的二段跳状态（纯 Java，零 Minecraft 依赖）。
 *
 * <p>与客户端状态机互补：客户端负责“按下沿 + 一次滞空一次”，服务端必须独立持有
 * {@code used} 与冷却，不能相信客户端只发一次包——{@code DoubleJumpPacket} 可被任意重发。</p>
 */
public final class DoubleJumpServerState {

    /** 一次触发后的冷却 tick（上游 {@code triggerDoubleJump} 的常量 10）。 */
    public static final int COOLDOWN_TICKS = 10;

    private boolean used;
    private int cooldownTicks;

    /**
     * 落地（含水中/攀爬）复位：允许下一次滞空再跳一次。
     */
    public void reset() {
        this.used = false;
        this.cooldownTicks = 0;
    }

    /**
     * 每个服务端 tick 推进冷却。
     */
    public void tickCooldown() {
        if (this.cooldownTicks > 0) {
            --this.cooldownTicks;
        }
    }

    /**
     * 当前是否允许触发：本次滞空未用过且冷却结束。地面/受阻判定由调用方负责。
     */
    public boolean canTrigger() {
        return !this.used && this.cooldownTicks <= 0;
    }

    /**
     * 记账：本次滞空已用掉唯一一次二段跳并进入冷却。
     */
    public void onTriggered() {
        this.used = true;
        this.cooldownTicks = COOLDOWN_TICKS;
    }

    public boolean isUsed() {
        return this.used;
    }

    public int cooldownTicks() {
        return this.cooldownTicks;
    }
}
