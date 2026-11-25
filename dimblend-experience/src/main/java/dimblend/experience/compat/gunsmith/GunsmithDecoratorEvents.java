package dimblend.experience.compat.gunsmith;

import dimblend.experience.DimBlend;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;

/**
 * 把 {@link GunOverDurabilityDecorator}（溢出耐久的第二根耐久条）挂到枪物品上。
 *
 * <p>taCZ 的所有枪共用同一个物品 {@code tacz:modern_kinetic_gun}（具体型号是组件里的
 * {@code gunId}），所以按物品注册一次即可覆盖全部枪型；gundb 的溢出耐久也长在这个物品上。</p>
 *
 * <p>整类自带 {@code @EventBusSubscriber(Dist.CLIENT, MOD)}，不需要在
 * {@code DimBlendClient} 里再手工挂一行（这样也避免动共享入口文件）。taCZ 缺席时直接不注册，
 * 绝不去 {@code BuiltInRegistries} 里查一个不存在的名字然后给 {@code air} 挂装饰器。</p>
 */
@EventBusSubscriber(modid = DimBlend.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class GunsmithDecoratorEvents {

    private static final ResourceLocation MODERN_KINETIC_GUN =
            ResourceLocation.fromNamespaceAndPath("tacz", "modern_kinetic_gun");

    @SubscribeEvent
    public static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        if (!ModList.get().isLoaded("tacz")) {
            return;
        }
        Item gun = BuiltInRegistries.ITEM.get(MODERN_KINETIC_GUN);
        if (gun == Items.AIR) {
            return;
        }
        event.register(gun, GunOverDurabilityDecorator.INSTANCE);
    }

    private GunsmithDecoratorEvents() {
    }
}
