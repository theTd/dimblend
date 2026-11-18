package dimblend.experience.mixin.compat.dirtywater;

import dimblend.experience.compat.dirtywater.DirtyWaterCraftingRules;
import dimblend.experience.compat.dirtywater.DirtyWaterFeedback;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 合成格里有 Purity 0 的水、结果槽却是空的：给该玩家 hotbar 提示
 * "太脏了 至少先过滤一下"。
 *
 * <p>{@code slotChangedCraftingGrid} 是工作台（{@code CraftingMenu}）与玩家 2×2
 * （{@code InventoryMenu}）共用的静态入口，一处注入覆盖两者。每个槽位变化都会走到这里，
 * 所以提示走 {@link DirtyWaterFeedback#notifyThrottled}（同一玩家 20 tick 一次）。</p>
 *
 * <p>为什么在菜单层提示而不是在 {@code matches}：配方匹配没有玩家上下文，而"被拒的配方"
 * 本身在 REI/JEI 里仍然存在（只是不匹配），菜单层是唯一知道"是谁放了这桶水"的地方。</p>
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuDirtyWaterMixin {

    @Inject(
            method = "slotChangedCraftingGrid(Lnet/minecraft/world/inventory/AbstractContainerMenu;"
                    + "Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;"
                    + "Lnet/minecraft/world/inventory/CraftingContainer;"
                    + "Lnet/minecraft/world/inventory/ResultContainer;"
                    + "Lnet/minecraft/world/item/crafting/RecipeHolder;)V",
            at = @At("TAIL"),
            require = 1,
            allow = 1
    )
    private static void dimblend$hintDirtyWater(AbstractContainerMenu menu, Level level, Player player,
            CraftingContainer craftSlots, ResultContainer resultSlots, RecipeHolder<CraftingRecipe> lastRecipe,
            CallbackInfo ci) {
        if (level.isClientSide || !resultSlots.getItem(0).isEmpty()) {
            return;
        }
        if (!DirtyWaterCraftingRules.looksLikeDirtyWaterIngredient(craftSlots)) {
            return;
        }
        DirtyWaterFeedback.notifyThrottled(player, level.getGameTime());
    }
}
