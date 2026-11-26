package dimblend.blocks.compat.create.funnel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 卸料漏斗资产"对齐 Create 6.0.10 黄铜漏斗"的机械自检。
 *
 * <p>三态的视觉问题一旦定位到资产层，只有三种可能：blockstate 的变体/旋转写错、模型没继承
 * 原版几何（自带 elements 或 parent 挂错）、父模型用到的贴图槽位没被覆盖（模型会回落到
 * Create 的黄铜贴图）。这三件事都能在纯 JVM 里断言，不需要启动游戏：</p>
 * <ul>
 *   <li>Create 6.0.10 的 jar（{@code com.simibubi.create:create-1.21.1:6.0.10-281}）就在
 *       测试类路径上，它的 {@code assets/create/**} 与被测的 {@code assets/dimblend_blocks/**}
 *       可以同时读到，于是可以逐个变体比对；</li>
 *   <li>父模型链的元素引用 {@code #槽位名}，可以递归收集后与本地 {@code textures} 求差集。</li>
 * </ul>
 */
class DumpFunnelAssetsTest {

    private static final String OUR_BLOCKSTATES = "assets/dimblend_blocks/blockstates/";
    private static final String OUR_MODELS = "assets/dimblend_blocks/models/block/funnel/";
    private static final String OUR_ITEM_MODELS = "assets/dimblend_blocks/models/item/";

    /** 我们的模型名 -> 必须继承的 Create 原版模型。 */
    private static final Map<String, String> EXPECTED_PARENTS = expectedParents();

    private static Map<String, String> expectedParents() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String shape : new String[] {"retracted", "extended", "pulling", "pushing"}) {
            for (String power : new String[] {"powered", "unpowered"}) {
                map.put("dump_belt_funnel_" + shape + "_" + power, "create:block/belt_funnel/block_" + shape);
            }
        }
        for (String direction : new String[] {"pull", "push"}) {
            for (String power : new String[] {"powered", "unpowered"}) {
                map.put("dump_funnel_horizontal_" + direction + "_" + power, "create:block/funnel/block_horizontal");
                map.put("dump_funnel_vertical_" + direction + "_" + power, "create:block/funnel/block_vertical");
            }
        }
        map.put("dump_funnel_item", "create:block/funnel/item");
        return map;
    }

    // ------------------------------------------------------------ blockstate 变体

    @Test
    void funnelBlockstateMirrorsCreateBrassFunnelVariants() {
        assertVariantsMatch(OUR_BLOCKSTATES + "dump_funnel.json",
                "dimblend_blocks:block/funnel/dump_funnel_", "assets/create/blockstates/brass_funnel.json",
                "create:block/brass_funnel_", 48);
    }

    @Test
    void beltFunnelBlockstateMirrorsCreateBrassBeltFunnelVariants() {
        assertVariantsMatch(OUR_BLOCKSTATES + "dump_belt_funnel.json",
                "dimblend_blocks:block/funnel/dump_belt_funnel_", "assets/create/blockstates/brass_belt_funnel.json",
                "create:block/brass_belt_funnel_", 64);
    }

    // ------------------------------------------------------------ 模型继承

    @Test
    void blockModelsArePureTextureOverridesOfCreateGeometry() {
        for (Map.Entry<String, String> entry : EXPECTED_PARENTS.entrySet()) {
            String file = OUR_MODELS + entry.getKey() + ".json";
            JsonObject model = readJson(file);

            assertEquals(entry.getValue(), model.get("parent").getAsString(),
                    file + " 必须直接继承 Create 原版模型");
            assertFalse(model.has("elements"),
                    file + " 不允许自带几何：三态形状必须 100% 来自 Create 原版模型");
            assertTrue(model.has("textures"), file + " 必须覆盖贴图");
            assertTrue(resourceExists(modelPath(entry.getValue())),
                    "Create 侧父模型不存在，继承会断链: " + entry.getValue());
        }
    }

    @Test
    void everyTextureSlotUsedByTheParentChainIsOverridden() {
        for (Map.Entry<String, String> entry : EXPECTED_PARENTS.entrySet()) {
            JsonObject model = readJson(OUR_MODELS + entry.getKey() + ".json");
            Set<String> overridden = new TreeSet<>(model.getAsJsonObject("textures").keySet());

            Set<String> used = new TreeSet<>(slotsUsedByParentChain(entry.getValue()));
            used.removeAll(overridden);
            assertTrue(used.isEmpty(),
                    entry.getKey() + " 少了父模型用到的贴图槽位（会回落到 Create 黄铜贴图）: " + used);
        }
    }

    @Test
    void verticalModelsKeepCreatesOpenClosedThroatMapping() {
        // 竖直漏斗的 #open 槽位：未通电=开口、通电=闭合（Create brass_funnel_vertical_* 同款映射）
        assertEquals("create:block/funnel/funnel_open",
                readJson(OUR_MODELS + "dump_funnel_vertical_push_unpowered.json")
                        .getAsJsonObject("textures").get("open").getAsString());
        assertEquals("create:block/funnel/funnel_closed",
                readJson(OUR_MODELS + "dump_funnel_vertical_push_powered.json")
                        .getAsJsonObject("textures").get("open").getAsString());
    }

    @Test
    void itemModelOnlyPointsAtTheBlockItemModel() {
        JsonObject item = readJson(OUR_ITEM_MODELS + "dump_funnel.json");
        assertEquals("dimblend_blocks:block/funnel/dump_funnel_item", item.get("parent").getAsString());
    }

    // ------------------------------------------------------------ 工具

    private static void assertVariantsMatch(String ourBlockstate, String ourModelPrefix, String createBlockstate,
            String createModelPrefix, int expectedVariants) {
        Map<String, String> ours = variantSignatures(ourBlockstate, ourModelPrefix, createModelPrefix);
        Map<String, String> create = variantSignatures(createBlockstate, createModelPrefix, createModelPrefix);

        assertEquals(expectedVariants, ours.size(), ourBlockstate + " 变体数");
        assertEquals(expectedVariants, create.size(), createBlockstate + " 变体数");

        Set<String> differences = new TreeSet<>();
        for (String key : union(ours.keySet(), create.keySet())) {
            String ourValue = ours.get(key);
            String createValue = create.get(key);
            if (ourValue == null) {
                differences.add("缺变体 " + key);
            } else if (createValue == null) {
                differences.add("多出变体 " + key);
            } else if (!ourValue.equals(createValue)) {
                differences.add(key + " : 我们=" + ourValue + " 原版=" + createValue);
            }
        }
        assertTrue(differences.isEmpty(), ourBlockstate + " 与 Create 变体不一致:\n" + String.join("\n", differences));
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> all = new TreeSet<>(left);
        all.addAll(right);
        return all;
    }

    /** 变体 -> "模型|x|y|uvlock"，模型名统一改写成 Create 的命名以便直接比对。 */
    private static Map<String, String> variantSignatures(String path, String ourModelPrefix, String createModelPrefix) {
        JsonObject variants = readJson(path).getAsJsonObject("variants");
        Map<String, String> signatures = new TreeMap<>();
        for (Map.Entry<String, JsonElement> variant : variants.entrySet()) {
            JsonElement value = variant.getValue();
            if (!value.isJsonObject()) {
                fail(path + " 的变体 " + variant.getKey() + " 不是单一模型（不支持 weighted 变体）");
            }
            JsonObject object = value.getAsJsonObject();
            String model = object.get("model").getAsString().replace(ourModelPrefix, createModelPrefix);
            signatures.put(variant.getKey(), model
                    + "|x=" + intOrZero(object, "x")
                    + "|y=" + intOrZero(object, "y")
                    + "|uvlock=" + (object.has("uvlock") && object.get("uvlock").getAsBoolean()));
        }
        return signatures;
    }

    private static int intOrZero(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsInt() : 0;
    }

    /** 沿父链递归收集元素面引用的 {@code #槽位} 名（父模型都直接带 elements）。 */
    private static Set<String> slotsUsedByParentChain(String modelRef) {
        Set<String> slots = new TreeSet<>();
        String current = modelRef;
        while (current != null) {
            JsonObject model = readOptionalJson(modelPath(current));
            if (model == null) {
                break;
            }
            JsonElement elements = model.get("elements");
            if (elements != null) {
                for (JsonElement element : elements.getAsJsonArray()) {
                    JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
                    for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                        String texture = face.getValue().getAsJsonObject().get("texture").getAsString();
                        if (texture.startsWith("#")) {
                            slots.add(texture.substring(1));
                        }
                    }
                }
                break;
            }
            JsonElement parent = model.get("parent");
            current = parent == null ? null : parent.getAsString();
        }
        return slots;
    }

    private static String modelPath(String ref) {
        String namespaced = ref.indexOf(':') >= 0 ? ref : "minecraft:" + ref;
        String namespace = namespaced.substring(0, namespaced.indexOf(':'));
        String path = namespaced.substring(namespaced.indexOf(':') + 1);
        return "assets/" + namespace + "/models/" + path + ".json";
    }

    private static JsonObject readJson(String classpathPath) {
        JsonObject json = readOptionalJson(classpathPath);
        if (json == null) {
            throw new AssertionError("类路径资源缺失: " + classpathPath);
        }
        return json;
    }

    private static JsonObject readOptionalJson(String classpathPath) {
        try (InputStream in = DumpFunnelAssetsTest.class.getClassLoader().getResourceAsStream(classpathPath)) {
            if (in == null) {
                return null;
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean resourceExists(String classpathPath) {
        try (InputStream in = DumpFunnelAssetsTest.class.getClassLoader().getResourceAsStream(classpathPath)) {
            return in != null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
