package dimblend.experience.mixin.client;

import dimblend.experience.curse.CurseClickPayload;
import dimblend.experience.curse.CurseRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 消失诅咒的触发改成"每一次物理左/右键"（用户 2026-11-03 口径）：在 {@code MouseHandler#onPress}
 * 的 HEAD 上报一次 C2S 信号。
 *
 * <p>HEAD 是故意的：原版方法体前半段会按"当前有没有界面 / 点到什么"分流，而这里要在一切分流之前
 * 取到按下事件，所以 GUI 里的点击、对着空气的挥击都算——这正是需求要的"连 GUI 点击也算"。
 * 只认 GLFW_PRESS（action 1）与左/右键（button 0/1），松手、中键、侧键都不上报。</p>
 *
 * <p>单测抓不到本类（单测不应用 mixin），必须实机点一次才算验证完。</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseClickMixin {

    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), require = 1, allow = 1)
    private void dimblend$reportMouseClick(long windowPointer, int button, int action, int modifiers, CallbackInfo ci) {
        if (!CurseRules.isTrackedMouseClick(button, action)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // 标题画面/断线时没有连接与玩家，发出去只会炸在 sendToServer 的 requireNonNull 上。
        ClientPacketListener connection = minecraft.getConnection();
        if (minecraft.player == null || connection == null) {
            return;
        }
        // 客户端装了本模组、服务端没装时通道不存在：NeoForge 的 checkPacket 会抛
        // UnsupportedOperationException 砸在鼠标回调里。这里先用它自己的同一条判定问一次
        // （NetworkRegistry#hasChannel，Connection 重载是 public 且不抛异常），通道不在就静默跳过。
        if (!NetworkRegistry.hasChannel(connection.getConnection(), ConnectionProtocol.PLAY,
                CurseClickPayload.TYPE.id())) {
            return;
        }
        PacketDistributor.sendToServer(new CurseClickPayload());
    }
}
