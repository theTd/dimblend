package dimblend.experience.mixin.compat.sable;

import dimblend.experience.compat.sable.SablePlacementBanRules;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 创造模式装置（风车轴承 / 水车 / 大水车）禁止放在 Sable 载具上（2026-11-25）。
 *
 * <p>不依赖星辉：{@code CrystalbornCatalystPlacementMixin} 只在
 * {@code eternal_starlight + sable} 同时在场时加载，本 mixin 单独走
 * {@code create + sable} 门控，缺星辉时同样生效。</p>
 *
 * <p>只拦 {@link BlockItem#place}：创造模式的空格放置物品与真人都走这条路径，
 * 机器臂/蓝图等其它放置通道不在用户口径内（与冰放置禁令同款取舍）。</p>
 */
@Mixin(BlockItem.class)
public abstract class CreateGeneratorPlacementBanMixin {

    @Inject(method = "place", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void dimblend$banOnSable(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (SablePlacementBanRules.shouldBlockPlacement(context.getItemInHand(),
                context.getLevel(), context.getClickedPos())) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }
}
