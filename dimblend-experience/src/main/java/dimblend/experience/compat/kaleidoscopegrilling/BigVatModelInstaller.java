package dimblend.experience.compat.kaleidoscopegrilling;

import java.util.Map;

import dimblend.experience.DimBlend;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/**
 * 森罗大缸在 Sable 子层级上的显示修复：烘焙完成时把
 * {@code kaleidoscope_grilling:big_vat} 的全部方块状态变体换成 {@link BigVatCutoutModel}
 * （物品模型 {@code inventory} 变体不动）。
 *
 * <p>做法与产蛋器 {@code EggCollectorModelInstaller} 完全一致，理由也一样：
 * 在烘焙结果上换模型，比在客户端初始化阶段抢调 {@code ItemBlockRenderTypes.setRenderLayer}
 * 更可靠——后者取决于两个 mod 的初始化先后，而这个事件在所有渲染层注册之后才发生，
 * 且天然不动物品栏里的模型。</p>
 *
 * <p>kaleidoscope_grilling 缺席时不做任何事；只按注册名匹配，不引用该 mod 的类，
 * 所以没有编译期依赖，卸载该 mod 也能正常启动。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BigVatModelInstaller {
    private static final String MODID = "kaleidoscope_grilling";
    private static final ResourceLocation BIG_VAT_BLOCK = ResourceLocation.fromNamespaceAndPath(
            MODID, "big_vat");

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!ModList.get().isLoaded(MODID)) {
            return;
        }
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            ModelResourceLocation key = entry.getKey();
            if (BIG_VAT_BLOCK.equals(key.id())
                    && !ModelResourceLocation.INVENTORY_VARIANT.equals(key.variant())) {
                entry.setValue(new BigVatCutoutModel(entry.getValue()));
            }
        }
    }

    private BigVatModelInstaller() {
    }
}