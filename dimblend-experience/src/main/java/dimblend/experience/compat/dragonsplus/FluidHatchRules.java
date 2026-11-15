package dimblend.experience.compat.dragonsplus;

/**
 * 流体舱口（{@code create_dragons_plus:fluid_hatch}）单向交互规则。
 *
 * <p>决策开关 {@code fluid-hatch-direction} 已于 {@code 2026-11-15}（batch {@code dimblend-batch-1115}）锁定为
 * {@link Mode#DEPOSIT_ONLY}：只能放入液体、不能取出。{@link Mode#EXTRACT_ONLY} 仅作为对照保留，运行期不再启用。</p>
 */
public final class FluidHatchRules {

    public enum Mode {
        /** 只能取出液体、不能放入（已弃用，仅作对照）。 */
        EXTRACT_ONLY,
        /** 只能放入液体、不能取出（{@code 2026-11-15} 锁定）。 */
        DEPOSIT_ONLY
    }

    public static final Mode ACTIVE_MODE = Mode.DEPOSIT_ONLY;

    public static boolean allowsDeposit() {
        return allowsDeposit(ACTIVE_MODE);
    }

    public static boolean allowsExtract() {
        return allowsExtract(ACTIVE_MODE);
    }

    public static boolean allowsDeposit(Mode mode) {
        return mode == Mode.DEPOSIT_ONLY;
    }

    public static boolean allowsExtract(Mode mode) {
        return mode == Mode.EXTRACT_ONLY;
    }

    private FluidHatchRules() {
    }
}
