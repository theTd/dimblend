package dimblend.client;

import cn.leolezury.eternalstarlight.common.client.ESDimensionSpecialEffects;
import cn.leolezury.eternalstarlight.common.client.renderer.world.ESSkyRenderer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.DimensionSpecialEffectsManager;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import twilightforest.client.TwilightForestRenderInfo;

/**
 * Dimension effects for the rotating dimension: vanilla overworld visuals by
 * default, Twilight Forest visuals while the camera samples a twilightforest
 * biome, Eternal Starlight sky while the camera samples an eternal_starlight
 * biome, Voidscape's portal-shader sky while the player stands in a voidscape
 * lane, vanilla End sky while the player stands in an End-sky lane
 * (end / deeperdarker), and vanilla Nether effects while the player stands in
 * a Nether-sky lane (underground / nether).
 *
 * <p>Restores the twilight band's "biome shader" (perma-dusk starfield, no
 * sun/moon/sunset, TF fog curve, low-Y / dark-forest fog) that lives in TF's
 * {@code twilightforest:renderer} effects instance and is normally keyed to
 * TF's own dimension only. Fog color is that instance's
 * {@code getBrightnessDependentFogColor} only — TF 1.21.1 has no extra
 * FogHandler color pass. Starlight bands delegate {@link ESSkyRenderer}
 * (dead star, custom starfield, {@link SkyType#NONE}) the same way; time lock
 * 14000 still applies to day-cycle reads, not the celestial pose (ES pins the
 * dead star at 12500). Voidscape lanes delegate the {@code voidscape:void}
 * effects instance ({@code SkyType.NONE}, portal-shader cube via
 * {@code VoidSkyRenderer}). End-sky lanes flip {@link DimensionSpecialEffects.SkyType#END}
 * so vanilla {@code renderEndSky} draws {@code end_sky.png} instead of the overworld
 * sun/moon/stars; time lock 18000 still applies to day-cycle reads, not the
 * skybox. Nether-sky lanes delegate {@link DimensionSpecialEffects.NetherEffects}
 * ({@code SkyType.NONE}, thick fog, constant ambient light, no clouds).</p>
 *
 * <p>The effects instance is a per-dimension singleton, so the band switch is
 * a per-frame predicate. Twilight and Starlight key off the camera's noise
 * biome (the same quart sample {@code getSkyColor} uses) so the binary bits
 * (stars, sun/moon / dead star) flip on that seam without Voronoi chatter.
 * {@link net.minecraft.world.level.biome.BiomeManager#getBiome} zooms eight
 * neighbor quarts; on the client an unloaded neighbor is plains, which made
 * the overlay twitch a few times when walking into a twilight or starlight
 * band. Unloaded camera quarts keep the last overlay instead of snapping to
 * plains. Voidscape, End-sky and Nether-sky lanes key off {@link ClientBandLane}
 * because they meet neighbors at a partition wall, not a biome blend.</p>
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
    private final DimensionSpecialEffects.NetherEffects nether = new DimensionSpecialEffects.NetherEffects();
    private static final ResourceLocation VOIDSCAPE_EFFECTS =
            ResourceLocation.fromNamespaceAndPath("voidscape", "void");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean loggedMissingVoidscapeEffects;

    private static RotatingSkyOverlay lastOverlay = RotatingSkyOverlay.NONE;

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
    public boolean constantAmbientLight() {
        DimensionSpecialEffects overlay = overlayEffects();
        return overlay != null ? overlay.constantAmbientLight() : super.constantAmbientLight();
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
            case VOIDSCAPE -> voidscapeEffects().renderSky(level, ticks, partialTick, modelViewMatrix, camera,
                    projectionMatrix, isFoggy, setupFog);
            case END, NETHER, NONE -> false;
        };
    }

    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick,
            LightTexture lightTexture, double camX, double camY, double camZ) {
        return currentOverlay() == RotatingSkyOverlay.TWILIGHT
                && twilight.renderSnowAndRain(level, ticks, partialTick, lightTexture, camX, camY, camZ);
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return currentOverlay() == RotatingSkyOverlay.TWILIGHT && twilight.tickRain(level, ticks, camera);
    }

    @Nullable
    private DimensionSpecialEffects overlayEffects() {
        return switch (currentOverlay()) {
            case TWILIGHT -> twilight;
            case STARLIGHT -> starlight;
            case VOIDSCAPE -> voidscapeEffects();
            case END -> end;
            case NETHER -> nether;
            case NONE -> null;
        };
    }

    private static DimensionSpecialEffects voidscapeEffects() {
        DimensionSpecialEffects effects = DimensionSpecialEffectsManager.getForType(VOIDSCAPE_EFFECTS);
        if (!loggedMissingVoidscapeEffects && effects.skyType() != DimensionSpecialEffects.SkyType.NONE) {
            loggedMissingVoidscapeEffects = true;
            LOGGER.warn("voidscape:void dimension effects missing; voidscape lane will not use VoidSkyRenderer");
        }
        return effects;
    }

    private static RotatingSkyOverlay currentOverlay() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.level instanceof ClientLevel level)) {
            lastOverlay = RotatingSkyOverlay.NONE;
            return lastOverlay;
        }
        BlockPos pos = minecraft.gameRenderer.getMainCamera().getBlockPosition();
        RotatingSkyOverlay sampled = RotatingSkyOverlay.of(
                isBiomeNamespace(level, pos, "twilightforest"),
                isBiomeNamespace(level, pos, "eternal_starlight"),
                ClientBandLane.voidscape(),
                ClientBandLane.endSky(),
                ClientBandLane.netherSky());
        lastOverlay = RotatingSkyOverlay.holdIfUnloaded(level.isLoaded(pos), sampled, lastOverlay);
        return lastOverlay;
    }

    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            lastOverlay = RotatingSkyOverlay.NONE;
        }
    }

    /**
     * True while this block's noise biome is a twilightforest-namespace biome.
     * Quart sample, not Voronoi {@code getBiome}, so neighbor-chunk plains
     * fallback cannot flip the fog gate.
     */
    static boolean isTwilightBiome(ClientLevel level, BlockPos pos) {
        return isBiomeNamespace(level, pos, "twilightforest");
    }

    private static boolean isBiomeNamespace(ClientLevel level, BlockPos pos, String namespace) {
        return level.getBiomeManager().getNoiseBiomeAtPosition(pos).unwrapKey()
                .map(key -> key.location().getNamespace().equals(namespace))
                .orElse(false);
    }
}
