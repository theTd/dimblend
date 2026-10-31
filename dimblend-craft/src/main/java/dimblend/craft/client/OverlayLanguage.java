package dimblend.craft.client;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

/**
 * 在游戏本次重载得到的语言实例之上叠一层显示名覆盖（2026-10-31 R12）。
 *
 * <p>只改写被覆盖的键，其余查询全部委托给底层实例；{@link #getLanguageData()} 返回合并后的
 * 全量表（FML 的 {@code I18nManager.injectTranslations} 会读它）。被覆盖的键一律按普通字符串
 * 处理：组件形式的原值（数组翻译）不再返回，避免旧文本盖住新名字。</p>
 */
public final class OverlayLanguage extends Language {

    private final Language base;
    private final Map<String, String> overrides;
    private final Map<String, String> merged;

    public OverlayLanguage(Language base, Map<String, String> overrides) {
        this.base = base;
        this.overrides = Map.copyOf(overrides);
        Map<String, String> data = new LinkedHashMap<>(base.getLanguageData());
        data.putAll(this.overrides);
        this.merged = Map.copyOf(data);
    }

    @Override
    public String getOrDefault(String key, String defaultValue) {
        String value = overrides.get(key);
        return value != null ? value : base.getOrDefault(key, defaultValue);
    }

    @Override
    public boolean has(String id) {
        return overrides.containsKey(id) || base.has(id);
    }

    @Override
    public boolean isDefaultRightToLeft() {
        return base.isDefaultRightToLeft();
    }

    @Override
    public FormattedCharSequence getVisualOrder(FormattedText text) {
        return base.getVisualOrder(text);
    }

    @Override
    public Map<String, String> getLanguageData() {
        return merged;
    }

    @Override
    public Component getComponent(String key) {
        // 覆盖值是普通字符串，组件查询必须落到 getOrDefault；只有未覆盖的键才沿用原组件。
        return overrides.containsKey(key) ? null : base.getComponent(key);
    }
}
