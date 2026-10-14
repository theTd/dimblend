package dimblend.experience.compat.sable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G6 熔毁免疫标签的数据接线校验：{@code SableLavaMelt#LAVA_MELT_IMMUNE} 指向
 * {@code data/dimblend_experience/tags/block/lava_melt_immune.json}，豁免语义靠
 * 继承 {@code #create:non_breakable} 落地——本条断言嵌套引用存在且为可选（Create
 * 缺席时跳过），被继承清单仍覆盖 simurail 车辆关键部件（转向架/自动车钩）。
 *
 * <p>纯资源校验：不加载 {@code SableLavaMelt} 类（其方法签名引用 Sable 类型，单测
 * 运行时 classpath 没有 sable jar，加载与否取决于 JVM 惰性验证，不做此假设）。</p>
 */
class LavaMeltImmunityTagTest {

    @Test
    void immunityTagNestsCreateNonBreakableAsOptional() throws Exception {
        Set<String> entries = entryIds(tag("dimblend_experience", "lava_melt_immune"));
        assertTrue(entries.contains("#create:non_breakable"),
                "lava_melt_immune must inherit create:non_breakable but has " + entries);
        for (JsonElement value : tag("dimblend_experience", "lava_melt_immune").getAsJsonArray("values")) {
            JsonObject entry = value.getAsJsonObject();
            if ("#create:non_breakable".equals(entry.get("id").getAsString())) {
                assertTrue(entry.has("required") && !entry.get("required").getAsBoolean(),
                        "nested create:non_breakable must be optional (Create may be absent)");
            }
        }
    }

    @Test
    void createNonBreakableStillCoversSimurailVehicleComponents() throws Exception {
        Set<String> entries = entryIds(tag("create", "non_breakable"));
        assertTrue(entries.contains("simurail:physics_bogey"),
                "non_breakable must keep covering the bogie but has " + entries);
        assertTrue(entries.contains("simurail:automatic_coupler"),
                "non_breakable must keep covering the coupler but has " + entries);
    }

    private static Set<String> entryIds(JsonObject tag) {
        Set<String> ids = new HashSet<>();
        for (JsonElement value : tag.getAsJsonArray("values")) {
            if (value.isJsonPrimitive()) {
                ids.add(value.getAsString());
            } else {
                ids.add(value.getAsJsonObject().get("id").getAsString());
            }
        }
        return ids;
    }

    private JsonObject tag(String namespace, String name) throws Exception {
        var stream = getClass().getResourceAsStream("/data/" + namespace + "/tags/block/" + name + ".json");
        assertNotNull(stream, "tag file missing: " + namespace + ":" + name);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }
}
