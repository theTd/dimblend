package dimblend.experience.mixin.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.util.FluidUtils;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.tavern.BarrelWaterPurity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 从酒桶取水时把 {@code Purity: 1}（过滤）打到"实际装到手里的那个容器"上。
 *
 * <p>为什么不在 {@code removeFluid} 里改：取水的实现是
 * {@code FluidUtils.fillItem(user, stack, tank, 4000)}，它先给容器能力 {@code fill}，
 * 再把 {@code stackFluid.getContainer()} 通过 {@code ItemUtils.getItemToLivingEntity}
 * 交给玩家。铜壶/水袋这类自带组件（内部 {@code FluidStack}）的容器能保住从桶里抽到的
 * Purity 组件，但<b>原版水桶</b>走 {@code FluidBucketWrapper}，出来的
 * {@code minecraft:water_bucket} 是全新物品栈，组件全丢——这正是"桶装酒桶水不显示过滤"的根因。</p>
 *
 * <p>所以在 {@code lambda$fillItem$1} 里包住 {@code getItemToLivingEntity} 这一次调用：
 * 交给玩家之前，只要当前线程处在酒桶取水作用域（{@link BarrelWaterPurity#isTakingBarrelWater()}，
 * 由 {@code BarrelBlockEntityMixin} 精确套在 {@code removeFluid} 的 {@code fillItem} 调用上）
 * 且它确实是"已装水容器"，就<b>一律覆写</b>成 {@code Purity: 1}——
 * 不再"只补不覆盖"：容器在之前倒水时留下的 {@code 2}/{@code 3} 也必须被统一成过滤。
 * 榨汁桶 {@code PressingTubBlockEntity} 也调 {@code FluidUtils.fillItem}，但它不在该作用域内，
 * 且产物不是水容器，两道条件都天然放行。</p>
 */
@Mixin(value = FluidUtils.class, remap = false)
public abstract class FluidUtilsPurityMixin {

    @WrapOperation(method = "lambda$fillItem$1",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/ysbbbbbb/kaleidoscopetavern/util/ItemUtils;getItemToLivingEntity(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V"),
            remap = false, require = 1, allow = 1)
    private static void dimblend$stampBarrelWaterContainer(LivingEntity entity, ItemStack stack,
            Operation<Void> original) {
        if (DirtyWaterCompat.purityAvailable() && BarrelWaterPurity.isTakingBarrelWater()) {
            BarrelWaterPurity.stampContainer(stack);
        }
        original.call(entity, stack);
    }
}
