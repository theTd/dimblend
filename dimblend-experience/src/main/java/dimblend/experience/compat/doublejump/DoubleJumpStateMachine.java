package dimblend.experience.compat.doublejump;

/**
 * 二段跳客户端判定状态机（纯 Java，零 Minecraft 依赖，便于逐 tick 单测）。
 *
 * <h2>上游缺陷</h2>
 * <p>{@code double-jump-neoforge-1.0.4} 的 {@code DoubleJumpClient#tick} 用
 * {@code canTrigger() && jumpingNow && !prevJumping && airTicks >= 2} 判定：</p>
 * <ul>
 *   <li>按住空格从地面起跳时，离地第 1 tick 的 {@code !prevJumping} 成立，但
 *       {@code airTicks >= 2} 把它挡掉；第 2 tick 起 {@code prevJumping} 已为 true，
 *       于是<b>永远等不到新的按下沿</b>；</li>
 *   <li>快速二连点若第二次按下落在离地前 2 tick 内，被 {@code airTicks >= 2} 直接丢弃；</li>
 *   <li>客户端从不置 {@code used}，服务端也不校验，门形同虚设。</li>
 * </ul>
 *
 * <h2>本状态机的语义</h2>
 * <ol>
 *   <li>地面（含水中/攀爬，上游视作 grounded）或 {@code blocked}（飞行/鞘翅/乘骑）时复位：
 *       清空 {@code armed}/{@code used}，并记下“离地时是否仍按着键”。</li>
 *   <li>离地第一 tick：若离地时没按键，立即 armed（走下台阶后第一次按下就能跳）；
 *       若按着键（地面起跳还没松），必须先在<b>空中</b>观察到一次松开才 armed。</li>
 *   <li>armed 且本 tick 出现按下沿（{@code clicked} 来自按键 click 计数，可捕捉同 tick 内的
 *       快速点按；{@code keyDown && !prevDown} 兜底）→ 触发一次，立刻 {@code used = true}。</li>
 *   <li>一次滞空最多触发一次，落地/受阻后复位。</li>
 * </ol>
 */
public final class DoubleJumpStateMachine {

    private boolean airborne;
    private boolean armed;
    private boolean used;
    private boolean heldOnGround;
    private boolean prevDown;

    /**
     * 推进一 tick。
     *
     * @param grounded 是否处于地面/水中/攀爬（上游的 grounded 口径，直接复位）
     * @param blocked  是否处于禁止二段跳的状态（飞行、鞘翅、乘骑）
     * @param keyDown  本 tick 按键是否按住
     * @param clicked  本 tick 是否收到过一次“按下”事件（{@code KeyMapping#consumeClick()}）
     * @return 本 tick 是否应发送一次二段跳包
     */
    public boolean tick(boolean grounded, boolean blocked, boolean keyDown, boolean clicked) {
        if (grounded || blocked) {
            this.airborne = false;
            this.armed = false;
            this.used = false;
            this.heldOnGround = keyDown;
            this.prevDown = keyDown;
            return false;
        }
        if (!this.airborne) {
            this.airborne = true;
            this.armed = !this.heldOnGround;
        }
        if (!keyDown) {
            this.armed = true;
        }
        boolean pressed = clicked || (keyDown && !this.prevDown);
        this.prevDown = keyDown;
        if (!pressed || !this.armed || this.used) {
            return false;
        }
        this.used = true;
        this.armed = false;
        return true;
    }

    /** 服务端拒绝或玩家登出等场景可显式清空（当前客户端无需调用）。 */
    public void reset() {
        this.airborne = false;
        this.armed = false;
        this.used = false;
        this.heldOnGround = false;
        this.prevDown = false;
    }

    /** 本滞空是否已经触发过（供测试与诊断）。 */
    public boolean isUsed() {
        return this.used;
    }

    /** 是否已 armed（供测试与诊断）。 */
    public boolean isArmed() {
        return this.armed;
    }
}
