package dimblend.experience.compat.yat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.experience.compat.yat.EdibleThirstRuleEngine.Kind;
import dimblend.experience.compat.yat.EdibleThirstRuleEngine.Rule;
import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Pins the bundled edible thirst table (batch 1118, I01/I02) and the loader that enforces it.
 * Pure unit test: no game bootstrap needed.
 */
class EdibleThirstOverridesTest {

    private static final Pattern RESOURCE_LOCATION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    @Test
    void bundledTableLoadsWithMoreThanOneHundredEightyEntries() {
        EdibleThirstRuleEngine.Table table = EdibleThirstRuleEngine.loadBundled();
        assertTrue(table.size() > 180, "覆盖表应有 180 项以上，实际 " + table.size());
    }

    @Test
    void bundledTableHasUniqueAndWellFormedItemIds() {
        EdibleThirstRuleEngine.Table table = EdibleThirstRuleEngine.loadBundled();
        Set<String> ids = new HashSet<>();
        for (EdibleThirstRuleEngine.Entry entry : table.entries()) {
            assertTrue(RESOURCE_LOCATION.matcher(entry.item()).matches(),
                    "非法 ResourceLocation: " + entry.item());
            assertTrue(ids.add(entry.item()), "重复物品 ID: " + entry.item());
        }
        assertEquals(table.size(), ids.size());
    }

    @Test
    void ruleValuesMatchTheAgreedNumbers() {
        assertEquals(6, EdibleThirstRuleEngine.thirstFor(Kind.DRINK, Rule.A));
        assertEquals(8, EdibleThirstRuleEngine.quenchedFor(Kind.DRINK, Rule.A));
        assertEquals(4, EdibleThirstRuleEngine.thirstFor(Kind.FOOD, Rule.A));
        assertEquals(5, EdibleThirstRuleEngine.quenchedFor(Kind.FOOD, Rule.A));
        assertEquals(3, EdibleThirstRuleEngine.thirstFor(Kind.DRINK, Rule.B));
        assertEquals(4, EdibleThirstRuleEngine.quenchedFor(Kind.DRINK, Rule.B));
        assertEquals(1, EdibleThirstRuleEngine.thirstFor(Kind.FOOD, Rule.C));
        assertEquals(1, EdibleThirstRuleEngine.quenchedFor(Kind.FOOD, Rule.C));
    }

    @Test
    void everyEntryCarriesItsRuleValue() {
        for (EdibleThirstRuleEngine.Entry entry : EdibleThirstRuleEngine.loadBundled().entries()) {
            assertEquals(EdibleThirstRuleEngine.thirstFor(entry.kind(), entry.rule()), entry.thirst(),
                    entry.item() + " 的 thirst 与规则不符");
            assertEquals(EdibleThirstRuleEngine.quenchedFor(entry.kind(), entry.rule()), entry.quenched(),
                    entry.item() + " 的 quenched 与规则不符");
        }
    }

    @Test
    void waterDrinksUseRuleA() {
        assertEntry("minecraft:potion", Kind.DRINK, Rule.A);
        assertEntry("create:builders_tea", Kind.DRINK, Rule.A);
        assertEntry("cold_sweat:filled_waterskin", Kind.DRINK, Rule.A);
        assertEntry("kaleidoscope_world_liquor:tonic_water", Kind.DRINK, Rule.A);
        // 茶壶配方注水（tea_fluid = minecraft:water），原版扫描漏掉了这些数据驱动物品
        assertEntry("kaleidoscope_cookery:barley_tea", Kind.DRINK, Rule.A);
        assertEntry("kaleidoscope_cookery:oolong", Kind.DRINK, Rule.A);
        assertEntry("kaleidoscope_end:chorus_flower_tea", Kind.DRINK, Rule.A);
    }

    @Test
    void stockpotSoupsCountWaterBecauseSoupBaseDefaultsToWater() {
        // kaleidoscope_cookery 的 stockpot/flex_stockpot 不写 soup_base 时默认就是水
        assertEntry("kaleidoscope_cookery:borscht", Kind.FOOD, Rule.A);
        assertEntry("kaleidoscope_cookery:beef_noodle", Kind.FOOD, Rule.A);
        assertEntry("kaleidoscope_cookery:cooked_rice", Kind.FOOD, Rule.A);
        assertEquals(4, EdibleThirstRuleEngine.loadBundled().get("kaleidoscope_cookery:borscht").thirst());
        assertEquals(5, EdibleThirstRuleEngine.loadBundled().get("kaleidoscope_cookery:borscht").quenched());
    }

    @Test
    void fruitsAndFruitFoodsUseRuleC() {
        assertEntry("minecraft:apple", Kind.FOOD, Rule.C);
        assertEntry("minecraft:melon_slice", Kind.FOOD, Rule.C);
        assertEntry("minecraft:glow_berries", Kind.FOOD, Rule.C);
        assertEntry("minecraft:sweet_berries", Kind.FOOD, Rule.C);
        assertEntry("minecraft:chorus_fruit", Kind.FOOD, Rule.C);
        assertEntry("kaleidoscope_tavern:grape", Kind.FOOD, Rule.C);
        // 有水果配料的加工品同样算 C（水果派、水果蛋糕、果汁冰淇淋等）
        assertEntry("biomeswevegone:green_apple_pie", Kind.FOOD, Rule.C);
        assertEntry("biomeswevegone:blueberry_pie", Kind.FOOD, Rule.C);
        assertEntry("atmospheric:passion_fruit_tart", Kind.FOOD, Rule.C);
        assertEntry("create:honeyed_apple", Kind.FOOD, Rule.C);
    }

    @Test
    void fruitDrinksWithoutWaterUseRuleB() {
        assertEntry("kaleidoscope_tavern:wine", Kind.DRINK, Rule.B);
        assertEntry("kaleidoscope_tavern:sweet_berry_wine", Kind.DRINK, Rule.B);
        assertEntry("kaleidoscope_dim_wine:chorus_cognac", Kind.DRINK, Rule.B);
        assertEntry("dndesires:strawberry_milkshake", Kind.DRINK, Rule.B);
        assertEntry("dndesires:glowberry_milkshake", Kind.DRINK, Rule.B);
    }

    @Test
    void unmatchedEdiblesAreNotOverridden() {
        // 既没有水也没有水果：不在表内，保留 YAT 配置值（未配置则保持无值）
        List<String> untouched = List.of(
                "minecraft:cooked_beef",
                "minecraft:beef",
                "minecraft:bread",
                "minecraft:milk_bucket",
                "minecraft:honey_bottle",
                "minecraft:potato",
                "minecraft:carrot",
                "minecraft:beetroot",
                "minecraft:cookie",
                "minecraft:pumpkin_pie",
                "kaleidoscope_cookery:baozi",
                "kaleidoscope_cookery:mantou");
        EdibleThirstRuleEngine.Table table = EdibleThirstRuleEngine.loadBundled();
        for (String id : untouched) {
            assertFalse(table.contains(id), id + " 未命中 A/B/C，不应出现在覆盖表中");
        }
    }

    @Test
    void loaderRejectsDuplicateEntries() {
        String json = table(
                entry("minecraft:apple", "food", "C", 1, 1),
                entry("minecraft:apple", "food", "C", 1, 1));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EdibleThirstRuleEngine.load(new StringReader(json)));
        assertTrue(error.getMessage().contains("Duplicate") || error.getMessage().contains("重复"),
                error.getMessage());
    }

    @Test
    void loaderRejectsValuesThatContradictTheRule() {
        String json = table(entry("minecraft:apple", "food", "C", 2, 2));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> EdibleThirstRuleEngine.load(new StringReader(json)));
        assertTrue(error.getMessage().contains("minecraft:apple"), error.getMessage());
    }

    @Test
    void loaderRejectsRuleAndKindMismatch() {
        assertThrows(IllegalArgumentException.class,
                () -> EdibleThirstRuleEngine.load(new StringReader(table(entry("minecraft:apple", "food", "B", 3, 4)))));
        assertThrows(IllegalArgumentException.class,
                () -> EdibleThirstRuleEngine.load(new StringReader(table(entry("minecraft:potion", "drink", "C", 1, 1)))));
    }

    @Test
    void loaderRejectsInvalidItemIdsAndUnknownEnums() {
        assertThrows(IllegalArgumentException.class,
                () -> EdibleThirstRuleEngine.load(new StringReader(table(entry("Minecraft:Apple", "food", "C", 1, 1)))));
        assertThrows(IllegalArgumentException.class,
                () -> EdibleThirstRuleEngine.load(new StringReader(table("{\"item\": \"minecraft:apple\", \"kind\": \"food\"}"))));
        assertThrows(IllegalArgumentException.class,
                () -> EdibleThirstRuleEngine.load(new StringReader(table(entry("minecraft:apple", "snack", "C", 1, 1)))));
        assertThrows(IllegalArgumentException.class,
                () -> EdibleThirstRuleEngine.load(new StringReader("{\"format\": 2, \"entries\": []}")));
    }

    private static void assertEntry(String item, Kind kind, Rule rule) {
        EdibleThirstRuleEngine.Entry entry = EdibleThirstRuleEngine.loadBundled().get(item);
        assertNotNull(entry, item + " 未出现在覆盖表中");
        assertEquals(kind, entry.kind(), item + " 的 kind");
        assertEquals(rule, entry.rule(), item + " 的 rule");
        assertEquals(EdibleThirstRuleEngine.thirstFor(kind, rule), entry.thirst(), item + " 的 thirst");
        assertEquals(EdibleThirstRuleEngine.quenchedFor(kind, rule), entry.quenched(), item + " 的 quenched");
    }

    private static String table(String... entries) {
        return "{\"format\": 1, \"entries\": [" + String.join(",", entries) + "]}";
    }

    private static String entry(String item, String kind, String rule, int thirst, int quenched) {
        return "{\"item\": \"" + item + "\", \"kind\": \"" + kind + "\", \"rule\": \"" + rule
                + "\", \"thirst\": " + thirst + ", \"quenched\": " + quenched + "}";
    }
}
