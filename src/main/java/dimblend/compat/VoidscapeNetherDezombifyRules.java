package dimblend.compat;

import javax.annotation.Nullable;

/**
 * Minecraft-free lookup table for {@link VoidscapeNetherDezombify}.
 * Voidscape's nether biome JSON spawns already-zombified nether mobs;
 * rotating (piglin-safe) should spawn the living forms instead.
 */
public final class VoidscapeNetherDezombifyRules {
    public static final String VOIDSCAPE_NAMESPACE = "voidscape";
    public static final String NETHER_PATH = "nether";

    private VoidscapeNetherDezombifyRules() {
    }

    public static boolean isVoidscapeNether(String namespace, String path) {
        return VOIDSCAPE_NAMESPACE.equals(namespace) && NETHER_PATH.equals(path);
    }

    @Nullable
    public static String replacementPath(String namespace, String path) {
        if (!"minecraft".equals(namespace) || path == null) {
            return null;
        }
        return switch (path) {
            case "zombified_piglin" -> "piglin";
            case "zoglin" -> "hoglin";
            default -> null;
        };
    }
}
