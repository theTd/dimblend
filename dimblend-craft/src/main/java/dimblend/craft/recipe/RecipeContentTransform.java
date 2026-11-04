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

    /**
     * 物品 id 的深度替换：把配方里**任何位置**写成 {@code from} 的物品改成 {@code to}。
     *
     * <p><b>为什么不能复用 {@link #replaceIngredient}。</b>那条只认材料对象的 {@code item}/{@code tag}，
     * 明确不碰产物路径。但有一类配方把物品 id 写进了产物的 NBT 里：森罗蒸笼
     * （{@code kaleidoscope_cookery:raw_dough_steamer}）产出的蒸笼在
     * {@code result.components."minecraft:block_entity_data".Items[].id} 里直接写了 4 个生面团，
     * 那是方块实体内容而不是材料。只改材料的话，配方的四个材料变成面团、蒸笼里装的仍是生面团，
     * 而那个物品此后已无法获得 —— 玩家会拿到一个装着幽灵物品的蒸笼。
     *
     * <p><b>为什么这样是安全的。</b>{@code item} 与 {@code id} 这两个键在这套配方 JSON 里就是
     * 「一个物品」的两种写法（材料用 {@code item}，产物与 BE 数据用 {@code id}），不存在
     * 同名但含义不同的情况。{@code block_entity_data.id}（方块实体**类型** id）与
     * {@code model_id}（显示模型）不会与本方法的 {@code from} 相同，因此不受影响。
     *
     * <p>未命中时返回输入实例（不做深拷贝），理由同 {@link #replaceIngredient}。
     */
    public static JsonElement replaceItemIdDeep(JsonElement recipeJson, ResourceLocation from,
            ResourceLocation to) {
        if (!containsItemIdDeep(recipeJson, from)) {
            return recipeJson;
        }
        JsonElement copy = recipeJson.deepCopy();
        replaceItemIdDeepInPlace(copy, from, to);
        return copy;
    }

    /** 快速预检：任意深度下是否存在 {@code item}/{@code id} 等于 {@code from}。 */
    private static boolean containsItemIdDeep(JsonElement node, ResourceLocation from) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                if (containsItemIdDeep(child, from)) {
                    return true;
                }
            }
            return false;
        }
        if (!node.isJsonObject()) {
            return false;
        }
        JsonObject object = node.getAsJsonObject();
        if (matchesItemId(object, ITEM_KEY, from) || matchesItemId(object, ID_KEY, from)) {
            return true;
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (containsItemIdDeep(entry.getValue(), from)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesItemId(JsonObject object, String key, ResourceLocation from) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && from.toString().equals(value.getAsString());
    }

    private static void replaceItemIdDeepInPlace(JsonElement node, ResourceLocation from, ResourceLocation to) {
        if (node.isJsonArray()) {
            for (JsonElement child : node.getAsJsonArray()) {
                replaceItemIdDeepInPlace(child, from, to);
            }
            return;
        }
        if (!node.isJsonObject()) {
            return;
        }
        JsonObject object = node.getAsJsonObject();
        if (matchesItemId(object, ITEM_KEY, from)) {
            object.add(ITEM_KEY, new JsonPrimitive(to.toString()));
        }
        if (matchesItemId(object, ID_KEY, from)) {
            object.add(ID_KEY, new JsonPrimitive(to.toString()));
        }
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            replaceItemIdDeepInPlace(entry.getValue(), from, to);
        }
    }
}
