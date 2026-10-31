package dimblend.experience.compat.fluid;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * R13 细雪桶排空后应返还空桶：判定「这次 drain 是否已经把整桶内容交出去」。
 *
 * <p>上游 fluid 的 {@code PowderSnowBucketHandler$PowderSnowBucketFluidHandler}
 * 只读 container、从不写回，{@code drain(1000, EXECUTE)} 返回 1000mB 而 container
 * 仍是满的细雪桶；Create 的 {@code GenericItemEmptying.emptyItem} 与 NeoForge 的
 * {@code FluidUtil.tryEmptyContainer} 都是 {@code drain → getContainer → shrink(1)}
 * 的顺序，于是流体进了储罐、手里仍留满桶（再经 craft 的
 * {@code water_from_powder_snow_mixing} 就是无限水）。</p>
 *
 * <p>纯函数只判两件事：必须是真的 EXECUTE（SIMULATE 不能改容器），以及交出的量
 * 已满一整桶（{@code maxDrain < BUCKET_VOLUME} 时上游返回 EMPTY，未交出内容）。</p>
 */
class PowderSnowBucketDrainRuleTest {

    /** 1.21.1 的 FluidType.BUCKET_VOLUME，与测试里写死的期望值互相印证。 */
    private static final int BUCKET_VOLUME = 1000;

    @Test
    void executingAFullDrainSwapsTheContainer() {
        assertTrue(PowderSnowBucketDrainRule.shouldSwapToEmptyContainer(true, 1000, BUCKET_VOLUME),
                "EXECUTE 交出整桶内容后必须改写 container，否则排空不消耗内容");
    }

    @Test
    void simulateNeverSwapsTheContainer() {
        assertFalse(PowderSnowBucketDrainRule.shouldSwapToEmptyContainer(false, 1000, BUCKET_VOLUME),
                "SIMULATE 只是询问，不能改容器（Create 的 simulate 通道与 FluidUtil 的 doDrain=false 都走这里）");
    }

    @Test
    void aPartialDrainSwapsNothing() {
        assertFalse(PowderSnowBucketDrainRule.shouldSwapToEmptyContainer(true, 999, BUCKET_VOLUME),
                "不足一整桶时上游 drain 返回 EMPTY，不能动 container");
        assertFalse(PowderSnowBucketDrainRule.shouldSwapToEmptyContainer(true, 0, BUCKET_VOLUME),
                "空返回（容器不是细雪桶）不能动 container");
    }

    @Test
    void anOverFullDrainStillSwaps() {
        assertTrue(PowderSnowBucketDrainRule.shouldSwapToEmptyContainer(true, 4000, BUCKET_VOLUME),
                "maxDrain 传 Integer.MAX_VALUE 时交出量仍只有一整桶；判据不能写成 == 1000");
    }
}