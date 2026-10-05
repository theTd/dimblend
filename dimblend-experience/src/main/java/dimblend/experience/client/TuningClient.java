package dimblend.experience.client;

import com.mojang.blaze3d.platform.InputConstants;
import dimblend.experience.DimBlend;
import dimblend.experience.tuning.TuningEditPayload;
import dimblend.experience.tuning.TuningOption;
import dimblend.experience.tuning.TuningSettings;
import dimblend.experience.tuning.TuningSnapshotPayload;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = DimBlend.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class TuningClient {
    public static final KeyMapping OPEN = new KeyMapping("key.dimblend_experience.tuning",
            KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(),
            "key.categories.dimblend_experience");

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(TuningClient::tick));
    }

    private static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) {
                PacketDistributor.sendToServer(new TuningEditPayload("", 0.0D));
            }
        }
    }

    public static void receive(TuningSnapshotPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() == null) {
            for (TuningOption option : TuningOption.values()) {
                if (option.isValid(payload.value(option))) {
                    TuningSettings.applyRemote(option, payload.value(option), payload.available(option));
                }
            }
        }
        if (payload.open()) {
            minecraft.setScreen(new TuningScreen(payload));
        } else if (minecraft.screen instanceof TuningScreen screen) {
            screen.receive(payload);
        }
    }

    private TuningClient() {
    }
}
