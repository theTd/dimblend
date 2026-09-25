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

    /**
     * Vanilla never registers {@code SpawnPlacements} for zoglin (they only
     * appear via hoglin conversion), so the default placement is
     * {@code NO_RESTRICTIONS}. Voidscape's own dimension patches that with an
     * ON_GROUND {@code PositionCheck}; rotating must do the same or zoglins
     * spawn in the 3D nether biome column's air and get swapped to falling hoglins.
     */
    public static boolean needsGroundPlacement(String namespace, String path) {
        return "minecraft".equals(namespace) && "zoglin".equals(path);
    }
}
