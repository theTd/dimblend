package dimblend.experience.advancement;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancementDatapackOverrideTest {

    private static final List<String> ORIG_DIMS = List.of(
            "overworld",
            "nether",
            "end",
            "aether",
            "twilight_forest",
            "starlight",
            "otherside",
            "voidscape"
    );

    private static final List<String> CONVERTED_ADVANCEMENTS = List.of(
            "data/minecraft/advancement/nether/root.json",
            "data/minecraft/advancement/story/enter_the_nether.json",
            "data/kaleidoscope_dim_wine/advancement/nether_enter.json",
            "data/minecraft/advancement/end/root.json",
            "data/minecraft/advancement/story/enter_the_end.json",
            "data/kaleidoscope_dim_wine/advancement/the_end_enter.json",
            "data/aether/advancement/the_aether.json",
            "data/aether/advancement/enter_aether.json",
            "data/kaleidoscope_dim_wine/advancement/aether_enter.json",
            "data/twilightforest/advancement/root.json",
            "data/kaleidoscope_dim_wine/advancement/twilightforest_enter.json",
            "data/eternal_starlight/advancement/root.json",
            "data/eternal_starlight/advancement/enter_starlight.json",
            "data/deeperdarker/advancement/main/enter_otherside.json",
            "data/voidscape/advancement/root.json",
            "data/aether/advancement/aether_sleep.json",
            "data/twilightforest/advancement/twilight_hunter.json",
            "data/voidscape/advancement/psychosis.json",
            "data/minecraft/advancement/nether/ride_strider_in_overworld_lava.json",
            "data/minecraft/advancement/nether/uneasy_alliance.json"
    );

    private static final List<String> BUMBLEZONE_HIDE_ADVANCEMENTS = List.of(
            "data/kaleidoscope_dim_wine/advancement/the_bumblezone_enter.json",
            "data/kaleidoscope_dim_wine/advancement/the_bumblezone_carbocretin_powder.json",
            "data/kaleidoscope_dim_wine/advancement/the_bumblezone_mead_essence.json",
            "data/kaleidoscope_dim_wine/advancement/the_bumblezone_sugar_water_bucket.json",
            "data/kaleidoscope_dim_wine/advancement/the_bumblezone_wine.json"
    );

    @Test
    void origDimBiomeTagsExistAndMarkModEntriesOptional() throws IOException {
        for (String dim : ORIG_DIMS) {
            String path = "data/dimblend_experience/tags/worldgen/biome/orig_dim/" + dim + ".json";
            JsonObject tag = readResourceJson(path);
            assertFalse(tag.get("replace").getAsBoolean(), path + " must keep replace=false");
            JsonArray values = tag.getAsJsonArray("values");
            assertNotNull(values, path + " missing values");
            assertTrue(values.size() > 0, path + " must have at least one entry");
            for (JsonElement entry : values) {
                if (entry.isJsonPrimitive()) {
                    String id = entry.getAsString();
                    assertTrue(
                            id.startsWith("minecraft:") || id.startsWith("#minecraft:"),
                            path + " non-vanilla bare entry without required=false: " + id
                    );
                } else {
                    JsonObject obj = entry.getAsJsonObject();
                    assertTrue(obj.has("id"), path + " entry missing id");
                    assertFalse(obj.get("required").getAsBoolean(), path + " modded entry must set required=false");
                }
            }
        }
    }

    @Test
    void convertedAdvancementsReplaceDimensionChecksWithBiomeTagsAndGuardModNamespaces() throws IOException {
        Set<String> validBiomeTags = new HashSet<>();
        for (String dim : ORIG_DIMS) {
            validBiomeTags.add("#dimblend_experience:orig_dim/" + dim);
        }

        for (String resourcePath : CONVERTED_ADVANCEMENTS) {
            JsonObject root = readResourceJson(resourcePath);
            String namespace = namespaceOf(resourcePath);
            if (!"minecraft".equals(namespace)) {
                assertHasModLoadedCondition(resourcePath, root, namespace);
            }

            JsonObject criteria = root.getAsJsonObject("criteria");
            assertNotNull(criteria, resourcePath + " missing criteria");
            assertTrue(criteria.size() > 0, resourcePath + " must define criteria");

            int biomeChecks = countBiomePredicatesAndForbidDimensionChecks(resourcePath, criteria, validBiomeTags);
            assertTrue(biomeChecks >= 1, resourcePath + " must contain at least one biome location predicate");
        }
    }

    @Test
    void bumblezoneDeadBranchAdvancementsRequireBothDimWineAndBumblezone() throws IOException {
        for (String resourcePath : BUMBLEZONE_HIDE_ADVANCEMENTS) {
            JsonObject root = readResourceJson(resourcePath);
            assertHasModLoadedCondition(resourcePath, root, "kaleidoscope_dim_wine");
            assertHasModLoadedCondition(resourcePath, root, "the_bumblezone");
        }
    }

    private static int countBiomePredicatesAndForbidDimensionChecks(
            String resourcePath,
            JsonElement element,
            Set<String> validBiomeTags
    ) {
        int count = 0;
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("trigger")) {
                String trigger = obj.get("trigger").getAsString();
                assertFalse(
                        "minecraft:changed_dimension".equals(trigger),
                        resourcePath + " still uses minecraft:changed_dimension"
                );
            }
            if (obj.has("location") && obj.get("location").isJsonObject()) {
                JsonObject loc = obj.getAsJsonObject("location");
                assertFalse(loc.has("dimension"), resourcePath + " still locks location.dimension");
                if (loc.has("biomes")) {
                    String tag = loc.get("biomes").getAsString();
                    assertTrue(validBiomeTags.contains(tag), resourcePath + " references unknown biome tag " + tag);
                    count++;
                }
            }
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                count += countBiomePredicatesAndForbidDimensionChecks(resourcePath, entry.getValue(), validBiomeTags);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                count += countBiomePredicatesAndForbidDimensionChecks(resourcePath, item, validBiomeTags);
            }
        }
        return count;
    }

    private static void assertHasModLoadedCondition(String resourcePath, JsonObject root, String expectedModId) {
        JsonArray conditions = root.getAsJsonArray("neoforge:conditions");
        assertNotNull(conditions, resourcePath + " missing neoforge:conditions");
        boolean found = false;
        for (JsonElement cond : conditions) {
            JsonObject c = cond.getAsJsonObject();
            if ("neoforge:mod_loaded".equals(c.get("type").getAsString())
                    && expectedModId.equals(c.get("modid").getAsString())) {
                found = true;
                break;
            }
        }
        assertTrue(found, resourcePath + " missing neoforge:mod_loaded for " + expectedModId);
    }

    private static String namespaceOf(String resourcePath) {
        String[] parts = resourcePath.split("/");
        assertEquals("data", parts[0]);
        return parts[1];
    }

    private static JsonObject readResourceJson(String resourcePath) throws IOException {
        try (InputStream stream = AdvancementDatapackOverrideTest.class.getClassLoader().getResourceAsStream(resourcePath)) {
            assertNotNull(stream, "Missing classpath resource: " + resourcePath);
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        }
    }
}
