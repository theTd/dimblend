package dimblend.experience.jump;

/**
 * 每次滞空只放行一次空中跳的状态机（纯 Java）。落地/入流体/攀爬/乘骑/飞行等
 * 由 {@link MidAirJumpRules#shouldRecharge} 判定的状态负责重新装满。
 */
public final class MidAirJumpState {

    private boolean used;

    /**
     * @param recharge 本 tick 是否重新装满（落地、入流体、攀爬等）
     * @param eligible 本 tick 是否处于可触发状态
     * @param pressed  本 tick 是否出现跳跃按下沿
     * @return true = 本 tick 应执行一次空中跳
     */
    public boolean tick(boolean recharge, boolean eligible, boolean pressed) {
        if (recharge) {
            this.used = false;
            return false;
        }
        if (!eligible || !pressed || this.used) {
            return false;
        }
        this.used = true;
        return true;
    }

    public void reset() {
        this.used = false;
    }

    public boolean isUsed() {
        return this.used;
    }
}
