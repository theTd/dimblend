package dimblend.experience.mixin.compat.fluid;

import dimblend.experience.compat.fluid.PowderSnowBucketDrainRule;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * R13 细雪桶排空后返还空桶。
 *
 * <p>上游字节码基线（运行 jar {@code fluid-2.0.1.jar}，javap 核实）：目标为嵌套类
 * {@code com.adonis.fluid.fluid.powdersnow.PowderSnowBucketHandler$PowderSnowBucketFluidHandler}，
 * 字段 {@code private final ItemStack container}（构造器 {@code copy()} + {@code setCount(1)}），
 * {@code drain(ILFluidAction;)LFluidStack;} 依次判 container 是否 {@code Items.POWDER_SNOW_BUCKET}、
 * {@code maxDrain < 1000} 则返回 EMPTY，否则 {@code new FluidStack(POWDER_SNOW, 1000)} ——
 * <b>全程只读 container，从不写回</b>；{@code getContainer()} 返回 {@code container.copy()}。</p>
 *
 * <p>调用方都是 {@code drain → getContainer → shrink(1)}：Create 的
 * {@code GenericItemEmptying.emptyItem}（倒进分液池/储罐）与 NeoForge 的
 * {@code FluidUtil.tryEmptyContainer}。于是流体入罐、手里仍是满桶；再经本包 craft 的
 * {@code water_from_powder_snow_mixing}（细雪 1000mB + 烈焰粉 → 水 1000mB，催化剂返还）
 * 就是无限水。细雪桶没有 craftRemainder，正常使用（放细雪、发射器）是整桶消耗的，
 * 只有能力路径漏掉了这一步。</p>
 *
 * <p>注入点取 {@code drain(int, FluidAction)} 的 RETURN：重载
 * {@code drain(FluidStack, FluidAction)} 委托给它，故一处注入覆盖两条重载。
 * 判据抽到普通包 {@link PowderSnowBucketDrainRule}（mixin 包内非注册类一旦被
 * merge 后的目标类引用即 IllegalClassLoadError，见根 AGENTS.md「Mixin 纪律」）。
 * 只改 container，不改返回值、不改配方数据，其它流体容器不受影响。</p>
 *
 * <p><b>静默失效防护（2026-10-31 实测修正）</b>：Mixin 0.8.5 的
 * {@code MixinInfo.handleTargetError} 只在 {@code MixinEnvironment.Option.DEBUG_TARGETS}
 * 开启时抛 {@code InvalidMixinException}，本仓没开该选项，所以<b>上游改类名不会让游戏崩</b>，
 * 只会记一条 WARN 后跳过 —— 靠 target 解析失败"显式报错"这条路是不成立的。
 * 同理，{@code @Shadow} 找不到字段在某些情形下也只是丢警告。因此真正的防护是
 * {@code PowderSnowBucketDrainGameTests}：它按能力接口实测"排空后是否变空桶"，
 * 未织入即变红（已做反向对照验证：把本 mixin 从 mixins.json 摘掉后该用例报
 * "got 1 minecraft:powder_snow_bucket" 并让 GameTest 失败）。
 * {@code require = 3, allow = 3} 只钉死方法内 3 个 {@code areturn} 的匹配数，
 * 防的是方法体被改写，不防类改名。模组整体缺席由 {@code DimBlendMixinPlugin} 的
 * {@code isLoaded("fluid")} 守卫跳过（那是"没装"而不是"版本变了"）。</p>
 */
@Mixin(targets = "com.adonis.fluid.fluid.powdersnow.PowderSnowBucketHandler$PowderSnowBucketFluidHandler", remap = false)
public abstract class PowderSnowBucketDrainMixin {

    /** 上游为 private final（构造器唯一赋值）；写回需 @Mutable，@Shadow 只声明不加 @Final。 */
    @Mutable
    @Shadow
    private ItemStack container;

    @Inject(method = "drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;",
            at = @At("RETURN"), require = 3, allow = 3)
    private void dimblend$swapToEmptyBucket(int maxDrain, FluidAction action, CallbackInfoReturnable<FluidStack> cir) {
        FluidStack drained = cir.getReturnValue();
        int amount = drained == null ? 0 : drained.getAmount();
        if (!PowderSnowBucketDrainRule.shouldSwapToEmptyContainer(
                action != null && action.execute(), amount, FluidType.BUCKET_VOLUME)) {
            return;
        }
        this.container = new ItemStack(Items.BUCKET);
    }
}