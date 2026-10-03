package dimblend.radio.client;

import dimblend.radio.acoustics.SteamAudioDownload;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Chat progress for the background phonon.dll download. All callbacks arrive off-thread;
 * messages hop to the client thread and are skipped when no player is around (menu, logout).
 */
final class AcousticDownloadNotice implements SteamAudioDownload.Listener {
    private static volatile boolean armed;

    /** Idempotent: the first radio that needs Steam Audio arms the notice for the process. */
    static void ensureRegistered() {
        if (armed) return;
        armed = true;
        SteamAudioDownload.listen(new AcousticDownloadNotice());
    }

    @Override
    public void started(long totalBytes) {
        say(Component.literal("正在下载声学组件（约 " + totalBytes / 1_000_000
                + "MB，仅一次），期间收音机为普通立体声"));
    }

    @Override
    public void progress(double fraction) {
        say(Component.literal(String.format(Locale.ROOT, "声学组件下载中 %d%%…", Math.round(fraction * 100))));
    }

    @Override
    public void done(long millis) {
        say(Component.literal("声学组件下载完成，收音机自动切回环绕混响"));
    }

    @Override
    public void failed(String reason) {
        say(Component.literal("声学组件下载失败，收音机保持普通立体声：" + reason)
                .withStyle(ChatFormatting.RED));
    }

    private static void say(Component text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.execute(() -> {
            if (mc.player != null) mc.gui.getChat().addMessage(text);
        });
    }

    private AcousticDownloadNotice() { }
}
