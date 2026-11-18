package dimblend.experience.compat.dirtywater;

import net.neoforged.fml.ModList;

/**
 * 脏水链路的运行期在场性检查。
 *
 * <p>脏水判定依赖 YAT 的 {@code WaterPurity}/{@code FluidPurityHelper}，但部分宿主 mixin
 * （酒桶、铜龙头）的插件门控只看各自模组是否在场，YAT 不一定在。缺 YAT 时纯度概念不存在，
 * 所有入口都必须先短路，否则 {@link DirtyWaterPurity} 的方法体会去解析缺席的类并
 * {@code NoClassDefFoundError}。</p>
 *
 * <p>每次调用都查一次 {@code ModList}：类初始化期（mixin 应用时）{@code ModList} 可能还没就绪，
 * 把结果缓存进 static 字段会把"早期未就绪"永久钉死。</p>
 */
public final class DirtyWaterCompat {

    /** YAT 是否在场（不在场时脏水拒收整体不生效）。 */
    public static boolean purityAvailable() {
        return ModList.get() != null && ModList.get().isLoaded("yet_another_thirst");
    }

    private DirtyWaterCompat() {
    }
}
