package dimblend.spyglass.server;

import dimblend.spyglass.api.SourceCatalog;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;

/**
 * The one place the whitelist meets the source toggle. A structure is kept unless its namespace is
 * disabled; the {@code minecraft} namespace is always kept, because vanilla structures have no
 * switch (the panel does not list them either).
 *
 * <p>Pure functions: the scan thread and the tests both call this without touching a config file.
 */
public final class SpyglassSourceFilter {
    /** True when this structure survives the source filter. */
    public static boolean keep(ResourceLocation id, Predicate<String> disabled) {
        return "minecraft".equals(id.getNamespace()) || !disabled.test(id.getNamespace());
    }

    /**
     * A namespace as accepted by a mod id and by our config / wire format: non-empty, at most
     * {@value SourceCatalog.Source#MAX_NAMESPACE_LENGTH} characters, and only {@code [a-z0-9_.-]}.
     * A path separator, a colon or whitespace is not a namespace.
     */
    public static boolean isValidNamespace(@Nullable String namespace) {
        if (namespace == null || namespace.isEmpty()
                || namespace.length() > SourceCatalog.Source.MAX_NAMESPACE_LENGTH) {
            return false;
        }
        for (int i = 0; i < namespace.length(); i++) {
            char c = namespace.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private SpyglassSourceFilter() {
    }
}
