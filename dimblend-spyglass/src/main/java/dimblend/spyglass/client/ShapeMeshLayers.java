package dimblend.spyglass.client;

import java.util.List;
import net.minecraft.client.renderer.RenderType;

/**
 * The chunk render layers a structure mesh is split into, in vanilla's draw order.
 *
 * <p>{@link RenderType#chunkBufferLayers()} is the list vanilla compiles sections into —
 * solid, cutout_mipped, cutout, translucent, tripwire ({@code RenderType.java:745}) — and
 * {@code LevelRenderer.renderLevel} draws them in that order (solid/cutout_mipped/cutout at
 * {@code LevelRenderer.java:955-957}, translucent and tripwire after entities at
 * {@code :1143-1145}/{@code :1158-1162}). Tripwire is a chunk
 * layer of its own (jungle temple traps), so it is kept instead of being dropped.
 */
final class ShapeMeshLayers {
    private ShapeMeshLayers() {
    }

    static List<RenderType> order() {
        return RenderType.chunkBufferLayers();
    }

    static int count() {
        return order().size();
    }

    /** Layer index of a chunk render type, or -1 for a type that is not a chunk layer. */
    static int indexOf(RenderType type) {
        List<RenderType> order = order();
        for (int i = 0; i < order.size(); i++) {
            if (order.get(i) == type) {
                return i;
            }
        }
        return -1;
    }

    /** Translucent quads are sorted back to front once, when the mesh is built. */
    static boolean isSorted(int layer) {
        return order().get(layer) == RenderType.translucent();
    }

    /** Blended layers are drawn farthest shape first; opaque layers nearest first. */
    static boolean isBlended(int layer) {
        RenderType type = order().get(layer);
        return type == RenderType.translucent() || type == RenderType.tripwire();
    }
}
