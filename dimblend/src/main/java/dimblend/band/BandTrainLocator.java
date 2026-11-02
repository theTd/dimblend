package dimblend.band;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;

/**
 * 服务端收集区域进度条白线的候选 sable（需求 #6 #7）。
 *
 * <p>为什么必须在服务端：需求 #7 的两个条件里，force-load 票据只在服务端可见
 * （{@code SubLevelAccess} 只有 pose/bbox/uuid/name，开始跟踪的同步包也不带票据）；而需求 #6
 * 要解决的“白线消失”根因是 sable 的 320 格网络跟踪上限——客户端连 sublevel 都已被删掉，
 * 无论怎么过滤都算不出来。所以候选在这里收集，由 {@link BandTrainSync} 下发。</p>
 *
 * <p>收集口径：本维度 {@link ServerSubLevelContainer#collectForceLoadedSubLevels()}——它已经
 * 保证 force-load 票据（含依赖链）——并把世界包围盒质心的 x/z 交给
 * {@link BandTrainMath.Candidate}。|z| ≤ 7 与“与玩家水平距离 ≤ 320”两条筛选在纯逻辑里按
 * 每个玩家分别做（320 是玩家相对的，不能在这里一次算死）。质心与改动前白线用的 x 同源同
 * 坐标系；不是 {@code logicalPose().position()} 的 plot 原点平移。</p>
 *
 * <p>sable 是可选依赖（compileOnly，mods.toml 里 optional）：所有 sable 类型都收在
 * {@link SableScan} 里，外层类不引用任何 sable 类型，因此未安装 sable 也能安全加载；
 * 嵌套类只在 {@link ModList#isLoaded(String)} 守卫通过后才被解析（JVM 惰性解析）。</p>
 */
public final class BandTrainLocator {
    private BandTrainLocator() {
    }

    /** 本维度所有有 force-load 票据的 sable 候选；未装 sable / 没有容器 → 空表。 */
    public static List<BandTrainMath.Candidate> candidates(ServerLevel level) {
        if (!ModList.get().isLoaded("sable")) {
            return List.of();
        }
        return SableScan.collect(level);
    }

    /** sable 专用层：只有装了 sable 才会被加载。 */
    private static final class SableScan {
        private SableScan() {
        }

        static List<BandTrainMath.Candidate> collect(ServerLevel level) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return List.of();
            }
            List<BandTrainMath.Candidate> candidates = new ArrayList<>();
            Vector3d center = new Vector3d();
            for (var sub : container.collectForceLoadedSubLevels()) {
                sub.boundingBox().center(center);
                candidates.add(new BandTrainMath.Candidate(center.x, center.z, true));
            }
            return candidates;
        }
    }
}
