package dimblend.experience.datagen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 工厂切石机配方范围锁：48 个装饰方块（42 个 {@code Type.base} + 6 种风扇），
 * 只有 {@code medium_fan} 因碰撞箱问题排除（用户 2026-10-31 口径）。
 *
 * <p>纯内存 + 生成物 classpath 断言，不进游戏；上游 factory_blocks 增删方块时此测试会先红，
 * 提醒同步 {@link FactoryStonecuttingBlocks} 并重跑 runData。
 */
class FactoryStonecuttingProviderTest {
    /**
     * 1.4.0 的 6 种风扇：{@code fan} / {@code fan_four} / {@code fan_malfunction} 是
     * {@code Type.redFan}，三个 {@code _on} 变体是 {@code Type.baseFan}，注册名即方块/物品 ID。
     * {@code medium_fan}（{@code Type.mediumFan}）不在此列。
     */
    private static final Set<String> INCLUDED_FANS = Set.of(
            "fan", "fan_on", "fan_four", "fan_four_on",
            "fan_malfunction", "fan_malfunction_on");

    private static final String MEDIUM_FAN = "medium_fan";

    @Test
    void decorativeBlockCountIs48() {
        assertEquals(48, FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.size());
    }

    @Test
    void noDuplicates() {
        assertEquals(
                new HashSet<>(FactoryStonecuttingBlocks.DECORATIVE_BLOCKS).size(),
                FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.size());
    }

    @Test
    void fanVariantsAreIncluded() {
        for (String fan : INCLUDED_FANS) {
            assertTrue(FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.contains(fan), fan + " must be included");
        }
    }

    @Test
    void mediumFanIsStillExcluded() {
        // 碰撞箱有问题，保持不可制作；用户明确要求不加入
        assertFalse(FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.contains(MEDIUM_FAN), "medium_fan must stay excluded");
    }

    @Test
    void fanSideIsIncluded() {
        // fan_side 虽名字带 fan，但是 Type.base 纯装饰（光滑金属），应包含
        assertTrue(FactoryStonecuttingBlocks.DECORATIVE_BLOCKS.contains("fan_side"));
    }

    /**
     * 表里每个方块都必须真的随 jar 发布一份切石机配方（生成物在 {@code src/generated/resources}，
     * 是 main resources 的 srcDir，所以从 classpath 读到的就是最终产物）。
     */
    @Test
    void everyIncludedBlockShipsAStonecuttingRecipe() throws IOException {
        for (String name : FactoryStonecuttingBlocks.DECORATIVE_BLOCKS) {
            JsonObject recipe = readGeneratedRecipe(name);
            String where = name + "_from_iron_block.json";

            assertEquals("minecraft:stonecutting", recipe.get("type").getAsString(), where);
            assertEquals(FactoryStonecuttingProvider.GROUP, recipe.get("group").getAsString(), where);
            assertEquals(
                    "minecraft:iron_block",
                    recipe.getAsJsonObject("ingredient").get("item").getAsString(),
                    where);

            JsonObject result = recipe.getAsJsonObject("result");
            assertEquals(4, result.get("count").getAsInt(), where);
            assertEquals("factory_blocks:" + name, result.get("id").getAsString(), where);

            JsonObject condition = recipe.getAsJsonArray("neoforge:conditions").get(0).getAsJsonObject();
            assertEquals("neoforge:mod_loaded", condition.get("type").getAsString(), where);
            assertEquals("factory_blocks", condition.get("modid").getAsString(), where);
        }
    }

    @Test
    void mediumFanShipsNoRecipe() {
        assertNull(
                readResource(MEDIUM_FAN + "_from_iron_block.json"),
                "medium_fan must stay uncraftable: no stonecutting recipe may ship for it");
    }

    private static JsonObject readGeneratedRecipe(String name) throws IOException {
        byte[] raw = readResource(name + "_from_iron_block.json");
        assertNotNull(raw, "missing generated recipe for " + name);
        return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static byte[] readResource(String fileName) {
        String path = "/data/dimblend_experience/recipe/stonecutting/" + fileName;
        try (InputStream in = FactoryStonecuttingProviderTest.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new AssertionError("failed to read " + path, e);
        }
    }
}
