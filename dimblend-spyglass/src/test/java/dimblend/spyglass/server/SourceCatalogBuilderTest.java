package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.api.SourceCatalog;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SourceCatalogBuilderTest {
    private static final Function<String, String> NO_MODS = namespace -> null;

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    @Test
    void extractsNamespacesDeduplicatesSortsAndDropsMinecraft() {
        List<SourceCatalog.Source> sources = SourceCatalogBuilder.build(
                List.of(
                        id("terralith", "x"),
                        id("minecraft", "desert_pyramid"),
                        id("terralith", "y"),
                        id("aether", "bronze_dungeon"),
                        id("aether", "silver_dungeon")),
                namespace -> false,
                NO_MODS);

        assertEquals(
                List.of("aether", "terralith"),
                sources.stream().map(SourceCatalog.Source::namespace).toList());
    }

    @Test
    void marksTheCorrespondingModFromTheGivenLookupAndFallsBackToTheNamespace() {
        List<SourceCatalog.Source> sources = SourceCatalogBuilder.build(
                List.of(id("terralith", "x"), id("aether", "bronze_dungeon")),
                namespace -> false,
                namespace -> namespace.equals("terralith") ? "Terralith" : null);

        SourceCatalog.Source aether = sources.get(0);
        assertEquals("aether", aether.namespace());
        assertEquals("aether", aether.displayName());
        assertFalse(aether.correspondingModLoaded());

        SourceCatalog.Source terralith = sources.get(1);
        assertEquals("terralith", terralith.namespace());
        assertEquals("Terralith", terralith.displayName());
        assertTrue(terralith.correspondingModLoaded());
    }

    @Test
    void enabledReflectsTheDisabledSet() {
        List<SourceCatalog.Source> sources = SourceCatalogBuilder.build(
                List.of(id("terralith", "x"), id("aether", "bronze_dungeon")),
                Set.of("terralith")::contains,
                NO_MODS);

        // Sorted by namespace: aether first, terralith second.
        assertTrue(sources.get(0).enabled());
        assertFalse(sources.get(1).enabled());
    }

    @Test
    void emptyInputYieldsAnEmptyList() {
        assertTrue(SourceCatalogBuilder.build(List.of(), namespace -> false, NO_MODS).isEmpty());
    }
}
