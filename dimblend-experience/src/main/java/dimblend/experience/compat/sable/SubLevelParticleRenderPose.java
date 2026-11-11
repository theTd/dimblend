package dimblend.experience.compat.sable;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.joml.Vector3d;

/**
 * 子层级粒子与子层级网格的位姿对齐。
 *
 * <h2>已证实的偏差（源码取证）</h2>
 * <p>Sable 在 {@code ParticleEngine#add} 的 TAIL 调 {@code ParticleExtension#sable$initialKickOut}
 * （{@code sable .../mixin/particle/ParticleEngineMixin.java:36-39}，装好的
 * {@code sable-neoforge-1.21.1-2.0.5_cui-modded.jar} javap 实读 {@code @Inject(method="add", at=TAIL)}），
 * 它把粒子从 plot 坐标换算成世界坐标时用的是 {@code subLevel.logicalPose()}
 * （{@code ParticleMixin.java:142}，javap 常量池实读 {@code SubLevel.logicalPose:()LPose3d;}）。</p>
 * <p>而子层级<b>网格</b>绘制用的是 {@code subLevel.renderPose()}，即
 * {@code lastPose.lerp(logicalPose, partialTick)}（{@code VanillaChunkedSubLevelRenderData.java:287}、
 * {@code ClientSubLevel.java:313-329}）。因此粒子永远停在「本 tick 末」的位姿上，而网格在
 * {@code lastPose} 与 {@code logicalPose} 之间插值：移动/转向中的载具上，粒子相对磨盘被
 * 前移 {@code (1-partialTick)} 个 tick 的位移（每帧变化）。静止载具 {@code lastPose == logicalPose}，
 * 位移为 0；地面方块根本没有子层级位姿，所以地面正常——这与实机口径（地面正常、装配车上偏）
 * 一致。</p>
 *
 * <h2>本类的做法</h2>
 * <p>把粒子当前世界坐标逆变换回 plot 锚点，再用 {@code renderPose(partialTick)} 正变换，差值就是
 * 绘制这一帧应有的临时平移。只用于渲染，不改 {@code sable$localTrackingAnchor} 与包围盒。</p>
 */
public final class SubLevelParticleRenderPose {

    /** 小于 1 mm 的位移不处理，避免静止载具上每帧白算一遍。 */
    private static final double MIN_SHIFT_SQUARED = 1.0E-6;

    private static final Vector3d PLOT_POINT = new Vector3d();
    private static final Vector3d SHIFTED = new Vector3d();
    private static final Vector3d CURRENT = new Vector3d();

    /**
     * 计算这颗粒子本帧应被临时平移多少。
     *
     * @param particle    粒子的渲染坐标读写口
     * @param subLevel    粒子追踪的子层级（客户端）
     * @param partialTick 与 {@code ParticleEngine#render} 同一个部分刻
     * @param out         结果（世界系位移）
     * @return 需要平移时为 true；位姿一致（静止）时为 false
     */
    public static boolean shiftFor(RenderPoseShiftable particle, ClientSubLevel subLevel,
            float partialTick, Vector3d out) {
        final Pose3dc tickPose = subLevel.logicalPose();
        final Pose3dc renderPose = subLevel.renderPose(partialTick);
        particle.dimblend$readRenderPos(CURRENT);
        tickPose.transformPositionInverse(PLOT_POINT.set(CURRENT), SHIFTED);
        renderPose.transformPosition(SHIFTED, out);
        out.sub(CURRENT);
        return out.lengthSquared() > MIN_SHIFT_SQUARED;
    }

    private SubLevelParticleRenderPose() {
    }
}
