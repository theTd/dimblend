package dimblend.experience.compat.coldsweat;

import com.momosoftworks.coldsweat.common.item.SoulspringLampItem;
import com.momosoftworks.coldsweat.core.init.ModItems;

import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * 灵魂之泉灯的 2D 物品属性覆盖（客户端）：{@code cold_sweat:soulspring_state} 的三段阈值
 * 换成 {@code 171 / 85}（LOCKED DECISION #15）。
 *
 * <p>Cold Sweat 的 {@code RegisterItemOverrides} 用 lambda 注册同一个属性
 * （{@code fuel > 43 ? 3 : fuel > 22 ? 2 : 1}，按 64 容量），阈值<b>没法</b>从外部改。
 * 但 {@code ItemProperties.register} 是"往同一个 {@code Map<Item, Map<ResourceLocation, fn>>}
 * 里 put"，同名属性后注册的胜出，所以这里用同名键再注册一次即可——不碰 Cold Sweat 的任何类结构。</p>
 *
 * <p>注册时机：必须晚于 Cold Sweat 的 {@code FMLClientSetupEvent#enqueueWork}。
 * 做法是在同一个事件里"再 enqueue 一层"：第二层任务在第一层全部执行完之后才跑，
 * 不依赖模组加载顺序。</p>
 */
public final class SoulspringLampClientOverrides {

    /** 属性名（Cold Sweat 自己的 {@code cold_sweat:soulspring_state}）。 */
    public static final ResourceLocation PROPERTY_STATE =
            ResourceLocation.fromNamespaceAndPath("cold_sweat", "soulspring_state");

    /** 在 {@code enqueueWork} 内调用（由 manifest 的 DimBlendClient 片段接线）。 */
    public static void register() {
        Item lamp = ModItems.SOULSPRING_LAMP.get();
        ItemProperties.register(lamp, PROPERTY_STATE,
                (stack, level, entity, seed) -> SoulspringLampRules.modelState(
                        SoulspringLampItem.isLit(stack), SoulspringLampItem.getFuel(stack)));
    }

    private SoulspringLampClientOverrides() {
    }
}
