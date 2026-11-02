package dimblend.spyglass.api;

import java.util.List;

/**
 * The source toggle panel's data: which namespaces can be chosen, whether each is on, and whether a
 * corresponding mod was found. Pure data, built on the server, immutable.
 *
 * <p>Contract: {@code docs/design/api/experience-spyglass.md} §3.1. {@code sources} never contains
 * {@code "minecraft"} and is sorted by namespace without duplicates; {@code message} is a status
 * <b>key</b>, not human text.
 */
public record SourceCatalog(List<Source> sources, boolean editable, String message) {

    public SourceCatalog {
        sources = List.copyOf(sources);
        message = message == null ? "" : message;
    }

    /** One row's data. */
    public record Source(String namespace, String displayName, boolean enabled, boolean correspondingModLoaded) {
        /** Wire limit of the namespace string; also refused here so a bad row cannot be built. */
        public static final int MAX_NAMESPACE_LENGTH = 64;

        public Source {
            if (namespace == null || namespace.isEmpty()) {
                throw new IllegalArgumentException("Source namespace must not be empty");
            }
            if (namespace.length() > MAX_NAMESPACE_LENGTH) {
                throw new IllegalArgumentException("Source namespace is longer than " + MAX_NAMESPACE_LENGTH);
            }
            displayName = displayName == null || displayName.isBlank() ? namespace : displayName;
        }
    }

    /** Empty catalog: the panel uses it when spyglass is absent or the read failed, avoiding null. */
    public static SourceCatalog empty(String message) {
        return new SourceCatalog(List.of(), false, message);
    }
}