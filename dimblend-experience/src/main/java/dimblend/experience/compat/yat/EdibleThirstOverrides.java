package dimblend.experience.compat.yat;

import dev.minhnh.yetanotherthirst.NeoForgeConfigItems;
import dev.minhnh.yetanotherthirst.core.thirst.ThirstValue;
import dev.minhnh.yetanotherthirst.core.thirst.ThirstValues;
import dimblend.experience.DimBlend;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/**
 * Applies the bundled edible thirst table on top of Yet Another Thirst's configured values.
 *
 * <p>YAT rebuilds {@code ThirstValues.DRINKS}/{@code FOODS} from {@code items.toml} on every
 * {@code ServerStartedEvent}, {@code TagsUpdatedEvent} and config load, using
 * {@code replaceConfiguredValues} which clears both maps first. YAT's own public API is
 * {@code putIfAbsent}, so it can never override an item the pack already configured (for example
 * {@code create:builders_tea} at 12/22). This class therefore merges into the two live maps after
 * each rebuild: unmatched items keep their YAT values, matched items move to the rule's value and
 * out of the opposite map, and items in YAT's {@code itemsBlacklist} are never touched.</p>
 *
 * <p>Registration must happen only when {@code yet_another_thirst} is loaded; every referenced
 * YAT type is resolved lazily via {@link ModList#isLoaded} in {@link DimBlend}.</p>
 */
public final class EdibleThirstOverrides {

    private static final String YAT_MOD_ID = "yet_another_thirst";
    private static final String DRINKS_FIELD = "DRINKS";
    private static final String FOODS_FIELD = "FOODS";
    private static final String BLACKLIST_FIELD = "ITEMS_BLACKLIST";

    private static volatile EdibleThirstRuleEngine.Table table;
    private static volatile boolean tableFailed;
    private static boolean applyLogged;

    /** Hooks the merge after YAT's own reload on every trigger that rebuilds its maps. */
    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, EdibleThirstOverrides::onServerStarted);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, EdibleThirstOverrides::onTagsUpdated);

        // ModConfigEvent 只投递到持有该配置的 mod 自己的总线，本 mod 的总线收不到 YAT 的
        // items.toml 事件；挂到 YAT 的容器总线上，LOWEST 保证排在 YAT 默认优先级的
        // reloadThirstValues 之后。
        IEventBus yatBus = ModList.get().getModContainerById(YAT_MOD_ID)
                .map(container -> container.getEventBus())
                .orElse(null);
        if (yatBus == null) {
            DimBlend.LOGGER.warn("[yat-thirst] 未找到 {} 的容器事件总线，配置文件热重载时将不会重放口渴值覆盖", YAT_MOD_ID);
            return;
        }
        yatBus.addListener(EventPriority.LOWEST, ModConfigEvent.Loading.class, event -> apply("config-loading"));
        yatBus.addListener(EventPriority.LOWEST, ModConfigEvent.Reloading.class, event -> apply("config-reloading"));
    }

    private static void onServerStarted(ServerStartedEvent event) {
        apply("server-started");
    }

    private static void onTagsUpdated(TagsUpdatedEvent event) {
        apply("tags-updated");
    }

    /**
     * Re-applies the table onto YAT's live maps. Safe to call before registries/tags exist: every
     * unresolvable item is skipped and the next trigger (tags update / server start) retries.
     */
    private static void apply(String trigger) {
        EdibleThirstRuleEngine.Table rules = rules();
        if (rules == null) {
            return;
        }
        Map<Item, ThirstValue> drinks;
        Map<Item, ThirstValue> foods;
        try {
            drinks = thirstMap(DRINKS_FIELD);
            foods = thirstMap(FOODS_FIELD);
        } catch (ReflectiveOperationException | RuntimeException e) {
            DimBlend.LOGGER.error("[yat-thirst] 无法访问 YAT ThirstValues 的 DRINKS/FOODS，口渴值覆盖未生效", e);
            return;
        }
        Set<Item> blacklist = blacklist();
        int applied = 0;
        int skipped = 0;
        for (EdibleThirstRuleEngine.Entry entry : rules.entries()) {
            ResourceLocation itemId = ResourceLocation.tryParse(entry.item());
            if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
                skipped++;
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (blacklist.contains(item)) {
                skipped++;
                continue;
            }
            ThirstValue value = new ThirstValue(entry.thirst(), entry.quenched());
            if (entry.kind() == EdibleThirstRuleEngine.Kind.DRINK) {
                foods.remove(item);
                drinks.put(item, value);
            } else {
                drinks.remove(item);
                foods.put(item, value);
            }
            applied++;
        }
        if (applyLogged) {
            DimBlend.LOGGER.debug("[yat-thirst] {} 重放口渴值覆盖：应用 {} 项，跳过 {} 项（未注册或黑名单）",
                    trigger, applied, skipped);
        } else {
            applyLogged = true;
            DimBlend.LOGGER.info("[yat-thirst] {} 应用口渴值覆盖：{} 项，跳过 {} 项（未注册或黑名单）",
                    trigger, applied, skipped);
        }
    }

    private static EdibleThirstRuleEngine.Table rules() {
        if (table == null && !tableFailed) {
            try {
                table = EdibleThirstRuleEngine.loadBundled();
            } catch (RuntimeException e) {
                tableFailed = true;
                DimBlend.LOGGER.error("[yat-thirst] 口渴值覆盖表加载失败，本次运行不覆盖任何物品", e);
            }
        }
        return table;
    }

    @SuppressWarnings("unchecked")
    private static Map<Item, ThirstValue> thirstMap(String fieldName) throws ReflectiveOperationException {
        // ThirstValues 只公开 get/putIfAbsent/replaceConfiguredValues；覆盖已有值必须拿到 live map。
        Field field = ThirstValues.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<Item, ThirstValue>) field.get(null);
    }

    /** Resolves YAT's {@code itemsBlacklist}; unreadable config means "no blacklist configured". */
    private static Set<Item> blacklist() {
        List<?> configured;
        try {
            Field field = NeoForgeConfigItems.class.getDeclaredField(BLACKLIST_FIELD);
            field.setAccessible(true);
            Object raw = field.get(null);
            if (!(raw instanceof ModConfigSpec.ConfigValue<?> configValue)) {
                return Set.of();
            }
            configured = configValue.get() instanceof List<?> list ? list : List.of();
        } catch (IllegalStateException e) {
            // 配置尚未加载（构造期、测试环境）：本轮不设黑名单，下一次触发会重新读取。
            return Set.of();
        } catch (ReflectiveOperationException | RuntimeException e) {
            DimBlend.LOGGER.warn("[yat-thirst] 读取 YAT itemsBlacklist 失败，本次按未配置黑名单处理", e);
            return Set.of();
        }
        if (configured.isEmpty()) {
            return Set.of();
        }
        Set<Item> items = new HashSet<>(configured.size());
        for (Object entry : configured) {
            if (!(entry instanceof String id) || id.isEmpty()) {
                continue;
            }
            if (id.charAt(0) == '#') {
                ResourceLocation tagId = ResourceLocation.tryParse(id.substring(1));
                if (tagId != null) {
                    BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, tagId))
                            .ifPresent(tag -> tag.forEach(holder -> items.add(holder.value())));
                }
                continue;
            }
            ResourceLocation itemId = ResourceLocation.tryParse(id);
            if (itemId != null && BuiltInRegistries.ITEM.containsKey(itemId)) {
                items.add(BuiltInRegistries.ITEM.get(itemId));
            }
        }
        return items;
    }

    private EdibleThirstOverrides() {
    }
}
