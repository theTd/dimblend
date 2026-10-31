package dimblend.experience.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * R13 细雪桶排空后必须返还空桶（{@code PowderSnowBucketDrainMixin} 的运行时织入检查）。
 *
 * <p>这条用例存在的理由有两条，都来自 2026-10-31 的实测：</p>
 * <ol>
 *   <li><b>mixin 未织入时单测照样全绿。</b>仓库 AGENTS.md 明写"单测不应用 mixin，
 *       不触发目标路径就不加载"。本改动全部价值在织入上，单测只覆盖判定纯函数。</li>
 *   <li><b>目标类按需加载，且 target 缺失只记 WARN。</b>实测 {@code -PwithFluid} 首次运行时，
 *       {@code PowderSnowBucketHandler$PowderSnowBucketFluidHandler} 从未被加载，
 *       日志只有一行 {@code @Mixin target ... is public ... should be specified in value}、
 *       没有任何 {@code Mixing ...} 行 —— 织入压根没发生，只因没人查询过细雪桶的流体能力。
 *       而 {@code MixinInfo.handleTargetError} 在 {@code DEBUG_TARGETS} 未开启时只记日志、
 *       不抛异常（本仓未开该选项），所以上游换类名/改签名不会让游戏崩，只会静默失效。
 *       本用例直接调用能力接口，是唯一能把"没织上"变红的自动化手段。</li>
 * </ol>
 *
 * <p>本类<b>只引用 NeoForge 标准 API</b>（{@code Capabilities.FluidHandler.ITEM} 与
 * {@code Items.POWDER_SNOW_BUCKET}），不引用任何 {@code com.adonis.fluid} 类，所以可以
 * {@code @GameTestHolder} 无条件注册而不必改 {@code DimBlend}；Fluid 缺席时用例在开头
 * 直接判定通过，不会误报。需要 Fluid 进 dev 运行：{@code -PwithFluid}。</p>
 */
@GameTestHolder("dimblend_experience")
@PrefixGameTestTemplate(false)
public final class PowderSnowBucketDrainGameTests {

    private static final String FLUID_MOD_ID = "fluid";

    private PowderSnowBucketDrainGameTests() {
    }

    /** fluid 缺席时没有物品流体能力，本用例无对象可验，直接通过（不是"验证过了"）。 */
    private static boolean skipWithoutFluid(GameTestHelper helper) {
        if (ModList.get().isLoaded(FLUID_MOD_ID)) {
            return false;
        }
        helper.succeed();
        return true;
    }

    /** EXECUTE 排空一整桶后，能力报告的容器必须是空桶。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void emptiedPowderSnowBucketReturnsAnEmptyBucket(GameTestHelper helper) {
        if (skipWithoutFluid(helper)) {
            return;
        }
        ItemStack bucket = new ItemStack(Items.POWDER_SNOW_BUCKET);
        IFluidHandlerItem handler = bucket.getCapability(Capabilities.FluidHandler.ITEM);
        helper.assertTrue(handler != null, "precondition: powder snow bucket must expose a fluid handler");

        FluidStack drained = handler.drain(1000, FluidAction.EXECUTE);
        helper.assertTrue(!drained.isEmpty() && drained.getAmount() == 1000,
                "precondition: a full drain must yield 1000mB, got " + drained);

        ItemStack result = handler.getContainer();
        helper.assertTrue(result.is(Items.BUCKET),
                "R13: emptying a powder snow bucket must leave an empty bucket, got " + result);
        helper.succeed();
    }

    /** SIMULATE 只询问，不许改容器 —— 否则 Create 的模拟通道与 FluidUtil 的 doDrain=false 会白送空桶。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void simulatingADrainDoesNotConsumeTheBucket(GameTestHelper helper) {
        if (skipWithoutFluid(helper)) {
            return;
        }
        ItemStack bucket = new ItemStack(Items.POWDER_SNOW_BUCKET);
        IFluidHandlerItem handler = bucket.getCapability(Capabilities.FluidHandler.ITEM);
        helper.assertTrue(handler != null, "precondition: powder snow bucket must expose a fluid handler");

        handler.drain(1000, FluidAction.SIMULATE);
        ItemStack result = handler.getContainer();
        helper.assertTrue(result.is(Items.POWDER_SNOW_BUCKET),
                "SIMULATE must not consume the bucket, got " + result);
        helper.succeed();
    }

    /** 上游未交出内容（不足一整桶）时不能消耗容器。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void drainingLessThanABucketKeepsTheContainer(GameTestHelper helper) {
        if (skipWithoutFluid(helper)) {
            return;
        }
        ItemStack bucket = new ItemStack(Items.POWDER_SNOW_BUCKET);
        IFluidHandlerItem handler = bucket.getCapability(Capabilities.FluidHandler.ITEM);
        helper.assertTrue(handler != null, "precondition: powder snow bucket must expose a fluid handler");

        FluidStack drained = handler.drain(999, FluidAction.EXECUTE);
        helper.assertTrue(drained.isEmpty(), "precondition: less than a bucket must yield nothing, got " + drained);
        helper.assertTrue(handler.getContainer().is(Items.POWDER_SNOW_BUCKET),
                "a non-draining call must keep the powder snow bucket, got " + handler.getContainer());
        helper.succeed();
    }

    /** 反向自检：确认目标类真的被加载过——否则上面三条是"没验"而不是"通过"。 */
    @GameTest(template = "item_drain_refill", templateNamespace = "dimblend_experience", timeoutTicks = 40)
    public static void mixinTargetClassIsLoadable(GameTestHelper helper) {
        if (skipWithoutFluid(helper)) {
            return;
        }
        boolean present = true;
        try {
            Class.forName("com.adonis.fluid.fluid.powdersnow.PowderSnowBucketHandler$PowderSnowBucketFluidHandler",
                    false, PowderSnowBucketDrainGameTests.class.getClassLoader());
        } catch (Throwable t) {
            present = false;
        }
        helper.assertTrue(present,
                "R13 mixin target class is gone; PowderSnowBucketDrainMixin would silently do nothing");
        helper.succeed();
    }
}