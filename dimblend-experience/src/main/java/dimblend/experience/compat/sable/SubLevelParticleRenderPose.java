package dimblend.experience.compat.sable;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.particle.ParticleExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.particle.Particle;
import org.joml.Vector3d;

/**
 * 子层级粒子与子层级网格的位姿对齐。
 *
 * <h2>两个已证实的偏差</h2>
 * <ol>
 *   <li><b>生成那一 tick（tick 0）整体前移一整 tick。</b>{@code Minecraft#tick} 里
 *       {@code ClientSubLevelContainer#tick} 先跑（{@code lastPose = P(T-1)}、
 *       {@code logicalPose = P(T)}），随后方块实体才生成粒子：{@code ParticleEngine#add} 把粒子放进
 *       {@code particlesToAdd}，Sable 的 {@code sable$initialKickOut} 用 {@code logicalPose}
 *       （{@code ParticleMixin.java:142}）把坐标换成世界坐标，于是 {@code xo = x = P(T)(p0)}。
 *       而 {@code ParticleEngine#tick} 在 tick 完 {@code particles} 之<b>后</b>才把
 *       {@code particlesToAdd} 取出来，这一 tick 该粒子没有被 {@code Particle#tick} 跑过，
 *       {@code xo} 也就没机会变成 {@code P(T-1)}。<b>两者都停在 P(T)</b>，比
 *       {@code renderPose(0.0) = P(T-1)} 整整提前一个 tick（移动载具上就是一个 tick 的位移）。</li>
 *   <li><b>tick 1+ 反向滞后（7428143 的实现错误）。</b>粒子被 {@code Particle#tick} 至少跑过一次
 *       之后，{@code xo} 落在 {@code lastPose}（{@code P(T)(p_prev)}），{@code x} 落在
 *       {@code logicalPose}（{@code P(T+1)(p_curr)}）。旧实现算的是
 *       {@code out = renderPose(pt)(logicalPose^-1(x)) - x = -(1 - pt) * V_train}，再把 {@code out}
 *       同时加到 {@code xo} 与 {@code x} 上——于是每颗 tick 1+ 的粒子都被<b>反向</b>推了一个
 *       {@code (1 - pt)} 的列车位移。</li>
 * </ol>
 *
 * <h2>统一做法（与 partialTick 无关的纯几何）</h2>
 * <p>不要对「世界坐标」做差再平移，而是把两个世界坐标各自逆变换回 plot 局部空间、<b>在局部空间
 * 插值</b>，再用 {@code renderPose(partialTick)} 正变换出来：</p>
 * <pre>
 *   localPrev = prevPose^-1(xo, yo, zo)
 *   localCurr = currPose^-1(x,  y,  z)
 *   result    = renderPose(pt)(lerp(pt, localPrev, localCurr))
 * </pre>
 * <ul>
 *   <li>tick 0：{@code prevPose = currPose = logicalPose}，{@code xo == x}，于是
 *       {@code result = renderPose(pt)(p0)}——{@code pt=0} 给 {@code P(T-1)(p0)}、{@code pt=1} 给
 *       {@code P(T)(p0)}，<b>没有那个 +1 tick 的跳变</b>。</li>
 *   <li>tick 1+：{@code prevPose = lastPose}、{@code currPose = logicalPose}，局部插值天然带上粒子
 *       自身的局部运动，<b>没有反向滞后</b>。</li>
 *   <li>转弯时局部插值再正变换，落点严格在圆弧上，不会出现「世界坐标线性插值」的弦线误差。</li>
 * </ul>
 *
 * <p>本类只做数学，不读 Minecraft 静态状态：{@link #computeRenderPosition} 可用 JOML 与
 * {@code Pose3dc} 直接单测，不需要 Bootstrap。</p>
 */
public final class SubLevelParticleRenderPose {

    /** 渲染线程专用的临时向量；本类只在渲染线程（以及单测主线程）被调用，不做并发保护。 */
    private static final Vector3d PREV_LOCAL = new Vector3d();
    private static final Vector3d CURR_LOCAL = new Vector3d();
    private static final Vector3d LERP_LOCAL = new Vector3d();
    private static final Vector3d PREVIOUS_RENDER_POS = new Vector3d();
    private static final Vector3d CURRENT_RENDER_POS = new Vector3d();

    /**
     * 计算这颗粒子本帧应被绘制的世界坐标（纯函数，可脱离 Minecraft 单测）。
     *
     * @param prevPose    {@code xo/yo/zo} 所在的位姿（tick 0 用 {@code logicalPose}，tick 1+ 用
     *                    {@code lastPose}）
     * @param currPose    {@code x/y/z} 所在的位姿（恒为 {@code logicalPose}）
     * @param renderPose  本帧网格实际使用的位姿（{@code renderPose(partialTick)}）
     * @param partialTick 部分刻
     * @param xo          上一帧绘制坐标（plot 局部量经 {@code prevPose} 换算后的世界坐标）
     * @param x           当前绘制坐标（plot 局部量经 {@code currPose} 换算后的世界坐标）
     * @param outWorldPos 结果（世界坐标）
     * @return 结果有限且可绘制时为 true；任何输入缺失/非有限（位姿 scale 为 0 等）时为 false
     */
    public static boolean computeRenderPosition(Pose3dc prevPose, Pose3dc currPose, Pose3dc renderPose,
            float partialTick, double xo, double yo, double zo, double x, double y, double z,
            Vector3d outWorldPos) {
        if (prevPose == null || currPose == null || renderPose == null || outWorldPos == null) {
            return false;
        }
        if (!Float.isFinite(partialTick)) {
            return false;
        }
        if (!Double.isFinite(xo) || !Double.isFinite(yo) || !Double.isFinite(zo)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }

        prevPose.transformPositionInverse(PREV_LOCAL.set(xo, yo, zo), PREV_LOCAL);
        currPose.transformPositionInverse(CURR_LOCAL.set(x, y, z), CURR_LOCAL);
        LERP_LOCAL.set(PREV_LOCAL).lerp(CURR_LOCAL, partialTick);
        renderPose.transformPosition(LERP_LOCAL, outWorldPos);

        return Double.isFinite(outWorldPos.x) && Double.isFinite(outWorldPos.y)
                && Double.isFinite(outWorldPos.z);
    }

    /**
     * 对一颗粒子套用 {@link #computeRenderPosition}。
     *
     * <p>只处理被 Sable 子层级追踪的粒子：{@code prevPose} 按「这一 tick 有没有被
     * {@code Particle#tick} 跑过」选取——没跑过时 {@code xo} 也在 {@code logicalPose} 里
     * （见类注释第 1 条），跑过之后 {@code xo} 已在 {@code lastPose} 里。</p>
     *
     * @param particle            目标粒子
     * @param partialTick         与 {@code ParticleEngine#render} 同一个部分刻
     * @param hasTickedInSubLevel 该粒子是否已在当前追踪子层级下被 tick 过至少一次
     * @param outWorldPos         结果（世界坐标）
     * @return 结果可用时为 true
     */
    public static boolean applyToParticle(Particle particle, float partialTick, boolean hasTickedInSubLevel,
            Vector3d outWorldPos) {
        if (particle == null || outWorldPos == null) {
            return false;
        }
        if (!(particle instanceof ParticleExtension extension)) {
            return false;
        }
        final SubLevel tracked = extension.sable$getTrackingSubLevel();
        if (!(tracked instanceof ClientSubLevel subLevel) || tracked.isRemoved()) {
            return false;
        }
        if (!(particle instanceof RenderPoseShiftable shiftable)) {
            return false;
        }

        final Pose3dc prevPose = hasTickedInSubLevel ? subLevel.lastPose() : subLevel.logicalPose();
        shiftable.dimblend$readPreviousRenderPos(PREVIOUS_RENDER_POS);
        shiftable.dimblend$readRenderPos(CURRENT_RENDER_POS);
        return computeRenderPosition(prevPose, subLevel.logicalPose(), subLevel.renderPose(partialTick),
                partialTick,
                PREVIOUS_RENDER_POS.x, PREVIOUS_RENDER_POS.y, PREVIOUS_RENDER_POS.z,
                CURRENT_RENDER_POS.x, CURRENT_RENDER_POS.y, CURRENT_RENDER_POS.z,
                outWorldPos);
    }

    private SubLevelParticleRenderPose() {
    }
}
