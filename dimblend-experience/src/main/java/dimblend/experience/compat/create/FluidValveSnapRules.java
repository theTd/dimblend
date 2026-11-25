package dimblend.experience.compat.create;

/**
 * 流体阀门（Create {@code FluidValveBlockEntity}）指针与开关状态的纯函数口径
 * （LOCKED DECISION #12 {@code valve_snap_revert}）。
 *
 * <p>现象：阀门摇杆转停后，{@code LerpedFloat} 指针停在 0 与 1 之间时，服务端与客户端
 * 各自缓存的目标值不同步，{@code ENABLED} 也不翻转，表现为"半开半关"而且两端显示不一致。
 * 裁定口径：<b>转速为 0 且指针未到位（0 &lt; val &lt; 1）时绝不翻转 {@code ENABLED}</b>，
 * 而是把指针按 {@code ENABLED ? 1 : 0} 平滑拉回；拉到位后同步给客户端。</p>
 *
 * <p>本类只做数值判定，零 Minecraft 依赖，便于单元测试（{@code FluidValveSnapRulesTest}）。</p>
 */
public final class FluidValveSnapRules {

    /** 视为"静止"的转速阈值（浮点噪声容差）。 */
    public static final float SPEED_EPSILON = 1.0e-5F;

    /** 视为"指针已达目标"的差值阈值。 */
    public static final float SETTLED_EPSILON = 1.0e-4F;

    /** 未到位时回拉的步进速度（每 tick，线性插值）。 */
    public static final float SNAP_STEP_PER_TICK = 0.25F;

    /** 转速是否视为静止。 */
    public static boolean isStationary(float speed) {
        return Math.abs(speed) < SPEED_EPSILON;
    }

    /** 指针是否停在 0 与 1 之间（"未到位"）。 */
    public static boolean isUnfinished(float pointer) {
        return pointer > 0.0F && pointer < 1.0F;
    }

    /** 未到位时应当回拉的目标：开关开 = 1，关 = 0。 */
    public static float snapTarget(boolean enabled) {
        return enabled ? 1.0F : 0.0F;
    }

    /** 指针是否已到达目标（用于决定何时同步并停止回拉）。 */
    public static boolean reached(float pointer, float target) {
        return Math.abs(pointer - target) <= SETTLED_EPSILON;
    }

    /** 按 {@code step} 向目标推进一 tick（不改目标、不做插值以外的判断）。 */
    public static float stepToward(float pointer, float target, float step) {
        float delta = target - pointer;
        if (Math.abs(delta) <= step) {
            return target;
        }
        return pointer + Math.signum(delta) * step;
    }

    private FluidValveSnapRules() {
    }
}
