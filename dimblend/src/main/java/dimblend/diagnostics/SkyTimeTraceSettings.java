package dimblend.diagnostics;

import dimblend.api.SkyTimeTraceTuning;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Persistent CLIENT config and a coherent, thread-safe snapshot for network/render observers. */
@EventBusSubscriber(modid = "dimblend", bus = EventBusSubscriber.Bus.MOD)
public final class SkyTimeTraceSettings {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    private static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .comment("Record local sky/time diagnostics in the client log. Changed live from the dbx panel.")
            .define("skyTimeTrace", false);
    public static final ModConfigSpec SPEC = BUILDER.build();
    private static volatile SkyTimeTraceTuning.State state = new SkyTimeTraceTuning.State(false, 0L);

    public static SkyTimeTraceTuning.State state() { return state; }

    public static void setEnabled(boolean enabled) {
        synchronized (SkyTimeTraceSettings.class) {
            if (!SPEC.isLoaded()) throw new IllegalStateException("Sky time trace client config is not loaded");
            ENABLED.set(enabled);
            publish(enabled);
        }
        // FML saves dispatch Reloading under its own config lock. Never hold our monitor across save:
        // the file watcher enters these same callbacks while already holding that FML lock.
        try {
            SPEC.save();
        } finally {
            refresh();
        }
    }

    @SubscribeEvent
    public static void loaded(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) refresh();
    }

    @SubscribeEvent
    public static void reloaded(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) refresh();
    }

    @SubscribeEvent
    public static void unloaded(ModConfigEvent.Unloading event) {
        if (event.getConfig().getSpec() == SPEC) publish(false);
    }

    private static synchronized void refresh() {
        // Read inside the same critical section as publication, so a delayed callback cannot publish a stale read.
        publish(SPEC.isLoaded() && ENABLED.get());
    }

    private static synchronized void publish(boolean enabled) {
        if (state.enabled() != enabled) state = new SkyTimeTraceTuning.State(enabled, state.revision() + 1L);
    }

    private SkyTimeTraceSettings() { }
}
