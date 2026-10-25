package dimblend.experience.client.headlight;

/**
 * One lit lava top face: block coordinates and the fluid's own height above the block base
 * ({@code FluidState.getOwnHeight()}, {@code FluidState.java:53-55}). Lava amounts are 8/9 for a source
 * block and level/9 for flowing lava ({@code FlowingFluid.java:468-471}), which is the same height vanilla
 * renders the top face at ({@code FlowingFluid.java:463-466} returns 1.0 only when the same fluid sits above,
 * and such a block is not an exposed top face here).
 */
public record LavaSurface(int x, int y, int z, float height) {
    /** Center of the top face: the point the lit test samples. */
    public double centerX() {
        return this.x + 0.5;
    }

    public double centerY() {
        return (double) this.y + this.height;
    }

    public double centerZ() {
        return this.z + 0.5;
    }

    /**
     * World y the outline is drawn at: the fluid surface lifted by {@link HeadlightLavaMath#SURFACE_LIFT}
     * against z-fighting with the lava top face, which is drawn depth-writing in the solid chunk layer
     * ({@code ItemBlockRenderTypes.java:392-395} sends every fluid except water to {@code RenderType.solid()}).
     */
    public double outlineY() {
        // Double throughout: the fluid height is a float, the block y must not round in float arithmetic.
        return (double) this.y + this.height + HeadlightLavaMath.SURFACE_LIFT;
    }
}
