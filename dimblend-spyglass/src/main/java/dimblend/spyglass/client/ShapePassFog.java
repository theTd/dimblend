package dimblend.spyglass.client;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * Applies the frame's real terrain fog to one structure pass and restores every fog value it changed
 * afterwards ({@link SpyglassShapeRenderer}).
 *
 * <p><b>Geometry.</b> Start, end and shape come from the last {@code FOG_TERRAIN} pass
 * ({@link SpyglassRevealState}). They have to be reinstalled rather than read from {@link RenderSystem}:
 * before the far pass the sky has replaced them with {@code FOG_SKY} values ({@code
 * LevelRenderer.java:947}, re-applied after the stars at {@code :1637}), and the terrain pass runs
 * only afterwards ({@code :949}). The vanilla terrain pass itself writes just these three values
 * ({@code FogRenderer.java:273-275}).
 *
 * <p><b>Color.</b> The RGB is what the vanilla terrain shader uses for this frame.
 * {@code FogRenderer.setupColor} computes fogRed/Green/Blue once per frame
 * ({@code FogRenderer.java:44-201}; NeoForge applies {@code ViewportEvent.ComputeFogColor} at the end,
 * NeoForge-patched {@code FogRenderer.java:200-204}), and {@code FogRenderer.levelFogColor()} installs
 * exactly that RGB with alpha 1. {@code LevelRenderer.renderLevel} calls it before the sky at {@code :941} and
 * {@code renderSky} repeats it at {@code :1559}. This class calls the same method when its pass
 * begins, so whatever the sky, entities or another renderer left in {@code RenderSystem} cannot
 * change the color. Nothing replaces the static RGB afterwards: {@code setupFog} only writes
 * start/end/shape ({@code FogRenderer.java:273-275}) and {@code setupNoFog} only writes the start
 * ({@code FogRenderer.java:203-205}). The three-argument setter keeps alpha 1
 * ({@code RenderSystem.java:382-384}), and {@code ShaderInstance.setDefaultUniforms} copies the array
 * into the {@code FogColor} uniform ({@code ShaderInstance.java:454-456}). At AFTER_SKY the sky has
 * just re-applied its own start/end/shape (and, in the normal sky path, the same level fog color at
 * {@code :1559}); at AFTER_PARTICLES the terrain pass has run and the clouds, which only call
 * {@code levelFogColor} again ({@code LevelRenderer.java:1712}), are drawn later in the frame
 * (NeoForge-patched {@code LevelRenderer.java:964} for AFTER_SKY,
 * {@code :1192}/{@code :1210} for AFTER_PARTICLES, clouds at {@code :1213-1219}). Fog setup itself
 * lives in {@code LevelRenderer.renderLevel} in 1.21.1; {@code GameRenderer.renderLevel} only computes
 * the render distance and calls it ({@code GameRenderer.java:1215-1261}, distance at {@code :1232}).
 *
 * <p><b>Strength.</b> The chunk shaders mix by {@code fogValue * FogColor.a}
 * ({@code assets/minecraft/shaders/include/fog.glsl}), so the per-mesh share from
 * {@link ShapeFogStrength} is written as the alpha of the shader's {@code FogColor} uniform, right
 * where the renderer writes {@code ChunkOffset}. The pass base keeps alpha 1, the vanilla level-fog
 * value, for any shader that does not take the per-mesh update. The alpha is set back to the base
 * after each mesh loop, like the {@code ChunkOffset} reset.
 *
 * <p>Reusable and allocation-free: one instance per call site, render thread only.
 */
final class ShapePassFog {
    /** Alpha of the vanilla level fog: {@code levelFogColor()} uses the three-argument setter. */
    static final float TERRAIN_ALPHA = 1.0F;

    private float savedStart;
    private float savedEnd;
    private float savedRed;
    private float savedGreen;
    private float savedBlue;
    private float savedAlpha;
    private FogShape savedShape = FogShape.SPHERE;
    private float red;
    private float green;
    private float blue;

    /**
     * Saves all current fog uniforms, then installs the recorded terrain planes/shape and keeps this
     * frame's fog color RGB with the vanilla alpha. Must be paired with {@link #end()}.
     */
    void begin(float terrainStart, float terrainEnd, FogShape terrainShape) {
        this.savedStart = RenderSystem.getShaderFogStart();
        this.savedEnd = RenderSystem.getShaderFogEnd();
        this.savedShape = RenderSystem.getShaderFogShape();
        float[] color = RenderSystem.getShaderFogColor();
        this.savedRed = color[0];
        this.savedGreen = color[1];
        this.savedBlue = color[2];
        this.savedAlpha = color[3];
        // Re-install the frame's level fog RGB with the same vanilla call the terrain pass uses; this
        // also resets alpha to 1, the value the three-argument setter uses.
        FogRenderer.levelFogColor();
        color = RenderSystem.getShaderFogColor();
        this.red = color[0];
        this.green = color[1];
        this.blue = color[2];
        RenderSystem.setShaderFogStart(terrainStart);
        RenderSystem.setShaderFogEnd(terrainEnd);
        RenderSystem.setShaderFogShape(terrainShape != null ? terrainShape : FogShape.SPHERE);
    }

    /** Puts back every fog uniform saved by {@link #begin(float, float, FogShape)}. */
    void end() {
        RenderSystem.setShaderFogStart(this.savedStart);
        RenderSystem.setShaderFogEnd(this.savedEnd);
        RenderSystem.setShaderFogColor(this.savedRed, this.savedGreen, this.savedBlue, this.savedAlpha);
        RenderSystem.setShaderFogShape(this.savedShape);
    }

    /**
     * Sets and uploads the {@code FogColor} alpha for one mesh, at the same point in the loop where
     * the renderer sets and uploads {@code ChunkOffset}.
     */
    void setStrength(ShaderInstance shader, float strength) {
        Uniform fogColor = shader.FOG_COLOR;
        if (fogColor != null) {
            fogColor.set(this.red, this.green, this.blue, strength);
            fogColor.upload();
        }
    }

    /**
     * Puts the {@code FogColor} uniform back at the terrain share after the mesh loop, like the
     * {@code ChunkOffset} reset. No upload: the shader is cleared right afterwards and every layer
     * uploads the uniform again through {@code setDefaultUniforms}.
     */
    void resetStrength(ShaderInstance shader) {
        Uniform fogColor = shader.FOG_COLOR;
        if (fogColor != null) {
            fogColor.set(this.red, this.green, this.blue, TERRAIN_ALPHA);
        }
    }
}
