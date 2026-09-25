package dimblend.experience.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import dimblend.experience.nickname.ClientNicknames;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A8 显示层改名：getHoverName 覆盖物品提示名（tooltip/悬停），纯客户端、
 * 不改物品数据。无昵称命中时走原版。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackNicknameMixin {

    @ModifyReturnValue(method = "getHoverName", at = @At("RETURN"))
    private Component dimblend$applyNickname(Component original) {
        Component nickname = ClientNicknames.override((ItemStack) (Object) this);
        return nickname != null ? nickname : original;
    }
}
