package dimblend.experience.exploration;

/**
 * 全局「按住空格才爬梯」口径（2026-11-25 LOCKED #1 {@code ladder_global}）。
 *
 * <p>原版在 {@code LivingEntity#handleRelativeFrictionAndCalculateMovement} 里把
 * {@code (horizontalCollision || jumping) && (onClimbable() || 粉雪+皮革靴)} 合成一个条件，
 * 命中就把 {@code vec3.y} 抬到 0.2——于是玩家只要贴着梯子并触墙（甚至只是站着）就会自动上爬。
 * 新口径只对 {@code Player} 生效：梯子的上爬速度<b>只在按住跳跃键时</b>给，横向碰撞不再触发上爬；
 * 粉雪+皮革靴分支、以及非玩家生物（僵尸/灾厄村民的寻路爬梯）完全保持原版。</p>
 *
 * <p>实现上不重写整段条件，而是替换 {@code onClimbable()} 这一次取值：
 * 玩家未按跳跃时把它当 false，于是 {@code ||} 左半边失效、粉雪分支照旧生效——
 * 两套"攀爬"语义天然分离，也不会动 {@code handleOnClimbable()} 的 -0.15 下滑钳制。</p>
 */
public final class LadderClimbRules {

    /** 原版上爬速度，保留常量便于对照与单测。 */
    public static final double CLIMB_UP_SPEED = 0.2D;

    /**
     * 攀爬位的实际取值。
     *
     * @param onClimbable 原版 {@code onClimbable()} 的结果
     * @param player      是否为玩家实例
     * @param jumping     是否按住跳跃键
     * @return 玩家必须按住跳跃才视为"可上爬"；非玩家一律沿用原值
     */
    public static boolean climbPositionActive(boolean onClimbable, boolean player, boolean jumping) {
        return player ? onClimbable && jumping : onClimbable;
    }

    private LadderClimbRules() {
    }
}
