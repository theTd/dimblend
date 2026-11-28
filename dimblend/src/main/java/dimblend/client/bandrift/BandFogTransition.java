package dimblend.client.bandrift;

import dimblend.DimBlendRegistries;
import dimblend.client.ClientBandLane;
import dimblend.client.ClientBandProgress;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Fog colour and fog plane transition across a band boundary, with no mixin.
 *
 * <p>{@code RotatingDimensionEffects} swaps its whole overlay — sky type, fog colour, fog curve,
 * ambient light — on one frame, keyed by {@link ClientBandLane}. The sky type and constant ambient
 * light are enum/boolean switches that cannot be interpolated; only the fog can be smoothed, and a
 * 0.25-0.35 s crossing flash (see {@code band_glitch}) hides the part that cannot.</p>
 *
 * <p>Both handlers sit at {@code EventPriority.LOW}, i.e. after every default-priority listener,
 * so {@link dimblend.client.TwilightBandFog}'s spooky-forest curve is the value being blended
 * towards rather than being overwritten by it. The blend only starts on an actual lane or region
 * change and switches itself off once it has converged, so a stationary player pays nothing and
 * nothing here can fight another mod's fog outside a crossing.</p>
 *
 * <p>All blending decisions live in {@link BandFogTransitionState}; this class is only the
 * Minecraft glue. Both render handlers observe the band identity themselves (see the P4 note in
 * the state class): a lane packet landing between two ticks must arm the blend on the next frame,
 * not 50 ms later, and an early converged-looking frame must not disarm it.</p>
 */
public final class BandFogTransition {
    private static final BandFogTransitionState STATE = new BandFogTransitionState();

    private BandFogTransition() {
    }

    /** Game-bus listener: arms the blend when the band identity changes. */
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        int bandSize = ClientBandProgress.bandSize();
        if (!rotating(level) || bandSize <= 0) {
            reset();
            return;
        }
        int region = Math.floorDiv(Mth.floor(minecraft.gameRenderer.getMainCamera().getPosition().x), bandSize);
        STATE.observe(region, ClientBandLane.lane(), System.nanoTime());
    }

    /** Game-bus listener at {@code EventPriority.LOW}. */
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        ClientLevel level = Minecraft.getInstance().level;
        // ComputeFogColor carries no FogType; the camera is the same source FogRenderer reads.
        if (!rotating(level) || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        int bandSize = ClientBandProgress.bandSize();
        if (bandSize <= 0) {
            return;
        }
        long now = System.nanoTime();
        int region = Math.floorDiv(Mth.floor(event.getCamera().getPosition().x), bandSize);
        STATE.observe(region, ClientBandLane.lane(), now);
        STATE.stepColor(event.getRed(), event.getGreen(), event.getBlue(), now);
        if (STATE.colorValid()) {
            event.setRed(STATE.outRed());
            event.setGreen(STATE.outGreen());
            event.setBlue(STATE.outBlue());
        }
        STATE.settleIfConverged(now);
    }

    /** Game-bus listener at {@code EventPriority.LOW}. */
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!rotating(level) || event.getType() != FogType.NONE) {
            return;
        }
        int bandSize = ClientBandProgress.bandSize();
        if (bandSize <= 0) {
            return;
        }
        long now = System.nanoTime();
        int region = Math.floorDiv(Mth.floor(event.getCamera().getPosition().x), bandSize);
        STATE.observe(region, ClientBandLane.lane(), now);
        boolean sky = event.getMode() == FogMode.FOG_SKY;
        if (sky) {
            STATE.stepSky(event.getNearPlaneDistance(), event.getFarPlaneDistance(), now);
            event.setNearPlaneDistance(STATE.skyNear());
            event.setFarPlaneDistance(STATE.skyFar());
        } else {
            STATE.stepTerrain(event.getNearPlaneDistance(), event.getFarPlaneDistance(), now);
            event.setNearPlaneDistance(STATE.terrainNear());
            event.setFarPlaneDistance(STATE.terrainFar());
        }
        // RenderFog only applies the plane distances when it is cancelled.
        event.setCanceled(true);
        STATE.settleIfConverged(now);
    }

    /** Game-bus listener: forget the previous band so nothing leaks across dimensions. */
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        reset();
    }

    public static void reset() {
        STATE.reset();
    }

    /** One-line state for {@code /dimblendrift status}. */
    public static String statusLine() {
        return String.format(Locale.ROOT,
                "fogBlend=%s lane=%s region=%s color=%.3f/%.3f/%.3f sky=%.1f..%.1f terrain=%.1f..%.1f",
                STATE.active() ? "active" : "idle",
                STATE.lane() == null ? "unknown" : STATE.lane(),
                STATE.region() == Integer.MIN_VALUE ? "?" : Integer.toString(STATE.region()),
                STATE.outRed(), STATE.outGreen(), STATE.outBlue(),
                STATE.skyNear(), STATE.skyFar(), STATE.terrainNear(), STATE.terrainFar());
    }

    private static boolean rotating(@Nullable ClientLevel level) {
        return level != null && level.dimension().equals(DimBlendRegistries.ROTATING_LEVEL);
    }
}
