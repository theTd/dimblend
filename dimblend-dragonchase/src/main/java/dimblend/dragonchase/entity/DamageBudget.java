package dimblend.dragonchase.entity;

import java.util.Arrays;

/**
 * Rolling damage budget for one dragon, shared by every part.
 *
 * <p>Decision <em>damage-pacing</em> (requirements.md §3.4): a dragon loses at most
 * {@code maxHealth * DragonChaseTuning.damageCapPercent / 100} health in any rolling 20 ticks
 * (one second). Every hit beyond the budget is discarded by the caller, which still plays the
 * hurt flash. Pure and unit-tested in {@code DamageBudgetTest}.
 *
 * <p>Server thread only; {@code tick} must be non-decreasing except across a world reload, where a
 * smaller value resets the budget.
 */
public final class DamageBudget {
    /** Rolling window length in game ticks (one second). */
    public static final int WINDOW_TICKS = 20;

    private final int[] ticks = new int[WINDOW_TICKS];
    private final float[] amounts = new float[WINDOW_TICKS];
    private int lastTick = Integer.MIN_VALUE;

    public DamageBudget() {
        Arrays.fill(this.ticks, Integer.MIN_VALUE);
    }

    /**
     * Records a hit at {@code tick} and returns the health that may actually be removed.
     *
     * @param tick game tick of the hit; a value below the previous one clears the budget
     * @param requested health the hit wanted to remove
     * @param capPerWindow health allowed in any rolling {@link #WINDOW_TICKS} ticks
     * @return accepted damage, between 0 and {@code requested}
     */
    public float accept(int tick, float requested, float capPerWindow) {
        if (requested <= 0.0F) {
            return 0.0F;
        }
        if (tick < this.lastTick) {
            Arrays.fill(this.ticks, Integer.MIN_VALUE);
        }
        this.lastTick = tick;

        float used = 0.0F;
        for (int i = 0; i < WINDOW_TICKS; i++) {
            int recorded = this.ticks[i];
            if (recorded > tick - WINDOW_TICKS && recorded <= tick) {
                used += this.amounts[i];
            }
        }

        float room = Math.max(0.0F, capPerWindow - used);
        float applied = Math.min(requested, room);

        int slot = Math.floorMod(tick, WINDOW_TICKS);
        if (this.ticks[slot] == tick) {
            this.amounts[slot] += applied;
        } else {
            this.ticks[slot] = tick;
            this.amounts[slot] = applied;
        }
        return applied;
    }

    public void reset() {
        Arrays.fill(this.ticks, Integer.MIN_VALUE);
        this.lastTick = Integer.MIN_VALUE;
    }
}
