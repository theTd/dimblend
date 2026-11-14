package dimblend.dragonchase.carriage;

import dimblend.api.NonCrewCarriages;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Registers the car behaviour events.
 *
 * <p>All five are game-bus events (a server tick, three player actions and the cross-mod purge
 * notification), so they are attached to {@code NeoForge.EVENT_BUS} here rather than through
 * {@code @EventBusSubscriber}: one place names everything this package listens to, which is what
 * the module's main class refers to.
 *
 * <p>The split is deliberate: {@link CarTicker} owns what happens over time, {@link CarInteractionLock}
 * owns what a player may touch, {@link CarPurgeListener} owns identities deleted underneath this
 * mod. Nothing here decides anything itself.
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
        CarPurgeListener.register();
        NonCrewCarriages.install(DragonCarriages::activeCarIds);
    }

    private CarriageBootstrap() {
    }
}
