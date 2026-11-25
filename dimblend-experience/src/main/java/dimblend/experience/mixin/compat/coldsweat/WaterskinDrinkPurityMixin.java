package dimblend.experience.mixin.compat.coldsweat;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.momosoftworks.coldsweat.common.item.FilledWaterskinItem;
import dev.minhnh.yetanotherthirst.core.purity.WaterPurity;

import dimblend.experience.compat.dirtywater.DirtyWaterCompat;
import dimblend.experience.compat.yat.WaterItemPurity;
import dimblend.experience.compat.yat.WaterSourcePurityRules;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 缺口 7（喝 / 提示）：Cold Sweat 装水的水袋喝下去要按 YAT 纯度结算，并在 tooltip 里显示纯度。
 *
 * <ul>
 *   <li>{@code finishUsingItem}：喝完调 {@code WaterPurity.givePurityEffects(entity, purity)}。
 *       纯度从物品的 {@code Purity} 组件读（缺省 0 = 脏水）；因为汇聚到 int 版本，
 *       {@code DrinkByHandFilterMixin}/{@code WaterPurityDrinkFilterMixin} 之外这条也自动
 *       走滤芯过滤——但水袋不在"背包滤芯"分支里重复扣，故这里只调 int 版本一次。</li>
 *   <li>{@code appendHoverText}：追加 YAT 的纯度行（{@code purityComponent}），与水瓶同文案。</li>
 * </ul>
 */
@Pseudo
@Mixin(targets = "com.momosoftworks.coldsweat.common.item.FilledWaterskinItem", remap = false)
public abstract class WaterskinDrinkPurityMixin {

    @WrapMethod(method = "finishUsingItem", remap = false, require = 1, allow = 1)
    private ItemStack dimblend$drinkWithPurity(ItemStack stack, Level level, LivingEntity entity,
            Operation<ItemStack> original) {
        ItemStack result = original.call(stack, level, entity);
        if (DirtyWaterCompat.purityAvailable() && !level.isClientSide) {
            int purity = WaterItemPurity.readOrDefault(stack);
            if (purity <= WaterSourcePurityRules.MIN_PURITY
                    && entity instanceof net.minecraft.server.level.ServerPlayer player
                    && dimblend.experience.compat.yat.InventoryFilterDrinking.tryFilterDirtyDrink(player)) {
                purity = dimblend.experience.compat.yat.InventoryFilterDrinking.filteredPurity();
            }
            WaterPurity.givePurityEffects(entity, purity);
        }
        return result;
    }

    @Inject(method = "appendHoverText", at = @At("TAIL"), remap = false, require = 1, allow = 1)
    private void dimblend$appendPurityTooltip(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
            TooltipFlag flag, CallbackInfo ci) {
        if (!DirtyWaterCompat.purityAvailable()) {
            return;
        }
        int purity = WaterItemPurity.read(stack);
        if (purity < 0) {
            purity = WaterSourcePurityRules.INFINITE_SOURCE_PURITY;
        }
        tooltip.add(WaterPurity.purityComponent(purity));
    }
}
