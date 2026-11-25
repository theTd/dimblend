package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.simibubi.create.content.fluids.OpenEndedPipe;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 缺口 2（真缺口）：Create 开口管直接舀世界水源/含水方块时补纯度。
 *
 * <p>反例（{@code manifest-s5.md} 的旧结论）：{@code VanillaFluidTargetsPurityMixin} 的注释说
 * {@code removeFluidFromSpace} 只经 {@code drainBlock} 拿世界流体。<b>字节码否定</b>
 * （{@code create-1.21.1-6.0.10}，{@code javap -p -c}）：</p>
 * <ol>
 *   <li>offset 66 {@code VanillaFluidTargets.drainBlock} → 非空就 offset 123 直接 return
 *       （只有蜜/岩浆锅/水锅走这里）；</li>
 *   <li>offset 156 {@code new FluidStack(fluidState.getType(), 1000)} → 普通水源块与
 *       {@code WATERLOGGED} 方块走这条，<b>裸栈</b>，{@code drainBlock} 根本不参与。</li>
 * </ol>
 *
 * <p>所以在 {@code NEW FluidStack} 这一次构造上打标即可（单点，{@code require/allow = 1}）。
 * 位置选得很关键：该构造发生在 offset 156，<b>早于</b> offset 205 之后的拆方块动作，因此
 * 这里读到的 {@code getBlockPurity} 是"被取走的那块水"的纯度（execute 分支亦然）。</p>
 *
 * <p><b>与 YAT 自带 mixin 的关系</b>：{@code YetAnotherThirst} 1.5.2 自带
 * {@code dev.minhnh.yetanotherthirst.mixin.create.MixinOpenEndedPipe}，它在同一方法的
 * {@code RETURN} 上补纯度。两处都判 {@code FluidPurityHelper.hasPurity}，所以谁先跑都只打一次；
 * 而 YAT 那侧是<b>取完水再读方块</b>（offset 205 起把水源改成流动水/拆掉 {@code WATERLOGGED}），
 * {@code FluidState#isSource()} 变假，{@code getBlockPurity} 会加
 * {@code RUNNING_WATER_PURIFICATION_AMOUNT}（"流动水变干净"），于是 execute 分支会把脏水算成
 * 干净一档。本处取值在构造点、早于任何世界改动，覆盖到的是正确值；因为 YAT 只在"无标签"时打标，
 * 这里先打上标签就会让 YAT 那侧自动跳过，不受两侧注入顺序影响。</p>
 *
 * <p>只对水生效：岩浆/蜜/巧克力走 {@code drainBlock} 或不满足 {@code FluidTags.WATER}，一律不动。</p>
 */
@Mixin(OpenEndedPipe.class)
public abstract class OpenEndedPipePurityMixin {

    @ModifyExpressionValue(method = "removeFluidFromSpace",
            at = @At(value = "NEW",
                    target = "(Lnet/minecraft/world/level/material/Fluid;I)"
                            + "Lnet/neoforged/neoforge/fluids/FluidStack;"),
            remap = false, require = 1, allow = 1)
    private FluidStack dimblend$stampScoopedWorldWater(FluidStack scooped) {
        if (!DirtyWaterCompat.purityAvailable()
                || !WaterSourcePurityRules.isWater(scooped.isEmpty(), scooped.is(Fluids.WATER))) {
            return scooped;
        }
        OpenEndedPipe self = (OpenEndedPipe) (Object) this;
        Level level = self.getWorld();
        BlockPos pos = self.getOutputPos();
        if (level == null || pos == null || level.isClientSide) {
            // 客户端只做视觉/缓存，纯度以服务端为准（与 VanillaFluidTargetsPurityMixin 同口径）
            return scooped;
        }
        if (!level.getFluidState(pos).is(FluidTags.WATER)) {
            // 理论上走不到（NEW 只在流体态是水/含水时执行），留一道保险：非水不打标
            return scooped;
        }
        DirtyWaterPurity.stampFluidPurity(scooped,
                WaterSourcePurityRules.clampPurity(WaterPurity.getBlockPurity(level, pos)));
        return scooped;
    }
}
