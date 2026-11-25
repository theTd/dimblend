package dimblend.experience.mixin.compat.fluid;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.simibubi.create.foundation.item.ItemDescription;

import net.createmod.catnip.lang.FontHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 铜水槽去掉「按住 Shift 查看概要」提示（Create 的 {@code ItemDescription} 汇总器）。
 *
 * <p>铜水槽本身没有 Create 风格的概要页，{@code ItemDescription.create} 会把
 * {@code create.generic.hold_to_summarize} 塞进 tooltip；置空返回值即整段消失，
 * 而方块被注视时的护目镜提示（{@code CopperSinkDisplayMixin}）完全不受影响。</p>
 *
 * <p>只按注册名 {@code fluid:copper_sink} 命中，其它物品照原样返回（{@code null} 是
 * Create 自己也会返回的合法值，调用方带判空）。</p>
 */
@Mixin(ItemDescription.class)
public abstract class CopperSinkTooltipSummaryMixin {

    private static final ResourceLocation DIMBLEND$COPPER_SINK =
            ResourceLocation.fromNamespaceAndPath("fluid", "copper_sink");

    @ModifyReturnValue(method = "create(Lnet/minecraft/world/item/Item;Lnet/createmod/catnip/lang/FontHelper$Palette;)Lcom/simibubi/create/foundation/item/ItemDescription;",
            at = @At("RETURN"), require = 1, allow = 1)
    private static ItemDescription dimblend$dropShiftSummary(ItemDescription original, Item item, FontHelper.Palette palette) {
        if (item != null && DIMBLEND$COPPER_SINK.equals(BuiltInRegistries.ITEM.getKey(item))) {
            return null;
        }
        return original;
    }
}
