package dimblend.experience.mixin.compat.create;

import com.simibubi.create.content.redstone.displayLink.DisplayLinkBlockItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 取消显示连接器（{@code create:display_link}）放置绑定时的距离限制。
 *
 * <p>返回 {@link Integer#MAX_VALUE} 而非 {@code -1}：保留 {@code ClickToLinkBlockItem#useOn}
 * 中同维度校验 {@code selectedDim.equals(placedDim)}，同时让原版 {@code BlockPos#closerThan}
 * 与 Sable 的 {@code ClickToLinkBlockItemMixin}（均转成 IEEE-754 {@code double} 后平方比较）
 * 在全地图与任意 Sable 子层级距离下恒放行。</p>
 */
@Mixin(DisplayLinkBlockItem.class)
public abstract class DisplayLinkUnlimitedItemMixin {

    @Inject(method = "getMaxDistanceFromSelection()I", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$unlimitedSelectionRange(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(Integer.MAX_VALUE);
    }
}
