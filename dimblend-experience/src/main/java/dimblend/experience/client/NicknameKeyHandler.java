package dimblend.experience.client;

import com.mojang.blaze3d.platform.InputConstants;

import dimblend.experience.DimBlend;
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

/**
 * A8 打开昵称对话框的按键。默认不绑定，玩家在控制设置里自行指定。
 */
@EventBusSubscriber(modid = DimBlend.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class NicknameKeyHandler {

    public static final KeyMapping OPEN = new KeyMapping(
            "key.dimblend_experience.nickname",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "key.categories.dimblend_experience");

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(NicknameKeyHandler::onClientTick));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (minecraft.player != null && minecraft.screen == null) {
                minecraft.setScreen(new NicknameScreen());
            }
        }
    }

    private NicknameKeyHandler() {
    }
}
