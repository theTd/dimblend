package dimblend.craft.recipe;

import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 配方 JSON 的通用内容替换（2026-10-31 R12）：只按字段名与精确 id 命中，不解析配方类型，
 * 因此对 {@code minecraft:crafting_shaped/shapeless}、Create 加工配方、森罗锅/磨等
 * 自定义类型一视同仁。
 *
 * <p>两个变换都先做一次「是否存在命中」的预检，未命中时原样返回输入（不复制）；
 * 命中时先 {@link JsonElement#deepCopy()} 再改写：调用方用「变换前后 JSON 是否相等 / 同一实例」
 * 判断是否命中，所以绝不能原地修改输入，且变换天然幂等（第一次替换后已无旧 id 可命中）。</p>
 */
public final class RecipeContentTransform {

    private static final String ITEM_KEY = "item";
    private static final String TAG_KEY = "tag";
    private static final String ID_KEY = "id";

    /**
     * 产物路径的字段名。只有这些键下的 {@code id} 才是产物：
     * {@code result}（合成/烧炼/森罗磨与锅）、{@code results}（Create 加工配方结果池）、
     * {@code transitional_item}（Create 序列组装过渡产物）。
     */
    private static final Set<String> RESULT_KEYS = Set.of("result", "results", "transitional_item");

    private RecipeContentTransform() {
    }

    /**
     * 材料替换：把 {@code item}/{@code tag} 精确等于 {@code from} 的材料对象改写成 {@code to}。
     *
     * <p>命中对象只保留新写法（移除原来的 {@code item}/{@code tag}），对象上的其它字段原样保留；
     * 标签只按 id 精确命中，包含关系不算。产物对象使用 {@code id} 字段，不会被本变换改写。</p>
     *
     * <p>未命中时直接返回输入实例（不做深拷贝），调用方可用引用相等快速判断「无变化」；
     * 每次执行器都会对全部配方跑一遍，这个短路避免为无关配方复制整段 JSON。</p>
     */
    public static JsonElement replaceIngredient(JsonElement recipeJson, RecipeEdit.IngredientTarget from,
            RecipeEdit.IngredientTarget to) {
        if (!containsTarget(recipeJson, from)) {
            return recipeJson;
        }
        JsonElement copy = recipeJson.deepCopy();
        replaceIngredientInPlace(copy, from, to);
        return copy;
    }

    /** 快速预检：配方里是否存在写法与目标完全一致的 {@code item}/{@code tag}。 */
    private static boolean containsTarget(JsonElement node, RecipeEdit.IngredientTarget target) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                if (containsTarget(child, target)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isJsonObject()) {
            return false;
        }
        JsonObject object = node.getAsJsonObject();
        if (matchesTarget(object, target)) {
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (containsTarget(entry.getValue(), target)) {
                return true;
            }
        }
        return false;
    }

    private static void replaceIngredientInPlace(JsonElement node, RecipeEdit.IngredientTarget from,
            RecipeEdit.IngredientTarget to) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                replaceIngredientInPlace(child, from, to);
            }
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        JsonObject object = node.getAsJsonObject();
        if (matchesTarget(object, from)) {
            object.remove(ITEM_KEY);
            object.remove(TAG_KEY);
            object.add(to.tag() ? TAG_KEY : ITEM_KEY, new JsonPrimitive(to.id().toString()));
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            replaceIngredientInPlace(entry.getValue(), from, to);
        }
    }

    private static boolean matchesTarget(JsonObject object, RecipeEdit.IngredientTarget target) {
        JsonElement value = object.get(target.tag() ? TAG_KEY : ITEM_KEY);
        return value != null && value.isJsonPrimitive() && target.id().toString().equals(value.getAsString());
    }

    /**
     * 产物替换：只改产物路径下的 {@code id}，数量（{@code count}/{@code amount}）与
     * 概率等其它字段原样；材料侧与配方自定义数据里的同名 {@code id} 不受影响。
     */
    public static JsonElement replaceResultId(JsonElement recipeJson, ResourceLocation from, ResourceLocation to) {
        if (!containsResultId(recipeJson, false, from)) {
            return recipeJson;
        }
        JsonElement copy = recipeJson.deepCopy();
        replaceResultIdInPlace(copy, false, from, to);
        return copy;
    }

    /** 快速预检：产物路径下是否存在 {@code id == from} 的条目。 */
    private static boolean containsResultId(JsonElement node, boolean inResult, ResourceLocation from) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                if (containsResultId(child, inResult, from)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isJsonObject()) {
            return false;
        }
        JsonObject object = node.getAsJsonObject();
        JsonElement id = object.get(ID_KEY);
        if (inResult && id != null) {
            return id.isJsonPrimitive() && from.toString().equals(id.getAsString());
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (containsResultId(entry.getValue(), inResult || RESULT_KEYS.contains(entry.getKey()), from)) {
                return true;
            }
        }
        return false;
    }

    private static void replaceResultIdInPlace(JsonElement node, boolean inResult, ResourceLocation from,
            ResourceLocation to) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                replaceResultIdInPlace(child, inResult, from, to);
            }
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        JsonObject object = node.getAsJsonObject();
        JsonElement id = object.get(ID_KEY);
        if (inResult && id != null && id.isJsonPrimitive() && from.toString().equals(id.getAsString())) {
            object.add(ID_KEY, new JsonPrimitive(to.toString()));
            return;
        }
        if (inResult && id != null) {
            // 已是另一个产物：不再深入该结果对象的自定义数据，避免误改同名 id。
            return;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            replaceResultIdInPlace(entry.getValue(), inResult || RESULT_KEYS.contains(entry.getKey()), from, to);
        }
    }
}
