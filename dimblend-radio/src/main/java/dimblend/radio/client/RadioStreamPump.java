package dimblend.radio.client;

import com.mojang.blaze3d.audio.Channel;
import dimblend.radio.mixin.client.SoundEngineAccessor;
import dimblend.radio.mixin.client.SoundManagerAccessor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.client.Minecraft;

/** Keep PCM queued even when the render thread is busy loading moving-world geometry. */
public final class RadioStreamPump {
    private record Entry(Executor executor, AtomicBoolean pending) { }
    private static final Map<Channel, Entry> STREAMS = new ConcurrentHashMap<>();
    static {
        var timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "Radio stream refill timer");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(RadioStreamPump::refill, 20, 20, TimeUnit.MILLISECONDS);
    }

    public static void register(Channel channel) {
        var manager = (SoundManagerAccessor) Minecraft.getInstance().getSoundManager();
        var engine = (SoundEngineAccessor) manager.dimblend$radioSoundEngine();
        STREAMS.put(channel, new Entry(engine.dimblend$radioExecutor(), new AtomicBoolean()));
    }

    public static void unregister(Channel channel) { STREAMS.remove(channel); }

    private static void refill() {
        STREAMS.forEach((channel, entry) -> {
            if (!entry.pending.compareAndSet(false, true)) return;
            entry.executor.execute(() -> {
                try {
                    if (STREAMS.get(channel) == entry) channel.updateStream();
                } finally { entry.pending.set(false); }
            });
        });
    }

    private RadioStreamPump() { }
}
