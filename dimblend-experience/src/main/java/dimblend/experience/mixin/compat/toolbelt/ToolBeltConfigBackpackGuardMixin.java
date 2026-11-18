package dimblend.experience.mixin.compat.toolbelt;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dimblend.experience.compat.toolbelt.ToolBeltBackpackGuard;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * 拦截 ToolBelt 的唯一物品白/黑名单门 {@code ConfigData#isItemStackAllowed(ItemStack)}：
 * 背包一律返回 false。
 *
 * <p>该方法是 {@code SwapItems#swapItem}、{@code BeltSlot#mayPlace} 以及客户端
 * {@code ClientEvents#handleKeys} 打开径向菜单前的共用判据，因此一处否定即可同时挡住：</p>
 * <ul>
 *   <li>快捷切换把背包换进腰带（服务端 + 单个腰带的 slot 校验）；</li>
 *   <li>手持背包（主手）按 R 打开径向菜单。</li>
 * </ul>
 *
 * <p>描述符已用 {@code javap -s} 从 {@code ToolBelt-1.21.1-2.2.9.jar} 实读为
 * {@code (Lnet/minecraft/world/item/ItemStack;)Z}；方法只依赖原版 {@link ItemStack}，
 * 因此无需引用 ToolBelt 的任何类型。</p>
 */
@Pseudo
@Mixin(targets = "dev.gigaherz.toolbelt.ConfigData", remap = false)
public abstract class ToolBeltConfigBackpackGuardMixin {

    @WrapMethod(method = "isItemStackAllowed(Lnet/minecraft/world/item/ItemStack;)Z",
            require = 1, allow = 1)
    private static boolean dimblend$rejectBackpacks(ItemStack stack, Operation<Boolean> original) {
        if (ToolBeltBackpackGuard.isBackpackItem(stack)) {
            return false;
        }
        return original.call(stack);
    }
}
