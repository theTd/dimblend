package dimblend.spyglass.server;

import dimblend.spyglass.api.SourceCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/**
 * Turns the whitelist's structure ids into the panel's source rows.
 *
 * <p>Only namespaces that actually resolved at least one whitelisted structure appear, so the panel
 * never offers a switch that would do nothing. {@code minecraft} is removed: vanilla structures are
 * always on. Rows are sorted by namespace, so the list is stable across scans.
 *
 * <p>Pure: the display-name lookup is a function, which keeps this testable without a mod list.
 */
public final class SourceCatalogBuilder {
    /**
     * @param ids              structure ids from the resolved whitelist tag
     * @param disabled         namespaces the player turned off
     * @param displayNameLookup mod display name for a namespace, or {@code null} when there is no mod
     */
    public static List<SourceCatalog.Source> build(
            List<ResourceLocation> ids,
            Predicate<String> disabled,
            Function<String, String> displayNameLookup) {
        TreeSet<String> namespaces = new TreeSet<>();
        for (ResourceLocation id : ids) {
            if (id != null && !"minecraft".equals(id.getNamespace())) {
                namespaces.add(id.getNamespace());
            }
        }
        List<SourceCatalog.Source> sources = new ArrayList<>(namespaces.size());
        for (String namespace : namespaces) {
            String displayName = displayNameLookup.apply(namespace);
            boolean modLoaded = displayName != null;
            sources.add(new SourceCatalog.Source(
                    namespace,
                    modLoaded ? displayName : namespace,
                    !disabled.test(namespace),
                    modLoaded));
        }
        return List.copyOf(sources);
    }

    private SourceCatalogBuilder() {
    }
}
