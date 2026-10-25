package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StructureLabelNamesTest {
    @Test
    void buildsTheTranslationKeyFromNamespaceAndDottedPath() {
        assertEquals("dimblend_spyglass.structure.minecraft.ancient_city",
                StructureLabelNames.translationKey(ResourceLocation.parse("minecraft:ancient_city")));
        assertEquals("dimblend_spyglass.structure.terralith.underground.witch_hut",
                StructureLabelNames.translationKey(ResourceLocation.parse("terralith:underground/witch_hut")));
    }

    @Test
    void fallsBackToATitleCasedPath() {
        assertEquals("Illager Fort", fallback("dungeons_arise:illager_fort"));
        assertEquals("Underground Witch Hut", fallback("terralith:underground/witch_hut"));
        assertEquals("Village Swamp", fallback("biomeswevegone:village/swamp"));
        assertEquals("Big 1", fallback("create_easy_structures:big_1"));
        assertEquals("Saloon", fallback("saloon:saloon"));
    }

    @Test
    void collapsesRunsOfSeparators() {
        assertEquals("Odd Path Name", fallback("test:_odd__path//name_"));
    }

    @Test
    void usesTheFullIdWhenThePathHasNoWord() {
        assertEquals("test:_/_", fallback("test:_/_"));
    }

    @Test
    void prefersTheTranslationWhenTheLanguageHasTheKey() {
        Map<String, String> language = Map.of(
                "dimblend_spyglass.structure.minecraft.ancient_city", "远古城市");

        assertEquals("远古城市", resolve("minecraft:ancient_city", language));
        assertEquals("Illager Fort", resolve("dungeons_arise:illager_fort", language));
    }

    private static String fallback(String id) {
        return StructureLabelNames.fallbackName(ResourceLocation.parse(id));
    }

    private static String resolve(String id, Map<String, String> language) {
        return StructureLabelNames.resolve(ResourceLocation.parse(id), language::containsKey, language::get);
    }
}
