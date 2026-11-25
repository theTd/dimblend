package dimblend.experience.compat.coldsweat;

import java.util.List;

import com.google.common.collect.Multimap;
import com.momosoftworks.coldsweat.config.ConfigSettings;
import com.momosoftworks.coldsweat.data.codec.configuration.FuelData;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * 热冰作为 Cold Sweat 锅炉 / 壁炉燃料（Audit Default #3，`dimblend_blocks:hot_ice` → 55）。
 *
 * <p><b>只做加性注入</b>：{@code BOILER_FUEL} / {@code HEARTH_FUEL} 是
 * {@code DynamicHolder<Multimap<Item, FuelData>>}（{@code javap -p} 核实，Guava {@code Multimap}，
 * 由 {@code RegistryMultiMap::new} 填充）。这里只在 {@code !containsKey(hotIce)} 时
 * {@code put}，<b>绝不</b>覆盖用户在 {@code coldsweat/item.toml} 里给自己配的热冰条目；
 * 也绝不改写 TOML 文件本身。</p>
 *
 * <p>{@link FuelData#fromToml(List, FuelData.FuelType)} 是 Cold Sweat 解析 TOML 那行的同一个入口
 * （{@code ["dimblend_blocks:hot_ice", 55]}），所以数值语义（燃烧 tick）与配置文件完全一致，
 * 不需要自己构造 {@code ItemRequirement}。</p>
 *
 * <p>时机：Cold Sweat 的 TOML 在 {@code ModConfigEvent} 里被读进来、holder 也被（可能）整体替换，
 * 所以每次 Cold Sweat 的配置加载/重载后都补一次；再加一个 {@code FMLCommonSetupEvent} 兜住
 * "配置加载早于 common setup"的顺序。全程 {@code ModList} 双门禁：
 * {@code cold_sweat} 与 {@code dimblend_blocks} 都在场才动。</p>
 */
public final class HotIceColdSweatFuel {

    /** 热冰的物品 id。 */
    public static final String HOT_ICE_ID = "dimblend_blocks:hot_ice";

    /** 燃烧 tick（与 TOML 第二个元素同义）。 */
    public static final int BURN_TICKS = 55;

    /** Cold Sweat 的 mod id。 */
    private static final String COLD_SWEAT = "cold_sweat";

    /** 热冰来源 mod。 */
    private static final String HOT_ICE_MOD = "dimblend_blocks";

    /**
     * 由 {@code DimBlend} 构造期调用一次（manifest 里有调用行）。
     *
     * @param modBus {@code DimBlend} 自己的 mod 事件总线，用来挂 {@code FMLCommonSetupEvent}
     */
    public static void register(IEventBus modBus) {
        if (!ModList.get().isLoaded(COLD_SWEAT) || !ModList.get().isLoaded(HOT_ICE_MOD)) {
            return;
        }
        modBus.addListener(HotIceColdSweatFuel::onCommonSetup);
        ModList.get().getModContainerById(COLD_SWEAT).ifPresent(container -> {
            IEventBus csBus = container.getEventBus();
            if (csBus != null) {
                csBus.addListener(EventPriority.LOWEST, ModConfigEvent.Loading.class, HotIceColdSweatFuel::onColdSweatConfig);
                csBus.addListener(EventPriority.LOWEST, ModConfigEvent.Reloading.class, HotIceColdSweatFuel::onColdSweatConfig);
            }
        });
        // 配置可能已经先加载完（register 在构造期、配置在其后），也可能早于我们（dev 热重载），
        // 两个方向都试一次，inject 自身幂等。
        inject();
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(HotIceColdSweatFuel::inject);
    }

    private static void onColdSweatConfig(ModConfigEvent event) {
        if (COLD_SWEAT.equals(event.getConfig().getModId())) {
            inject();
        }
    }

    /**
     * 幂等地把热冰塞进两个燃料表。
     *
     * @return 本次是否至少写入了其中一张表（两张都已存在则为 false）
     */
    public static boolean inject() {
        if (!ModList.get().isLoaded(COLD_SWEAT) || !ModList.get().isLoaded(HOT_ICE_MOD)) {
            return false;
        }
        try {
            return injectIntoHolders();
        } catch (RuntimeException notReadyYet) {
            // Cold Sweat 的 holder 还没被配置加载填过（或正处于重载中间态）：静默跳过，
            // ModConfigEvent / FMLCommonSetupEvent 会再来一次。这里绝不能把启动带崩。
            return false;
        }
    }

    private static boolean injectIntoHolders() {
        ResourceLocation hotIceId = ResourceLocation.tryParse(HOT_ICE_ID);
        if (hotIceId == null || !BuiltInRegistries.ITEM.containsKey(hotIceId)) {
            return false;
        }
        Item hotIce = BuiltInRegistries.ITEM.get(hotIceId);
        boolean changed = false;
        changed |= putIfAbsent(ConfigSettings.BOILER_FUEL.get(), hotIce,
                FuelData.fromToml(List.of(HOT_ICE_ID, BURN_TICKS), FuelData.FuelType.BOILER));
        changed |= putIfAbsent(ConfigSettings.HEARTH_FUEL.get(), hotIce,
                FuelData.fromToml(List.of(HOT_ICE_ID, BURN_TICKS), FuelData.FuelType.HEARTH));
        return changed;
    }

    private static boolean putIfAbsent(Multimap<Item, FuelData> fuels, Item item, FuelData data) {
        if (fuels == null || item == null || data == null || fuels.containsKey(item)) {
            return false;
        }
        fuels.put(item, data);
        return true;
    }

    private HotIceColdSweatFuel() {
    }
}
