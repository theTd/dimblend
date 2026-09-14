package dimblend.client;

import net.minecraft.resources.ResourceKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer.FogMode;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FogType;
import net.neoforged.neoforge.client.event.ViewportEvent.ComputeFogColor;
import net.neoforged.neoforge.client.event.ViewportEvent.RenderFog;
import net.neoforged.neoforge.event.level.LevelEvent.Unload;

/**
 * Twilight-band fog distance control for the rotating dimension: a port of
 * Twilight Forest's {@code twilightforest.client.event.FogHandler} (slow lerp
 * of the sky/terrain fog planes, half far-plane and pulled-in near-plane in
 * the spooky forest). TF's handler is gated on
 * {@code effects() instanceof TwilightForestRenderInfo}, which never matches
 * the rotating dimension, so the same behavior is reproduced here behind the
 * dimblend-specific gate (rotating dimension + camera-side twilightforest
 * biome).
 *
 * <p>One deliberate deviation from the TF original: the chunk-loaded latches
 * are re-armed whenever the gate flips from off to on. TF only ever enters
 * its gate once per dimension visit (dimension unload resets the latches),
 * while a player can cross the surface/twilight seam repeatedly inside one
 * dimension; without re-arming, re-entry would resume lerping from a stale
 * far plane and take seconds to converge.</p>
 */
public final class TwilightBandFog {
    private static final ResourceKey<Biome> SPOOKY_FOREST = ResourceKey.create(
            Registries.BIOME, ResourceLocation.fromNamespaceAndPath("twilightforest", "spooky_forest"));

    private static boolean active = false;
    private static boolean skyChunkLoaded = false;
    private static float skyFar = 0.0F;
    private static float skyNear = 0.0F;
    private static boolean terrainChunkLoaded = false;
    private static float terrainFar = 0.0F;
    private static float terrainNear = 0.0F;
    private static float spookyPercent = 0.0F;

    private TwilightBandFog() {
    }

    public static void onComputeFogColor(ComputeFogColor event) {
        if (!(event.getCamera().getEntity() instanceof LocalPlayer player)
                || !(player.level() instanceof ClientLevel client)
                || !(client.effects() instanceof RotatingDimensionEffects)
                || !RotatingDimensionEffects.isTwilightBiome(client, player.blockPosition())) {
            return;
        }
        float[] colors = new float[]{event.getRed(), event.getGreen(), event.getBlue()};
        boolean spooky = isSpooky(client, player);
        double time = 13000;
        double d0 = Mth.frac(time / 24000.0F - 0.25F);
        double d1 = 0.5F - Math.cos(d0 * Math.PI) / 2.0F;
        double d2 = (d0 * 2.0F + d1) / 3.0F;
        float daylight = Mth.clamp(Mth.cos((float) (d2 * (Math.PI * 2))) * 2.0F + 0.5F, 0.0F, 1.0F);
        if (spooky) {
            spookyPercent += 0.005F;
        } else {
            spookyPercent -= 0.005F;
        }
        spookyPercent = Mth.clamp(spookyPercent, 0.0F, 1.0F);
        event.setRed(Mth.clampedLerp(spookyPercent, colors[0] * daylight * 0.94F + 0.06F, colors[0]));
        event.setGreen(Mth.clampedLerp(spookyPercent, colors[1] * daylight * 0.94F + 0.06F, colors[1]));
        event.setBlue(Mth.clampedLerp(spookyPercent, colors[2] * daylight * 0.91F + 0.09F, colors[2]));
    }

    public static void onRenderFog(RenderFog event) {
        if (event.getType() != FogType.NONE
                || !(Minecraft.getInstance().cameraEntity instanceof LocalPlayer player)
                || !(player.level() instanceof ClientLevel clientLevel)
                || !(clientLevel.effects() instanceof RotatingDimensionEffects)
                || !RotatingDimensionEffects.isTwilightBiome(clientLevel, player.blockPosition())) {
            if (active) {
                active = false;
                skyChunkLoaded = false;
                terrainChunkLoaded = false;
            }
            return;
        }
        active = true;

        if (event.getMode() == FogMode.FOG_SKY) {
            if (skyChunkLoaded) {
                event.setCanceled(true);
                boolean spooky = isSpooky(clientLevel, player);
                float far = spooky ? event.getFarPlaneDistance() * 0.5F : event.getFarPlaneDistance();
                float near = spooky ? 0.0F : event.getNearPlaneDistance();
                skyFar = Mth.lerp(0.003F, skyFar, far);
                skyNear = Mth.lerp(0.003F * (skyNear < near ? 0.5F : 2.0F), skyNear, near);
                event.setFarPlaneDistance(skyFar);
                event.setNearPlaneDistance(skyNear);
            } else if (clientLevel.isLoaded(player.blockPosition())) {
                skyChunkLoaded = true;
                skyFar = isSpooky(clientLevel, player) ? event.getFarPlaneDistance() * 0.5F : event.getFarPlaneDistance();
                skyNear = isSpooky(clientLevel, player) ? 0.0F : event.getNearPlaneDistance();
            }
        } else if (terrainChunkLoaded) {
            event.setCanceled(true);
            boolean spooky = isSpooky(clientLevel, player);
            float far = spooky ? event.getFarPlaneDistance() * 0.5F : event.getFarPlaneDistance();
            float near = spooky ? far * 0.75F : event.getNearPlaneDistance();
            terrainFar = Mth.lerp(0.003F, terrainFar, far);
            terrainNear = Mth.lerp(0.003F * (terrainNear < near ? 0.5F : 2.0F), terrainNear, near);
            event.setFarPlaneDistance(terrainFar);
            event.setNearPlaneDistance(terrainNear);
        } else if (skyChunkLoaded || clientLevel.isLoaded(player.blockPosition())) {
            terrainChunkLoaded = true;
            terrainFar = isSpooky(clientLevel, player) ? event.getFarPlaneDistance() * 0.5F : event.getFarPlaneDistance();
            terrainNear = isSpooky(clientLevel, player) ? terrainFar * 0.75F : event.getNearPlaneDistance();
        }
    }

    public static void onLevelUnload(Unload event) {
        // Fires for both sides like the TF original; the state only ever feeds
        // the client fog pipeline, so a stray server-side reset is harmless.
        active = false;
        skyChunkLoaded = false;
        terrainChunkLoaded = false;
        spookyPercent = 0.0F;
    }

    private static boolean isSpooky(ClientLevel level, LocalPlayer player) {
        return level.getBiome(player.blockPosition()).is(SPOOKY_FOREST);
    }
}
