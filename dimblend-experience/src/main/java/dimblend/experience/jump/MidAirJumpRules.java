package dimblend.experience.jump;

/**
 * 空中二段跳的纯判定规则（零 Minecraft 依赖，便于逐 tick 单测）。
 *
 * <p>规格：起跳腾空后再次按下跳跃键可在空中再跳一次、脚底释放 cloud 粒子；
 * 走下悬崖/坠落接近地面时同样可二次起跳，重置下落高度以减伤（代替落地水）。</p>
 *
 * <p>tick 时序纪律（实机事故沉淀）：所有地面/状态判定都必须取
 * {@code LocalPlayer.aiStep} 中 {@code super.aiStep()} **之前**的值。原版地面起跳
 * 在该 tick 的 {@code move()} 里把玩家抬离地面，若在移动后观察 {@code onGround()}
 * 会把「地面按下的第一次跳跃」误判成一次空中按下沿。</p>
 */
public final class MidAirJumpRules {

    /**
     * 本 tick 是否重置「本次滞空已用」——落地、入流体、攀爬、乘骑、飞行、鞘翅都算重新起跳。
     */
    public static boolean shouldRecharge(boolean onGround, boolean inFluid, boolean climbable,
                                         boolean passenger, boolean flying, boolean fallFlying) {
        return onGround || inFluid || climbable || passenger || flying || fallFlying;
    }

    /**
     * 是否允许在空中触发二段跳。刻意**不**排除 {@code mayFly}：在注入点，
     * 创造模式 7 tick 双击飞行切换与鞘翅展开都已先行执行并置 {@code abilities.flying}
     * / {@code isFallFlying()}，用这两个实际状态就能完整保留原版行为；反过来排除
     * {@code mayFly} 会让创造模式玩家永远无法测试/使用二段跳。
     */
    public static boolean eligible(boolean onGround, boolean flying, boolean fallFlying,
                                    boolean passenger, boolean spectator, boolean inFluid,
                                    boolean climbable, boolean levitation, boolean dead) {
        return !onGround && !flying && !fallFlying && !passenger && !spectator
                && !inFluid && !climbable && !levitation && !dead;
    }

    /**
     * 本 tick 是否存在「跳跃按下沿」。{@code autoJumpTime > 0} 时拒绝，避免原版
     * AutoJump 伪造的 {@code input.jumping = true} 被当成玩家的空中按键。
     */
    public static boolean pressedRisingEdge(boolean currentJumping, boolean prevJumping, int autoJumpTime) {
        return currentJumping && !prevJumping && autoJumpTime <= 0;
    }

    private MidAirJumpRules() {
    }
}
