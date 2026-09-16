package dimblend.client;

import cn.leolezury.eternalstarlight.common.client.ESDimensionSpecialEffects;
import cn.leolezury.eternalstarlight.common.client.renderer.world.ESSkyRenderer;
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
 * biome, Eternal Starlight sky while the camera samples an eternal_starlight
 * biome, and vanilla End sky while the player stands in an End band.
 *
 * <p>Restores the twilight band's "biome shader" (perma-dusk starfield, no
 * sun/moon/sunset, TF fog curve, low-Y / dark-forest fog) that lives in TF's
 * {@code twilightforest:renderer} effects instance and is normally keyed to
 * TF's own dimension only. Fog color is that instance's
 * {@code getBrightnessDependentFogColor} only — TF 1.21.1 has no extra
 * FogHandler color pass. Starlight bands delegate {@link ESSkyRenderer}
 * (dead star, custom starfield, {@link SkyType#NONE}) the same way; time lock
 * 14000 still applies to day-cycle reads, not the celestial pose (ES pins the
 * dead star at 12500). End bands flip {@link DimensionSpecialEffects.SkyType#END}
 * so vanilla {@code renderEndSky} draws {@code end_sky.png} instead of the overworld
 * sun/moon/stars; time lock 18000 still applies to day-cycle reads, not the
 * skybox.</p>
 *
 * <p>The effects instance is a per-dimension singleton, so the band switch is
 * a per-frame predicate. Twilight and Starlight key off camera biome so the
 * binary bits (stars, sun/moon / dead star) flip on the same seam the sky-disc
 * color already interpolates. End keys off {@link ClientBandLane} because End
 * bands meet neighbors at a partition wall, not a biome blend.</p>
 *
 * <p>Constructed with the same parameters TF uses for its own registration
 * (cloud level 128, SkyType.NONE, no forced/constant lightmap). The star
 * vertex buffer is uploaded once by TF's own registration path, which always
 * runs on the client because Twilight Forest is a required dependency.
 * Eternal Starlight is likewise required; {@link ESSkyRenderer} builds its
 * own star buffer on first use.</p>
 */
public final class RotatingDimensionEffects extends DimensionSpecialEffects.OverworldEffects {

    /** Clouds sit 128 above original TF sea (0); after +64 lift they stay 128 above sea 64. */
    private final TwilightForestRenderInfo twilight = new TwilightForestRenderInfo(
            192.0F, false, DimensionSpecialEffects.SkyType.NONE, false, false);
    private final DimensionSpecialEffects starlight = new ESDimensionSpecialEffects(
            160.0F, false, DimensionSpecialEffects.SkyType.NONE, false, false);
    private final DimensionSpecialEffects.EndEffects end = new DimensionSpecialEffects.EndEffects();

    @Override
    public DimensionSpecialEffects.SkyType skyType() {
        DimensionSpecialEffects overlay = overlayEffects();
        return overlay != null ? overlay.skyType() : super.skyType();
    }

    @Override
    public float getCloudHeight() {
        DimensionSpecialEffects overlay = overlayEffects();
        return overlay != null ? overlay.getCloudHeight() : super.getCloudHeight();
    }

    @Override
    public boolean hasGround() {
        DimensionSpecialEffects overlay = overlayEffects();
        return overlay != null ? overlay.hasGround() : super.hasGround();
    }

    @Override
    public boolean forceBrightLightmap() {
        DimensionSpecialEffects overlay = overlayEffects();
        return overlay != null ? overlay.forceBrightLightmap() : super.forceBrightLightmap();
    }

    @Override
    @Nullable
    public float[] getSunriseColor(float daycycle, float partialTicks) {
        DimensionSpecialEffects overlay = overlayEffects();
        return overlay != null ? overlay.getSunriseColor(daycycle, partialTicks) : super.getSunriseColor(daycycle, partialTicks);
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 biomeFogColor, float daylight) {
        DimensionSpecialEffects overlay = overlayEffects();
        return overlay != null
                ? overlay.getBrightnessDependentFogColor(biomeFogColor, daylight)
                : super.getBrightnessDependentFogColor(biomeFogColor, daylight);
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        DimensionSpecialEffects overlay = overlayEffects();
        return overlay != null ? overlay.isFoggyAt(x, y) : super.isFoggyAt(x, y);
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, Matrix4f modelViewMatrix,
            Camera camera, Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog) {
        return switch (currentOverlay()) {
            case TWILIGHT -> twilight.renderSky(level, ticks, partialTick, modelViewMatrix, camera,
                    projectionMatrix, isFoggy, setupFog);
            case STARLIGHT -> ESSkyRenderer.renderSky(level, modelViewMatrix, projectionMatrix,
                    partialTick, camera, setupFog);
            case END, NONE -> false;
        };
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick,
            LightTexture lightTexture, double camX, double camY, double camZ) {
        return inTwilightZone()
                && twilight.renderSnowAndRain(level, ticks, partialTick, lightTexture, camX, camY, camZ);
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return inTwilightZone() && twilight.tickRain(level, ticks, camera);
    }

    @Nullable
    private DimensionSpecialEffects overlayEffects() {
        return switch (currentOverlay()) {
            case TWILIGHT -> twilight;
            case STARLIGHT -> starlight;
            case END -> end;
            case NONE -> null;
        };
    }

    private static RotatingSkyOverlay currentOverlay() {
        return RotatingSkyOverlay.of(inTwilightZone(), inStarlightZone(), inEndZone());
    }

    /**
     * True while the main camera samples a twilightforest-namespace biome.
     * Biome lookups are the same signal the sky/fog color pipelines use, so
     * the binary switch lands exactly where the color interpolation crosses.
     */
    private static boolean inTwilightZone() {
        return cameraBiomeNamespace("twilightforest");
    }

    private static boolean inEndZone() {
        return ClientBandLane.end();
    }

    /**
     * True while the main camera samples an eternal_starlight-namespace biome.
     * Same camera-biome signal as twilight, so the dead-star sky flips with
     * the sky-disc color rather than waiting on the lane packet.
     */
    private static boolean inStarlightZone() {
        return cameraBiomeNamespace("eternal_starlight");
    }

    private static boolean cameraBiomeNamespace(String namespace) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel level)) {
            return false;
        }
        return isBiomeNamespace(level, minecraft.gameRenderer.getMainCamera().getBlockPosition(), namespace);
    }

    static boolean isTwilightBiome(ClientLevel level, BlockPos pos) {
        return isBiomeNamespace(level, pos, "twilightforest");
    }

    private static boolean isBiomeNamespace(ClientLevel level, BlockPos pos, String namespace) {
        return level.getBiome(pos).unwrapKey()
                .map(key -> key.location().getNamespace().equals(namespace))
                .orElse(false);
    }
}
