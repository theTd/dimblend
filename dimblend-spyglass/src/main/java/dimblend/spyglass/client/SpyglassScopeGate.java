package dimblend.spyglass.client;

import net.minecraft.client.Minecraft;

/**
 * Single source of truth for "the player is looking through a spyglass right now".
 * Package-visible so every client-side renderer (outlines, labels, ...) uses the same gate.
 */
final class SpyglassScopeGate {
    static boolean isScoping(Minecraft minecraft) {
        return minecraft.level != null
                && minecraft.player != null
                && minecraft.screen == null
                && !minecraft.options.hideGui
                && minecraft.options.getCameraType().isFirstPerson()
                && minecraft.player.isScoping();
    }

    private SpyglassScopeGate() {
    }
}
