package dimblend.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import twilightforest.client.TwilightForestRenderInfo;

/**
 * Dimension effects for the rotating dimension: vanilla overworld visuals by
 * default, Twilight Forest visuals while the camera samples a twilightforest
 * biome. Restores the twilight band's "biome shader" (perma-dusk starfield,
 * no sun/moon/sunset, TF fog curve, low-Y / dark-forest fog) that lives in
 * TF's {@code twilightforest:renderer} effects instance and is normally keyed
 * to TF's own dimension only.
 *
 * <p>The effects instance is a per-dimension singleton, so the band switch is
 * a per-frame predicate on the camera position's biome. Sky-disc and fog
 * colors already interpolate across the surface/twilight seam because vanilla
 * samples them from surrounding biomes; only the hard binary bits (stars,
 * sun/moon) flip once at the seam midline.</p>
 *
 * <p>Constructed with the same parameters TF uses for its own registration
 * (cloud level 128, SkyType.NONE, no forced/constant lightmap). The star
 * vertex buffer is uploaded once by TF's own registration path, which always
 * runs on the client because Twilight Forest is a required dependency.</p>
 */
public final class RotatingDimensionEffects extends DimensionSpecialEffects.OverworldEffects {

    /** Clouds sit 128 above original TF sea (0); after +64 lift they stay 128 above sea 64. */
    private final TwilightForestRenderInfo twilight = new TwilightForestRenderInfo(
            192.0F, false, DimensionSpecialEffects.SkyType.NONE, false, false);

    @Override
    @Nullable
    public float[] getSunriseColor(float daycycle, float partialTicks) {
        if (inTwilightZone()) {
            return twilight.getSunriseColor(daycycle, partialTicks);
        }
        return super.getSunriseColor(daycycle, partialTicks);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 biomeFogColor, float daylight) {
        if (inTwilightZone()) {
            return twilight.getBrightnessDependentFogColor(biomeFogColor, daylight);
        }
        return super.getBrightnessDependentFogColor(biomeFogColor, daylight);
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return inTwilightZone() && twilight.isFoggyAt(x, y);
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix,
            Camera camera, Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        if (inTwilightZone()) {
            return twilight.renderSky(level, ticks, partialTick, modelViewMatrix, camera,
                    projectionMatrix, isFoggy, setupFog);
        }
        return false;
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick,
            LightTexture lightTexture, double camX, double camY, double camZ) {
        return inTwilightZone() && twilight.renderSnowAndRain(level, ticks, partialTick, lightTexture, camX, camY, camZ);
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return inTwilightZone() && twilight.tickRain(level, ticks, camera);
    }

    /**
     * True while the main camera samples a twilightforest-namespace biome.
     * Biome lookups are the same signal the sky/fog color pipelines use, so
     * the binary switch lands exactly where the color interpolation crosses.
     */
    private static boolean inTwilightZone() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel level)) {
            return false;
        }
        return isTwilightBiome(level, minecraft.gameRenderer.getMainCamera().getBlockPosition());
    }

    static boolean isTwilightBiome(ClientLevel level, BlockPos pos) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location().getNamespace().equals("twilightforest"))
                .orElse(false);
    }
}
