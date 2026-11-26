package dimblend.dragonchase.carriage;

import dimblend.api.NonCrewCarriages;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Registers the car behaviour events.
 *
 * <p>All of them are game-bus events (a server tick, three player actions, the player join/dimension
 * pushes and the cross-mod purge notification), so they are attached to {@code NeoForge.EVENT_BUS}
 * here rather than through {@code @EventBusSubscriber}: one place names everything this package
 * listens to, which is what the module's main class refers to.
 *
 * <p>The split is deliberate: {@link CarTicker} owns what happens over time, {@link CarInteractionLock}
 * owns what a player may touch, {@link CarPurgeListener} owns identities deleted underneath this
 * mod, {@link CrystalMountsSync} owns the one client-facing list, {@link CarCrystalExplosion} owns
 * what a broken car crystal does. Nothing here decides anything itself.
 *
 * <p><b>Cross-mod wiring.</b> {@code dimblend.api.NonCrewCarriages} is the "not a player's train"
 * answer the other DimBlend features ask for; the registered cars are exactly that set, so the
 * provider is installed here, where the registry's lifetime begins.
 */
public final class CarriageBootstrap {
    public static void init(IEventBus modEventBus) {
        NeoForge.EVENT_BUS.addListener(CarTicker::onServerTick);
        NeoForge.EVENT_BUS.addListener(CarInteractionLock::onRightClick);
        NeoForge.EVENT_BUS.addListener(CarInteractionLock::onLeftClick);
        NeoForge.EVENT_BUS.addListener(CarInteractionLock::onBreak);
        // Break one crystal, keep the rest: the car's crystals knock back, they never damage.
        NeoForge.EVENT_BUS.addListener(CarCrystalExplosion::onExplosionStart);
        NeoForge.EVENT_BUS.addListener(CarriageBootstrap::onPlayerLoggedIn);
        NeoForge.EVENT_BUS.addListener(CarriageBootstrap::onPlayerChangedDimension);
        CarPurgeListener.register();
        NonCrewCarriages.install(DragonCarriages::activeCarIds);
    }

    /**
     * A joining player has no crystal mount list; without this a crystal car already running would
     * stay frozen on that client until the next spawn or death changes the list.
     */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CrystalMountsSync.sendTo(player);
        }
    }

    /** Same for a dimension change: the client kept the old dimension's ids, and the new one's list. */
    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            CrystalMountsSync.sendTo(player);
        }
    }

    private CarriageBootstrap() {
    }
}
