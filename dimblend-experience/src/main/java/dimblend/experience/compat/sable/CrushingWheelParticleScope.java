package dimblend.experience.compat.sable;

/**
 * 客户端粉碎轮粒子生成作用域标记（纯 Java，零三方类型依赖）。
 *
 * <p>当 Sable 子层级上的 {@code CrushingWheelControllerBlock}、{@code CrushingWheelControllerBlockEntity}
 * 或粉碎轮本体的 {@code KineticEffectHandler} 正在调用 {@code addParticle} 时进入本作用域，使刚创建的粒子：
 * <ol>
 *   <li>跳过 {@code CrushingWheelControllerBlock} 自带的 {@code 16x13x16} 内部实体碰撞盒与两侧粉碎轮完整方块碰撞，
 *       避免被 {@code ParticleMixin#resolveAABBCollision} 瞬间挤出接缝并触发 {@code > 0.5} 格追踪脱钩；</li>
 *   <li>在 {@code CritParticle.<init>} 提前调用 {@code tick()} 时保持在 plot 局部空间完成首步积分，
 *       避免在 {@code sable$initialKickOut()} 之前把 {@code ~1.6e7} 的 plot 坐标送入世界位姿差运算。</li>
 * </ol>
 */
public final class CrushingWheelParticleScope {

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    public static void enter() {
        DEPTH.set(DEPTH.get() + 1);
    }

    public static void exit() {
        int next = DEPTH.get() - 1;
        if (next <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(next);
        }
    }

    public static boolean isActive() {
        return DEPTH.get() > 0;
    }

    private CrushingWheelParticleScope() {
    }
}
