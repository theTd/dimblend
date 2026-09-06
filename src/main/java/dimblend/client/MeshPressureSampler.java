package dimblend.client;

import dimblend.worldgen.MeshPressure;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.bus.api.SubscribeEvent;

@EventBusSubscriber(modid = "dimblend", value = Dist.CLIENT)
public final class MeshPressureSampler {
    private MeshPressureSampler() {}

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
        MeshPressure.update(dispatcher.getToBatchCount(), dispatcher.getFreeBufferCount());
    }
}
