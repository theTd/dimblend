package dimblend.client;

import dimblend.DimBlend;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Client-side pause mirror for the hang watchdog.
 *
 * <p>{@code IntegratedServer.paused} is a plain boolean written by the server thread;
 * the watchdog daemon has no happens-before edge to it and could read a stale value in
 * either direction (false hang dumps while the game is paused, or a suppressed dump
 * during a real hang). {@code Minecraft.pause} is volatile, and the client tick runs
 * even while the game is paused, so this bridge mirrors it every client tick into the
 * watchdog's atomic pair. {@code clientSeen} gating keeps dedicated servers unaffected:
 * this class never loads there.
 */
@EventBusSubscriber(modid = "dimblend", value = Dist.CLIENT)
public final class ClientPauseBridge {
    private ClientPauseBridge() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        DimBlend.watchdog().clientPause(mc.isPaused());
    }
}
