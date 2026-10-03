package dimblend.radio.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dimblend.radio.DimBlendRadio;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * {@code /radioacoustics record [seconds]} records what the radios play and how their acoustics
 * were simulated ({@link AcousticRecording}) for 30 s, or the given seconds up to
 * {@link AcousticRecording#MAX_SECONDS}, into {@code dimblend-radio/acoustic-recordings} under the
 * game directory; {@code /radioacoustics record stop} ends it early. The elapsed time shows on the
 * action bar, so a moment heard can be found in the recording.
 */
@EventBusSubscriber(modid = DimBlendRadio.MODID, value = Dist.CLIENT)
public final class AcousticRecordingCommand {
    private static final int DEFAULT_SECONDS = 30;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("radioacoustics")
                .then(Commands.literal("record")
                        .executes(context -> start(context.getSource(), DEFAULT_SECONDS))
                        .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, AcousticRecording.MAX_SECONDS))
                                .executes(context -> start(context.getSource(), IntegerArgumentType.getInteger(context, "seconds"))))));
    }

    private static int start(CommandSourceStack source, int seconds) {
        if (AcousticRecording.active() != null) {
            source.sendFailure(Component.literal("Already recording radio acoustics; /radioacoustics record stop ends it"));
            return 0;
        }
        try {
            AcousticRecording.start(directory(), seconds);
        } catch (IOException | RuntimeException error) {
            DimBlendRadio.LOGGER.warn("[radio] cannot start an acoustic recording", error);
            source.sendFailure(Component.literal("Cannot record radio acoustics: " + error));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Recording radio acoustics for " + clock(seconds)
                + ". Note the time on the action bar when you hear the problem; /radioacoustics record stop ends early."), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int stop(CommandSourceStack source) {
        AcousticRecording recording = AcousticRecording.active();
        if (recording == null) {
            source.sendFailure(Component.literal("No radio acoustics recording is running"));
            return 0;
        }
        finish(recording);
        return Command.SINGLE_SUCCESS;
    }

    /** Shows the elapsed time, and stops the recording once its time is up. */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        AcousticRecording recording = AcousticRecording.active();
        if (recording == null) return;
        double elapsed = recording.elapsedSeconds();
        if (elapsed >= recording.limitSeconds()) {
            finish(recording);
            return;
        }
        Minecraft.getInstance().gui.setOverlayMessage(Component.literal("● Recording radio acoustics "
                + clock(elapsed) + " / " + clock(recording.limitSeconds())).withStyle(ChatFormatting.RED), false);
    }

    private static void finish(AcousticRecording recording) {
        Minecraft mc = Minecraft.getInstance();
        message(mc, Component.literal("Radio acoustics recording stopped at " + clock(recording.elapsedSeconds()) + "; saving..."));
        recording.stop().whenComplete((summary, error) -> mc.execute(() -> message(mc, saved(summary, error))));
    }

    private static Component saved(AcousticRecording.Summary summary, Throwable error) {
        if (error != null) {
            DimBlendRadio.LOGGER.warn("[radio] acoustic recording failed to close", error);
            return Component.literal("Radio acoustics recording failed to save: " + error).withStyle(ChatFormatting.RED);
        }
        Path folder = summary.directory().toAbsolutePath();
        MutableComponent link = Component.literal(folder.getFileName().toString()).withStyle(style -> style
                .withUnderlined(true).withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, folder.toString())));
        MutableComponent text = Component.literal("Saved radio acoustics recording ").append(link)
                .append(String.format(Locale.ROOT, " (%d radios, %s, %.0f MB)", summary.radios(), clock(summary.seconds()),
                        summary.bytes() / 1e6));
        if (summary.radios() == 0) text.append(Component.literal("; no radio played through the acoustics while it ran").withStyle(ChatFormatting.YELLOW));
        if (summary.dropped() > 0) text.append(Component.literal("; " + summary.dropped() + " writes were dropped").withStyle(ChatFormatting.YELLOW));
        if (summary.failure() != null) text.append(Component.literal("; writing failed: " + summary.failure().getMessage()).withStyle(ChatFormatting.RED));
        return text;
    }

    private static void message(Minecraft mc, Component text) {
        mc.gui.getChat().addMessage(text);
    }

    private static Path directory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dimblend-radio").resolve("acoustic-recordings");
    }

    private static String clock(double seconds) {
        int whole = (int) seconds;
        return String.format(Locale.ROOT, "%d:%02d", whole / 60, whole % 60);
    }

    private AcousticRecordingCommand() { }
}
