package dimblend.dragonchase.client;

import dimblend.dragonchase.DragonChaseRegistries;
import net.minecraft.client.renderer.entity.DragonFireballRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.projectile.DragonFireball;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only wiring: renderers for both entity types plus resetting the crystal-beam cache on
 * logout. Only called on the physical client ({@code DimBlendDragonChase}).
 */
public final class DragonChaseClient {
    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(DragonChaseClient::onRegisterRenderers);
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientCrystalBeams.reset());
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // The dragon and its fireball are EnderDragon / DragonFireball subclasses, so the vanilla
        // renderers apply unchanged (the dragon one is wrapped to add the per-crystal beams).
        EntityRendererProvider<EnderDragon> dragonRenderer = ChaseDragonRenderer::new;
        event.registerEntityRenderer(DragonChaseRegistries.CHASE_DRAGON.get(), dragonRenderer);
        EntityRendererProvider<DragonFireball> fireballRenderer = DragonFireballRenderer::new;
        event.registerEntityRenderer(DragonChaseRegistries.DRAGON_FIREBALL.get(), fireballRenderer);
    }

    private DragonChaseClient() {
    }
}
