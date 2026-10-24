package dimblend.experience.compat.connected;

import dimblend.experience.DimBlend;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = DimBlend.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class LinkedTransmitterDebugSettings {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Record linked transmitter interaction and player pose diagnostics in the game log. Also toggled by /dbx linkeddiag.")
            .define("linkedTransmitterDiagnostics", false);
    public static final ModConfigSpec SPEC = BUILDER.build();
    private static volatile boolean enabled;

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(boolean value) {
        synchronized (LinkedTransmitterDebugSettings.class) {
            if (!SPEC.isLoaded()) {
                throw new IllegalStateException("Linked transmitter debug config is not loaded");
            }
            ENABLED.set(value);
        }
        try {
            SPEC.save();
        } finally {
            refresh();
        }
    }

    @SubscribeEvent
    public static void loaded(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            refresh();
        }
    }

    @SubscribeEvent
    public static void reloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            refresh();
        }
    }

    @SubscribeEvent
    public static void unloaded(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            enabled = false;
        }
    }

    private static synchronized void refresh() {
        enabled = SPEC.isLoaded() && ENABLED.get();
    }

    private LinkedTransmitterDebugSettings() {
    }
}
