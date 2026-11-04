package dimblend.spyglass.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * World-wide spyglass settings. A SERVER config: the source filter is read on the server when a scan
 * is prepared, and NeoForge sends the file to clients on login. The panel edits it through
 * {@link SpyglassSourceService}; a manual file edit is picked up by the reloading listener, which
 * also forces the next scan to rebuild.
 *
 * <p>The stored set is the <b>disabled</b> namespaces, so a new datapack or mod starts enabled and no
 * migration is needed. {@code minecraft} is never stored: vanilla structures have no switch.
 */
public final class SpyglassServerSettings {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_SOURCES = BUILDER
            .comment("Namespaces whose whitelisted structures are not marked: no scan and no prediction.",
                    "The panel at /dbx tune -> Spyglass writes this list.",
                    "minecraft is always enabled and is never stored here.")
            .defineListAllowEmpty(
                    "disabledSources",
                    List.of(),
                    () -> "",
                    value -> value instanceof String string && SpyglassSourceFilter.isValidNamespace(string));

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Immutable set of disabled namespaces; empty while the config is not loaded. */
    public static Set<String> disabledSources() {
        if (!SPEC.isLoaded()) {
            return Set.of();
        }
        Set<String> disabled = new LinkedHashSet<>();
        for (Object value : DISABLED_SOURCES.get()) {
            if (value instanceof String namespace
                    && !"minecraft".equals(namespace)
                    && SpyglassSourceFilter.isValidNamespace(namespace)) {
                disabled.add(namespace);
            }
        }
        return Set.copyOf(disabled);
    }

    /** Replaces the stored set (sorted for a stable file) and saves it. */
    public static void setDisabledSources(Set<String> disabled) {
        List<String> sorted = new ArrayList<>(disabled.size());
        for (String namespace : disabled) {
            if (!"minecraft".equals(namespace) && SpyglassSourceFilter.isValidNamespace(namespace)) {
                sorted.add(namespace);
            }
        }
        Collections.sort(sorted);
        DISABLED_SOURCES.set(sorted);
        SPEC.save();
    }

    public static void onLoading(ModConfigEvent.Loading event) {
        // The first snapshot is built after this, so nothing to invalidate.
    }

    public static void onReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            // A hand-edited file is a source change too: the next poll must rebuild.
            SpyglassServerService.invalidateAll();
        }
    }

    public static void onUnloading(ModConfigEvent.Unloading event) {
        // Nothing cached: disabledSources() re-reads the spec and returns an empty set when unloaded.
    }

    private SpyglassServerSettings() {
    }
}
