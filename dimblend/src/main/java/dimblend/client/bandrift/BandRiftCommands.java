package dimblend.client.bandrift;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import java.util.List;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

/**
 * Client command for the band-rift work: {@code /dimblendrift status|diag|mode|open|burst}.
 *
 * <p>Every answer goes to chat <em>and</em> to the log with a stable prefix so a capture session can
 * be replayed from the log alone:</p>
 * <ul>
 *   <li>{@code [bandrift-status]} — seam state, shader health, fog transition;</li>
 *   <li>{@code [bandrift-diag]} — every S2/M1 observable (injection hashes, uniform dump, mirage
 *       chunk/VBO counts, far-pass draws, frame microseconds);</li>
 *   <li>{@code [bandrift-mode]} — live A/B switch ({@code rift} / {@code membrane} / {@code legacy});</li>
 *   <li>{@code [bandrift-open]} — pinned {@code openFactor} for graded screenshots, or {@code auto};</li>
 *   <li>{@code [bandrift-burst]} — manual crossing burst for a capture without driving a train.</li>
 * </ul>
 */
public final class BandRiftCommands {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] MODES = {"rift", "membrane", "legacy"};
    private static final String[] OPEN_VALUES = {"auto", "0", "0.25", "0.45", "0.52", "0.75", "1"};

    private BandRiftCommands() {
    }

    /** Game-bus listener. */
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dimblendrift")
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("diag").executes(context -> diag(context.getSource())))
                .then(Commands.literal("mode").then(Commands.argument("mode", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(MODES, builder))
                        .executes(context -> mode(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "mode")))))
                .then(Commands.literal("open").then(Commands.argument("value", com.mojang.brigadier.arguments.StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(OPEN_VALUES, builder))
                        .executes(context -> open(context.getSource(),
                                com.mojang.brigadier.arguments.StringArgumentType.getString(context, "value")))))
                .then(Commands.literal("burst").executes(context -> burst(context.getSource())));
        event.getDispatcher().register(root);
    }

    private static int status(CommandSourceStack source) {
        return print(source, "bandrift-status", BandRiftRenderer.statusLines());
    }

    /** Full S2/M1 observable dump; the same fields the CASTAWAYL capture checklist reads. */
    private static int diag(CommandSourceStack source) {
        return print(source, "bandrift-diag", BandRiftRenderer.diagLines());
    }

    private static int print(CommandSourceStack source, String prefix, List<String> lines) {
        for (String line : lines) {
            LOGGER.info("[{}] {}", prefix, line);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    private static int mode(CommandSourceStack source, String text) {
        BandRiftMode parsed = BandRiftMode.parse(text);
        if (parsed == null) {
            source.sendFailure(Component.literal("unknown mode '" + text + "', expected rift|membrane|legacy"));
            return 0;
        }
        BandRiftMode.set(parsed);
        String id = parsed.id();
        LOGGER.info("[bandrift-mode] {}", id);
        source.sendSuccess(() -> Component.literal("bandrift mode = " + id), false);
        return 1;
    }

    private static int open(CommandSourceStack source, String text) {
        if ("auto".equalsIgnoreCase(text)) {
            BandRiftRenderer.clearManualOpen();
            LOGGER.info("[bandrift-open] auto");
            source.sendSuccess(() -> Component.literal("bandrift open = auto"), false);
            return 1;
        }
        double value;
        try {
            value = Double.parseDouble(text);
        } catch (NumberFormatException exception) {
            source.sendFailure(Component.literal("expected 'auto' or a number in [0, 1], got '" + text + "'"));
            return 0;
        }
        if (!(value >= 0.0) || value > 1.0) {
            source.sendFailure(Component.literal("openFactor must be in [0, 1], got " + value));
            return 0;
        }
        BandRiftRenderer.setManualOpen(value);
        String formatted = String.format(Locale.ROOT, "%.2f", value);
        LOGGER.info("[bandrift-open] {}", formatted);
        source.sendSuccess(() -> Component.literal("bandrift open = " + formatted), false);
        return 1;
    }

    private static int burst(CommandSourceStack source) {
        BandRiftRenderer.manualBurst();
        source.sendSuccess(() -> Component.literal("bandrift burst fired"), false);
        return 1;
    }
}
