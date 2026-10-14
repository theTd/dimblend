package dimblend.experience.compat.createeggproduction;

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
 * G7 产蛋器内嵌鸡在 Sable 子层级不显示：烘焙完成时把
 * {@code createeggproduction:egg_collector_block} 的全部方块状态变体换成
 * {@link EggCollectorLayerSplitModel}（物品模型 {@code inventory} 变体不动）。
 * createeggproduction 缺席时不做任何事；只按注册名匹配，不引用该 mod 的类。
 */
@EventBusSubscriber(modid = DimBlend.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class EggCollectorModelInstaller {
    private static final String MODID = "createeggproduction";
    private static final ResourceLocation EGG_COLLECTOR_BLOCK = ResourceLocation.fromNamespaceAndPath(
            MODID, "egg_collector_block");

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        if (!ModList.get().isLoaded(MODID)) {
            return;
        }
        for (Map.Entry<ModelResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            ModelResourceLocation key = entry.getKey();
            if (EGG_COLLECTOR_BLOCK.equals(key.id())
                    && !ModelResourceLocation.INVENTORY_VARIANT.equals(key.variant())) {
                entry.setValue(new EggCollectorLayerSplitModel(entry.getValue()));
            }
        }
    }

    private EggCollectorModelInstaller() {
    }
}
