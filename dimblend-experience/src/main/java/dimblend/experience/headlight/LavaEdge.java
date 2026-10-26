package dimblend.experience.headlight;

/** One perimeter segment of a lit lava top face in world coordinates (endpoints already at {@link LavaSurface#outlineY()}). */
public record LavaEdge(double x1, double y1, double z1, double x2, double y2, double z2) {
}
