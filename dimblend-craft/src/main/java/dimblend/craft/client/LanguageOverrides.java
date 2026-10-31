package dimblend.craft.client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 外 namespace 显示名覆盖表（2026-10-31 R12）：随包 JSON 在
 * {@code assets/dimblend_craft/lang_overrides/<code>.json}，由客户端 mixin 在语言重载后
 * 作为<b>最高优先级</b>覆盖层安装，不依赖整合包里各 mod 资源包的加载顺序。
 *
 * <p>文件不放在 {@code assets/dimblend_craft/lang/} 下，就是为了不让原版语言加载器把它当成
 * 普通语言文件：只保留一种覆盖机制（mixin 覆盖层），避免「有时候资源包顺序恰好也生效」的
 * 半生效状态。</p>
 *
 * <p>语义按原版回落规则：先铺英文表，再叠当前语言表；没有该语言表时（如日语）显示英文名。
 * 文件缺失或解析失败返回空表，由调用方告警，不崩溃。</p>
 */
public final class LanguageOverrides {

    /** 覆盖表资源目录（以 classpath 绝对路径访问，不受当前选中的资源包影响）。 */
    public static final String RESOURCE_DIR = "/assets/dimblend_craft/lang_overrides/";

    private static final String FALLBACK_CODE = "en_us";
    private static final Map<String, Map<String, String>> CACHE = new ConcurrentHashMap<>();

    private LanguageOverrides() {
    }

    /** 当前语言应安装的覆盖表：英文为基础，当前语言（若提供）覆盖在其上。 */
    public static Map<String, String> forLanguage(String selectedCode) {
        Map<String, String> merged = new LinkedHashMap<>(load(FALLBACK_CODE));
        if (!FALLBACK_CODE.equals(selectedCode)) {
            merged.putAll(load(selectedCode));
        }
        return Map.copyOf(merged);
    }

    /** 读取某语言的覆盖表（结果缓存）；文件缺失或解析失败返回空表。 */
    public static Map<String, String> load(String code) {
        return CACHE.computeIfAbsent(code, LanguageOverrides::read);
    }

    private static Map<String, String> read(String code) {
        try (InputStream in = LanguageOverrides.class.getResourceAsStream(RESOURCE_DIR + code + ".json")) {
            if (in == null) {
                return Map.of();
            }
            JsonObject json = JsonParser
                    .parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            Map<String, String> table = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                    table.put(entry.getKey(), value.getAsString());
                }
            }
            return Map.copyOf(table);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
