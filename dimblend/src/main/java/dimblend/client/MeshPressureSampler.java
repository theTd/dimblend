package dimblend.client;

import dimblend.worldgen.MeshPressure;
import dimblend.worldgen.PregenAdmission;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.bus.api.SubscribeEvent;

@EventBusSubscriber(modid = "dimblend", value = Dist.CLIENT)
public final class MeshPressureSampler {
    private static long previousFrame;
    private static long frameNanos;
    private static long slowUntil;
    private static long nextTargetSample;
    private static int targetFps;
    private MeshPressureSampler() {}

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre event) {
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        int target = targetFps(mc, now);
        if (previousFrame != 0) {
            frameNanos = now - previousFrame;
            if (!PregenAdmission.framesHealthy(mc.getFps(), target, frameNanos)) {
                slowUntil = now + 1_000_000_000L;
            }
        }
        previousFrame = now;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;  // main menu: do not refresh the timestamp so the signal naturally goes stale (NO_SIGNAL)
        }
        SectionRenderDispatcher dispatcher = mc.levelRenderer.getSectionRenderDispatcher();
        if (dispatcher == null) {
            return;  // null before allChanged() (LevelRenderer.java:705)
        }
        int target = targetFps(mc, System.nanoTime());
        boolean healthy = mc.player != null && mc.getOverlay() == null && mc.screen == null
                && System.nanoTime() >= slowUntil
                && PregenAdmission.framesHealthy(mc.getFps(), target, frameNanos);
        MeshPressure.update(dispatcher.getToBatchCount(), dispatcher.getToUpload(),
                dispatcher.getFreeBufferCount(), healthy);
    }

    private static int targetFps(Minecraft mc, long now) {
        if (now >= nextTargetSample) {
            targetFps = mc.options.framerateLimit().get();
            if (mc.options.enableVsync().get()) {
                int refreshRate = mc.getWindow().getRefreshRate();
                if (refreshRate > 0) targetFps = Math.min(targetFps, refreshRate);
            }
            nextTargetSample = now + 1_000_000_000L;
        }
        return targetFps;
    }
}
