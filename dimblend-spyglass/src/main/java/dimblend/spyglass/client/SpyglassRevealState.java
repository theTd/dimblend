package dimblend.spyglass.client;

import com.mojang.blaze3d.shaders.FogShape;
import dimblend.spyglass.DimBlendSpyglass;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * The player's live reveal distance and the terrain fog band the structure meshes fade into: start,
 * end and shape of the last {@code FOG_TERRAIN} pass.
 *
 * <p>Vanilla computes the terrain fog planes once per frame in {@code FogRenderer.setupFog}
 * ({@code LevelRenderer.java:949}); for the non-foggy case the start is
 * {@code R - clamp(R / 10, 4, 64)} and the end is {@code R}, with {@code R} the effective render
 * distance in blocks ({@code FogRenderer.java:267-275}, {@code GameRenderer.java:982-984,1232}). A
 * structure inside loaded terrain is otherwise covered by that fog, so the spyglass uses exactly the
 * distance the client already fades the terrain at: everything from there to 1024 blocks is shown,
 * instead of a fixed 256-block inner edge.
 *
 * <p>NeoForge fires {@link ViewportEvent.RenderFog} from {@code FogRenderer.setupFog} through
 * {@code ClientHooks.onFogRender} <b>after</b> the plane distances are computed and only lets a
 * cancelled event replace them, so an unmodified event exposes vanilla's fog start, end and shape.
 * Listening at {@link EventPriority#LOWEST} observes the values after every other listener.
 *
 * <p>{@link SpyglassShapeRenderer} reinstalls the same start/end/shape for both mesh passes. At
 * {@code AFTER_SKY} the shader fog still carries the sky pass's {@code FOG_SKY} values
 * ({@code LevelRenderer.java:947}, re-applied after the stars at {@code :1637}); the terrain pass runs
 * only afterwards ({@code :949}), so the last recorded terrain values are the correct ones there.
 *
 * <p>Reveal is enabled only for {@link FogRenderer.FogMode#FOG_TERRAIN} with
 * {@link FogType#NONE} and no blindness/darkness effect. Every other fog (water, lava, powder snow)
 * and the blindness/darkness fog functions replace the terrain fog with a short-range effect fog
 * whose start has nothing to do with the render distance; showing far structures through it would be
 * wrong, so the spyglass draws nothing at all then.
 *
 * <p>Client main thread only (the fog event, the level passes, the sender and the level clear all
 * run there); the value is a couple of plain fields.
 */
@EventBusSubscriber(modid = DimBlendSpyglass.MODID, value = Dist.CLIENT)
public final class SpyglassRevealState {
    /** The client main thread owns these (fog event, level passes, sender, level clear). */
    private static boolean enabled;
    private static float revealDistanceBlocks;
    private static float fogEndBlocks;
    private static FogShape fogShape = FogShape.SPHERE;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() != FogRenderer.FogMode.FOG_TERRAIN) {
            // The sky pass uses its own [0, R] fog; only the terrain pass matches the loaded terrain.
            return;
        }
        if (event.getType() != FogType.NONE || hasBlockingEffect(event.getCamera().getEntity())) {
            enabled = false;
            return;
        }
        float start = event.getNearPlaneDistance();
        if (!Float.isFinite(start) || start < 0.0F) {
            enabled = false;
            return;
        }
        enabled = true;
        revealDistanceBlocks = start;
        fogEndBlocks = event.getFarPlaneDistance();
        FogShape shape = event.getFogShape();
        fogShape = shape != null ? shape : FogShape.SPHERE;
    }

    private static boolean hasBlockingEffect(Entity cameraEntity) {
        // The vanilla blindness/darkness fog functions shorten the fog to 5 blocks; the reveal
        // distance would be meaningless, so the feature is off while either effect is active.
        return cameraEntity instanceof LivingEntity living
                && (living.hasEffect(MobEffects.BLINDNESS) || living.hasEffect(MobEffects.DARKNESS));
    }

    /** True when the last terrain fog pass was a plain, unobstructed distance fog. */
    static boolean isEnabled() {
        return enabled;
    }

    /** The terrain fog start in blocks recorded by the last terrain pass; only valid when enabled. */
    static double revealDistanceBlocks() {
        return revealDistanceBlocks;
    }

    /** The terrain fog end in blocks recorded by the last terrain pass; only valid when enabled. */
    static float fogEndBlocks() {
        return fogEndBlocks;
    }

    /** The terrain fog shape recorded by the last terrain pass; only valid when enabled. */
    static FogShape fogShape() {
        return fogShape;
    }

    /** Forgets the values: nothing is shown before the first terrain pass after joining a level. */
    static void reset() {
        enabled = false;
        revealDistanceBlocks = 0.0F;
        fogEndBlocks = 0.0F;
        fogShape = FogShape.SPHERE;
    }

    private SpyglassRevealState() {
    }
}
