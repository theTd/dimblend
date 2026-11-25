package dimblend.experience.compat.yat;

import dev.minhnh.yetanotherthirst.core.thirst.ThirstConfig;
import dimblend.experience.DimBlend;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * YAT 滤芯平衡 + 手捧喝水开关（LOCKED DECISION #8 的前置条件）。
 *
 * <p>{@code ThirstConfig} 的这些字段是"模板常量"而不是配置项，YAT 在 config load / reload
 * 时会重新读文件把静态字段写回默认值（{@code dimblend-dev} §8.60 的坑）。因此覆盖必须在
 * <b>YAT 自己的 mod event bus</b> 上挂 {@link ModConfigEvent.Loading}/{@link ModConfigEvent.Reloading}
 * 监听，优先级 {@link EventPriority#LOWEST}（保证读数之后、别人之前写回）；只在
 * {@code FMLCommonSetupEvent} 里写一次会在玩家改配置后失效。</p>
 *
 * <p>{@code CAN_DRINK_BY_HAND} 强制为 true：需求要求"蹲下右键水源直接喝"始终可用
 * （滤芯过滤的前提就是能手捧喝）。</p>
 */
public final class ThirstFilterBalance {

    /** 首次装配时记下的耐久基线（reload 会重复触发，不能每次都拿当前值再折半/翻倍）。 */
    private static int fabricDurabilityBase = -1;
    private static int carbonDurabilityBase = -1;

    /** 手捧喝水强制开启（需求：不允许被配置关掉）。 */
    public static void apply() {
        if (fabricDurabilityBase < 0) {
            fabricDurabilityBase = ThirstConfig.FABRIC_FILTER_DURABILITY;
            carbonDurabilityBase = ThirstConfig.CARBON_FILTER_DURABILITY;
        }
        ThirstConfig.CAN_DRINK_BY_HAND = true;
        ThirstConfig.FABRIC_FILTER_MAX_PURITY = 1;
        ThirstConfig.SAND_FILTER_MAX_PURITY = 1;
        ThirstConfig.CARBON_FILTER_MAX_PURITY = 1;
        ThirstConfig.FABRIC_FILTER_DURABILITY = fabricDurabilityBase / 2;
        ThirstConfig.CARBON_FILTER_DURABILITY = carbonDurabilityBase * 2;
    }

    /** 首次装配（{@code FMLCommonSetupEvent}，走 {@code enqueueWork} 线程安全）。 */
    public static void setup(FMLCommonSetupEvent event) {
        event.enqueueWork(ThirstFilterBalance::apply);
    }

    /**
     * 在 YAT 的 mod event bus 上挂 config load/reload 钩子（{@code DimBlend} 初始化时调用一次）。
     * 先判 {@code ModList} 里有 YAT，避免 {@code getModContainerById} 空指针。
     */
    public static void register() {
        ModContainer container = ModList.get().getModContainerById("yet_another_thirst").orElse(null);
        if (container == null) {
            DimBlend.LOGGER.warn("yet_another_thirst missing, filter balance overrides skipped");
            return;
        }
        IEventBus bus = container.getEventBus();
        if (bus == null) {
            return;
        }
        bus.addListener(EventPriority.LOWEST, ModConfigEvent.Loading.class, event -> apply());
        bus.addListener(EventPriority.LOWEST, ModConfigEvent.Reloading.class, event -> apply());
    }

    private ThirstFilterBalance() {
    }
}
