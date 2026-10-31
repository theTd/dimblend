package dimblend.experience.compat.fluid;

/**
 * R13：细雪桶被流体机器排空后应返还空桶。判定与 IO 无关，只回答
 * 「这一次 drain 是否真的把一整桶内容交出去了」。
 *
 * <p>上游 {@code PowderSnowBucketHandler$PowderSnowBucketFluidHandler} 的容器字段
 * 从不写回（drain 只读、getContainer 直接返回 copy），所以调用方
 * {@code drain → getContainer} 拿到的是满桶。本类供
 * {@code dimblend.experience.mixin.compat.fluid.PowderSnowBucketDrainMixin}
 * 判定改写时机，放在普通包而不是 mixin 包 —— mixin 包内非注册类一旦被 merge 后的
 * 目标类引用就触发 IllegalClassLoadError（见根 AGENTS.md「Mixin 纪律」）。</p>
 */
public final class PowderSnowBucketDrainRule {

    private PowderSnowBucketDrainRule() {
    }

    /**
     * @param execute      调用方是否传了 {@code FluidAction.EXECUTE}
     * @param drainedAmount 上游 drain 实际返回的流体量（mB）
     * @param bucketVolume  一整桶的容量（1.21.1 为 {@code FluidType.BUCKET_VOLUME} = 1000）
     * @return true 表示内容已交出，容器必须由满桶改写成空桶
     */
    public static boolean shouldSwapToEmptyContainer(boolean execute, int drainedAmount, int bucketVolume) {
        // SIMULATE 只询问，上游 drain 在 maxDrain < 1000 时返回 EMPTY（content 留在桶里）。
        return execute && drainedAmount >= bucketVolume;
    }
}