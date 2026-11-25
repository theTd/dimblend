package dimblend.experience.mixin.compat.yat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.yat.InventoryFilterDrinking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 容器喝水（水瓶 / 陶碗 / 水袋等）的滤芯过滤（LOCKED DECISION #8）。
 *
 * <p>{@code WaterPurity#givePurityEffects(LivingEntity, ItemStack)} 是所有"手里拿着装了水的
 * 容器喝一口"的唯一汇聚点（{@code givePurityEffects(entity, getPurity(stack))}）。这里整段
 * 包一层：{@code purity == 0} 且服务端玩家背包里有干净滤芯时，拿一份<b>带 Purity=1 的副本</b>
 * 交给原方法，真实物品栈不动（不会改写别人的水瓶）。纯度 1/2/3 一律原样透传，
 * 不消耗滤芯。</p>
 */
@Mixin(WaterPurity.class)
public abstract class WaterPurityDrinkFilterMixin {

    @WrapMethod(method = "givePurityEffects(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;)Z",
            remap = false, require = 1, allow = 1)
    private static boolean dimblend$filterContainerDrinking(LivingEntity entity, ItemStack stack,
            Operation<Boolean> original) {
        if (stack == null || stack.isEmpty() || !DirtyWaterCompat.purityAvailable()) {
            return original.call(entity, stack);
        }
        if (entity instanceof ServerPlayer player
                && InventoryFilterDrinking.needsFilter(WaterPurity.getPurity(stack))
                && InventoryFilterDrinking.tryFilterDirtyDrink(player)) {
            ItemStack filtered = stack.copy();
            WaterPurity.addPurity(filtered, InventoryFilterDrinking.filteredPurity());
            return original.call(entity, filtered);
        }
        return original.call(entity, stack);
    }
}
