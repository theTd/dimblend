package dimblend.craft.recipe;

import java.util.Map;
import java.util.Optional;

import net.minecraft.core.HolderLookup;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;

/**
 * 任意配方类型的 JSON 字段编辑器：用配方自身序列化器的 Codec 把配方编码成 JSON，
 * 按点号路径写入字段后重新解码成配方实例，实现与类型无关的机器配方修改。
 *
 * <p>路径语法：点号分隔，每段为对象键或十进制数组下标（如 {@code results.0.count}，
 * 纯数字段一律按下标解释）；末段为「写入」语义（键存在则覆盖、不存在则新增，
 * 数组下标则必须落在已有范围内），中间段必须已存在且类型匹配。任何一步失败
 * 都返回 empty，由调用方保留原配方。</p>
 */
public final class RecipeJsonEditor {

    private RecipeJsonEditor() {
    }

    /**
     * 按字段编辑表重建配方。
     *
     * @return 编辑并重新解码后的配方；编码失败、路径结构不符或解码失败时返回 empty，
     *         调用方应保留原配方并告警
     */
    public static Optional<Recipe<?>> edit(Recipe<?> recipe, Map<String, JsonElement> fieldEdits,
            HolderLookup.Provider registries) {
        DynamicOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        try {
            return editTyped(recipe, fieldEdits, ops);
        } catch (Exception e) {
            // 部分上游序列化器的编码路径只面向解码实现，可能直接抛异常；
            // 与调用方约定一致：失败即放弃本条修改，由调用方保留原配方并告警。
            return Optional.empty();
        }
    }

    /** 以通配捕获的方式拿到配方自身序列化器的 Codec 完成往返；配方与其序列化器必然同型。 */
    @SuppressWarnings("unchecked")
    private static <T extends Recipe<?>> Optional<Recipe<?>> editTyped(Recipe<?> recipe,
            Map<String, JsonElement> fieldEdits, DynamicOps<JsonElement> ops) {
        RecipeSerializer<T> serializer = (RecipeSerializer<T>) recipe.getSerializer();
        Optional<JsonElement> encoded = serializer.codec().codec().encodeStart(ops, (T) recipe).result();
        if (encoded.isEmpty() || !encoded.get().isJsonObject()) {
            return Optional.empty();
        }
        JsonObject json = encoded.get().getAsJsonObject();
        for (Map.Entry<String, JsonElement> edit : fieldEdits.entrySet()) {
            if (!writePath(json, edit.getKey(), edit.getValue())) {
                return Optional.empty();
            }
        }
        DataResult<Pair<T, JsonElement>> decoded = serializer.codec().decoder().decode(ops, json);
        return decoded.result().map(pair -> (Recipe<?>) pair.getFirst());
    }

    /** 按路径写入：末段为写入语义，中间段必须已存在；任何一步结构不符返回 false。 */
    private static boolean writePath(JsonObject root, String path, JsonElement value) {
        String[] segments = path.split("\\.");
        JsonElement current = root;
        for (int i = 0; i < segments.length - 1; i++) {
            current = child(current, segments[i]);
            if (current == null) {
                return false;
            }
        }
        String last = segments[segments.length - 1];
        Integer index = arrayIndex(last);
        if (index != null) {
            if (!current.isJsonArray()) {
                return false;
            }
            JsonArray array = current.getAsJsonArray();
            if (index >= array.size()) {
                return false;
            }
            array.set(index, value);
            return true;
        }
        if (!current.isJsonObject()) {
            return false;
        }
        current.getAsJsonObject().add(last, value);
        return true;
    }

    /** 取路径段指向的子节点；结构不符或越界返回 null。 */
    private static JsonElement child(JsonElement node, String segment) {
        Integer index = arrayIndex(segment);
        if (index != null) {
            if (!node.isJsonArray()) {
                return null;
            }
            JsonArray array = node.getAsJsonArray();
            return index < array.size() ? array.get(index) : null;
        }
        if (!node.isJsonObject()) {
            return null;
        }
        return node.getAsJsonObject().get(segment);
    }

    /** 纯数字路径段解析为数组下标；空串、非纯数字或溢出均视为非下标段。 */
    private static Integer arrayIndex(String segment) {
        if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            return Integer.parseInt(segment);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
