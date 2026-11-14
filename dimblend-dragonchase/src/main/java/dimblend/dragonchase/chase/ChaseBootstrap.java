package dimblend.dragonchase.chase;

import dimblend.api.NonCrewCarriages;
import dimblend.dragonchase.command.DragonChaseCommand;
import dimblend.dragonchase.entity.ChaseDragons;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wires the chase: the dragon listener, the per-level director tick, the spawn pause, the debug
 * command and the shutdown marker cleanup.
 *
 * <p>The mod bus is taken for symmetry with the other bootstraps but nothing here is a mod-bus
 * event: all of them are game-bus events, so they are added to {@link NeoForge#EVENT_BUS}.
 *
 * <p>Cross-mod wiring: {@code NonCrewCarriages.installUnticketGuard} answers "this sub-level keeps
 * its loading tickets" for the nest, so {@code dimblend}'s region purge cannot unticket a
 * {@code WAITING}/{@code ACTIVE} nest and silently abandon the chase. The id set the speed floor
 * reads is a different question and is installed by {@code CarriageBootstrap}, next to the registry
 * it answers from.
 */
public final class ChaseBootstrap {
    public static void init(IEventBus modEventBus) {
        ChaseDragons.setListener(new ChaseListener());
        NeoForge.EVENT_BUS.addListener(ChaseDirector::onLevelTick);
        // LOWEST: no later listener may re-enable a spawn this rule denied (§4).
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ChaseSpawnRule::onFinalizeSpawn);
        NeoForge.EVENT_BUS.addListener(ChaseDirector::onServerStopped);
        NeoForge.EVENT_BUS.addListener(DragonChaseCommand::register);
        NonCrewCarriages.installUnticketGuard(ChaseDirector::isNestForceLoadProtected);
    }

    private ChaseBootstrap() {
    }
}
