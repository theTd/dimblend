package dimblend.experience.mixin.compat.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.util.FluidUtils;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.dirtywater.DirtyWaterPurity;
import dimblend.experience.compat.tavern.BarrelWaterPurity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 从酒桶取水时把 {@code Purity: 2} 打到"实际装到手里的那个容器"上。
 *
 * <p>为什么不在 {@code removeFluid} 里改：取水的实现是
 * {@code FluidUtils.fillItem(user, stack, tank, 4000)}，它先给容器能力 {@code fill}，
 * 再把 {@code stackFluid.getContainer()} 通过 {@code ItemUtils.getItemToLivingEntity}
 * 交给玩家。铜壶/水袋这类自带组件的容器能保住 Purity，但<b>原版水桶</b>走
 * {@code FluidBucketWrapper}，出来的 {@code minecraft:water_bucket} 是全新物品栈，
 * 组件全丢——这正是"桶装酒桶水显示脏水"的根因。</p>
 *
 * <p>所以在 {@code lambda$fillItem$1} 里包住 {@code getItemToLivingEntity} 这一次调用：
 * 交给玩家之前，只要它是"已装水容器"且还没有 Purity 组件，就补 {@code Purity: 2}。
 * 只补不覆盖（铜壶本来带 2 的不动）。</p>
 */
@Mixin(value = FluidUtils.class, remap = false)
public abstract class FluidUtilsPurityMixin {

    @WrapOperation(method = "lambda$fillItem$1",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/ysbbbbbb/kaleidoscopetavern/util/ItemUtils;getItemToLivingEntity(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)V"),
            remap = false, require = 1, allow = 1)
    private static void dimblend$stampBarrelWaterContainer(LivingEntity entity, ItemStack stack,
            Operation<Void> original) {
        if (DirtyWaterCompat.purityAvailable()
                && stack != null
                && DirtyWaterPurity.isWaterContainer(stack)
                && !BarrelWaterPurity.hasPurity(stack)) {
            BarrelWaterPurity.stampContainer(stack, BarrelWaterPurity.BARREL_WATER_PURITY);
        }
        original.call(entity, stack);
    }
}
