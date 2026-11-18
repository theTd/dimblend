package dimblend.experience.compat.lootjournal;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loot Journal 拾取布局的<b>第二层</b>兜底：即使 {@code elementOrder} 已被
 * {@link LootJournalLayoutSanitizer} 修好，{@code ConfigCache#refresh()} 仍可能在
 * {@code LayoutParser.parse} <b>之前</b>因别的原因抛出（例如配置尚未加载时读取
 * {@code Config.SCREEN_ANCHOR}），此时 {@code layout} 会永久停留在
 * {@code PickupLayout.DEFAULT = [NAME]}——文字在、图标/数量/总数全没。
 *
 * <p>本类在拾取 HUD 每次绘制入口检查：只要 {@code ConfigCache.layout} 仍是
 * {@code PickupLayout.DEFAULT} 那个实例，且 {@code Config.ELEMENT_ORDER} 已可读，就按
 * {@code LayoutParser.parse(LootJournalLayoutSanitizer.repairNormalized(elementOrder))}
 * 重建一次并写回静态字段。用户显式配置的纯 NAME 布局会解析成<b>新实例</b>（不是 DEFAULT
 * 常量），不会被误改。</p>
 *
 * <p>目标模块的类不在编译 classpath 上，因此整条链路用反射；解析失败只禁用一次并告警，
 * 不在每帧重复。</p>
 */
public final class LootJournalLayoutRepair {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("dimblend_experience/loot-journal-layout");

    private static boolean resolved;
    private static boolean unsupported;
    private static Field layoutField;
    private static Object defaultLayout;
    private static Method parseMethod;
    private static Object elementOrderConfig;
    private static Method elementOrderGet;

    /**
     * 拾取 HUD 绘制入口调用：仅在布局仍卡在 {@code PickupLayout.DEFAULT} 时重建一次。
     */
    public static void ensureLayoutResolved() {
        if (unsupported) {
            return;
        }
        if (!resolved) {
            resolve();
            if (unsupported) {
                return;
            }
        }
        try {
            if (layoutField.get(null) != defaultLayout) {
                return;
            }
            Object rawValue = elementOrderGet.invoke(elementOrderConfig);
            if (!(rawValue instanceof String raw)) {
                return;
            }
            Object rebuilt = parseMethod.invoke(null, LootJournalLayoutSanitizer.repairNormalized(raw));
            if (rebuilt == null) {
                return;
            }
            layoutField.set(null, rebuilt);
            LOGGER.warn("[loot-journal-layout] ConfigCache.layout 仍停留在只含 NAME 的"
                    + " PickupLayout.DEFAULT（refresh 在解析元素顺序前失败），"
                    + "已按 elementOrder \"{}\" 重建为完整布局", raw);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Config 尚未加载时 get() 会抛异常：下一帧继续重试，不刷屏。
        }
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> configCache = Class.forName("dev.obscuria.lootjournal.config.ConfigCache");
            layoutField = configCache.getField("layout");
            Class<?> layoutClass = Class.forName(
                    "dev.obscuria.lootjournal.client.renderer.layout.PickupLayout");
            defaultLayout = layoutClass.getField("DEFAULT").get(null);
            parseMethod = Class.forName(
                    "dev.obscuria.lootjournal.client.renderer.layout.LayoutParser")
                    .getMethod("parse", String.class);
            elementOrderConfig = Class.forName("dev.obscuria.lootjournal.config.Config")
                    .getField("ELEMENT_ORDER")
                    .get(null);
            elementOrderGet = elementOrderConfig.getClass().getMethod("get");
        } catch (ReflectiveOperationException | LinkageError e) {
            unsupported = true;
            LOGGER.warn("[loot-journal-layout] 无法解析 Loot Journal 配置类，"
                    + "跳过布局兜底修复：{}", e.toString());
        }
    }

    private LootJournalLayoutRepair() {
    }
}
