package dimblend.experience.client;

import dimblend.experience.compat.connected.LinkedTransmitterDebugSettings;
import dimblend.experience.compat.connected.LinkedTransmitterDiagnostics;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;

public final class LinkedTransmitterDebugClient {

    public static void tick(ClientTickEvent.Post event) {
        if (!LinkedTransmitterDebugSettings.enabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        float partialTick = minecraft.getTimer().getGameTimeDeltaPartialTick(false);
        LinkedTransmitterDiagnostics.sample(minecraft.player, () -> ModList.get().isLoaded("sable")
                ? SableLinkedClientDiagnostics.renderPoses(minecraft.player, partialTick)
                : "");
    }

    private LinkedTransmitterDebugClient() {
    }
}
