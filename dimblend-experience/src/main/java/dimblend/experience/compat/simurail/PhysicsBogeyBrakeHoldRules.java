package dimblend.experience.compat.simurail;

/**
 * 转向架"刹车保持"口径（2026-11-25 LOCKED #2 {@code brake_hold_below_5s}，1 m/s 版）。
 *
 * <p>用户口径：制动强度大于 0、速度低于 <b>1 m/s</b>、且该载具未被豁免时，
 * 载具被钉在原地（速度归零），而不是在 0 附近反复推挤；一旦高于阈值就交回
 * Simurail 自己的刹车型物理。阈值/迟滞/豁免全部收在这里，便于单测钉死。
 * </p>
 *
 * <p>为什么是"钉住"而不是"加大刹车力"：低速时 {@code Math.clamp(speed,-1,1)} 的
 * 刹車力与速度同阶，物理步里会被牵引力轻易顶开，形成 0~0.9 m/s 的锯齿。
 * 每个物理步把线速度/角速度清零才是"停在原地"的确定做法。</p>
 */
public final class PhysicsBogeyBrakeHoldRules {

    /** 低于此速度（m/s，开区间）才进入刹车保持。 */
    public static final double HOLD_BELOW_SPEED = 1.0D;

    /** 视为"已在原地"的容差，用于判断保持态是否已经达成（避免抖动）。 */
    public static final double AT_REST_EPSILON = 0.08D;

    /** 该转向架这一步是否应当把载具钉住。 */
    public static boolean shouldHold(double brakeStrength, boolean exempt, double speed) {
        return brakeStrength > 0.0D && !exempt && Math.abs(speed) < HOLD_BELOW_SPEED;
    }

    /** 保持态下的纵向速度钳制：仅用于诊断读数，物理上直接清零。 */
    public static double clampLongitudinal(double speed) {
        if (speed > AT_REST_EPSILON) return 0.0D;
        if (speed < -AT_REST_EPSILON) return 0.0D;
        return speed;
    }

    private PhysicsBogeyBrakeHoldRules() {
    }
}
