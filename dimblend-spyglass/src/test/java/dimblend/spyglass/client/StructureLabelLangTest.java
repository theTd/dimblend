package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * The structure-name keys, the retained label keys and the measuring line stay consistent in both
 * shipped languages.
 *
 * <p>The name keys are <b>retained data</b>: the mark layer only draws {@code ?}/{@code !}, so no
 * code path reads them any more, but they are kept (like the keys of structures removed from the
 * whitelist) so re-adding a name later does not need a translation pass. The test therefore checks
 * the files, not a production consumer.
 */
class StructureLabelLangTest {
    private static final String TAG = "/data/dimblend_spyglass/tags/worldgen/structure/visible.json";
    private static final String EN_US = "/assets/dimblend_spyglass/lang/en_us.json";
    private static final String ZH_CN = "/assets/dimblend_spyglass/lang/zh_cn.json";
    /** Label chrome that is not a structure name, e.g. the retained "(not generated)" key. */
    private static final String LABEL_PREFIX = "dimblend_spyglass.label.";
    private static final String STRUCTURE_PREFIX = "dimblend_spyglass.structure.";
    private static final String PREDICTED_KEY = "dimblend_spyglass.label.not_generated";
    private static final String MEASURING_KEY = "dimblend_spyglass.label.measuring";
    private static final String TOOLTIP_PREFIX = "dimblend_spyglass.tooltip.";

    @Test
    void everyPlainVanillaEntryHasANameInEnglishAndChinese() throws IOException {
        Map<String, String> english = lang(EN_US);
        Map<String, String> chinese = lang(ZH_CN);
        List<ResourceLocation> vanilla = plainVanillaEntries();

        assertFalse(vanilla.isEmpty());
        for (ResourceLocation id : vanilla) {
            String key = structureKey(id);
            assertFalse(english.getOrDefault(key, "").isBlank(), "en_us is missing " + key);
            assertFalse(chinese.getOrDefault(key, "").isBlank(), "zh_cn is missing " + key);
            assertNotEquals(english.get(key), chinese.get(key), "zh_cn copies the English name of " + key);
        }
    }

    @Test
    void bothLanguagesShipTheSameKeysAndOnlyStructureOrLabelKeys() throws IOException {
        Map<String, String> english = lang(EN_US);
        Map<String, String> chinese = lang(ZH_CN);

        assertEquals(english.keySet(), chinese.keySet());
        for (String key : english.keySet()) {
            assertTrue(key.startsWith(STRUCTURE_PREFIX)
                    || key.startsWith(LABEL_PREFIX)
                    || key.startsWith(TOOLTIP_PREFIX), key);
        }
    }

    @Test
    void predictedLabelSuffixExistsInBothLanguages() throws IOException {
        // The suffix says an outline was replayed from the seed and has no blocks in the world yet;
        // without it a predicted marker would read as a real building.
        Map<String, String> english = lang(EN_US);
        Map<String, String> chinese = lang(ZH_CN);
        assertFalse(english.getOrDefault(PREDICTED_KEY, "").isBlank());
        assertFalse(chinese.getOrDefault(PREDICTED_KEY, "").isBlank());
        assertNotEquals(
                english.get(PREDICTED_KEY),
                chinese.get(PREDICTED_KEY));
    }

    @Test
    void measuringLabelExistsInBothLanguages() throws IOException {
        Map<String, String> english = lang(EN_US);
        Map<String, String> chinese = lang(ZH_CN);
        assertFalse(english.getOrDefault(MEASURING_KEY, "").isBlank(), "en_us is missing " + MEASURING_KEY);
        assertFalse(chinese.getOrDefault(MEASURING_KEY, "").isBlank(), "zh_cn is missing " + MEASURING_KEY);
        assertNotEquals(english.get(MEASURING_KEY), chinese.get(MEASURING_KEY));
    }

    @Test
    void variantsGetDistinctNames() throws IOException {
        for (String file : List.of(EN_US, ZH_CN)) {
            Map<String, String> names = lang(file);
            Set<String> seen = new HashSet<>();
            for (Map.Entry<String, String> entry : names.entrySet()) {
                if (!entry.getKey().startsWith(STRUCTURE_PREFIX)) {
                    continue; // label and tooltip keys are not structure names
                }
                assertTrue(seen.add(entry.getValue()), file + " repeats the name of " + entry.getKey());
            }
        }
    }

    private static String structureKey(ResourceLocation id) {
        return STRUCTURE_PREFIX + id.getNamespace() + "." + id.getPath().replace('/', '.');
    }

    private static List<ResourceLocation> plainVanillaEntries() throws IOException {
        List<ResourceLocation> ids = new ArrayList<>();
        for (JsonElement value : json(TAG).getAsJsonArray("values")) {
            if (value.isJsonPrimitive() && value.getAsString().startsWith("minecraft:")) {
                ids.add(ResourceLocation.parse(value.getAsString()));
            }
        }
        return ids;
    }

    private static Map<String, String> lang(String path) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : json(path).entrySet()) {
            entries.put(entry.getKey(), entry.getValue().getAsString());
        }
        return entries;
    }

    private static JsonObject json(String path) throws IOException {
        try (InputStream stream = StructureLabelLangTest.class.getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}
