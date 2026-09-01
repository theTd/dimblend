package com.dimblend.command;

import com.dimblend.DimBlendRegistries;
import com.dimblend.worldgen.BandIndex;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;

public final class DimBlendCommands {
    private static final String[] BAND_NAMES = {"overworld", "end", "nether", "twilight"};
    private static final DynamicCommandExceptionType UNKNOWN_BAND = new DynamicCommandExceptionType(
            value -> Component.literal("unknown band: " + value)
    );
    private static final SuggestionProvider<CommandSourceStack> BAND_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(List.of("overworld", "end", "nether", "twilight", "0", "1", "2", "3"), builder);

    private DimBlendCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dimblend")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> teleport(context.getSource(), 0, true))
                        .then(Commands.argument("band", StringArgumentType.word())
                                .suggests(BAND_SUGGESTIONS)
                                .executes(context -> teleport(
                                        context.getSource(),
                                        parseBand(StringArgumentType.getString(context, "band")),
                                        false
                                )))
        );
    }

    private static int parseBand(String raw) throws CommandSyntaxException {
        String value = raw.toLowerCase(Locale.ROOT);
        for (int i = 0; i < BAND_NAMES.length; i++) {
            if (BAND_NAMES[i].equals(value)) {
                return i;
            }
        }
        try {
            int index = Integer.parseInt(value);
            if (index >= 0 && index < BAND_NAMES.length) {
                return index;
            }
        } catch (NumberFormatException ignored) {
        }
        throw UNKNOWN_BAND.create(raw);
    }

    private static int teleport(CommandSourceStack source, int bandIndex, boolean origin) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            source.sendFailure(Component.literal("dimblend dimension not loaded"));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("dimblend requires a player"));
            return 0;
        }

        int x = origin ? 0 : bandIndex * BandIndex.DEFAULT_BAND_SIZE + BandIndex.DEFAULT_BAND_SIZE / 2;
        int z = 0;
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int sampledY = generator.getFirstFreeHeight(
                x,
                z,
                Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                level,
                level.getChunkSource().randomState()
        );
        final int y = sampledY <= level.getMinBuildHeight()
                ? Math.max(level.getMinBuildHeight() + 1, generator.getSeaLevel())
                : sampledY;

        player.teleportTo(level, x + 0.5, y, z + 0.5, Set.of(), player.getYRot(), player.getXRot());
        String bandLabel = origin ? "origin" : BAND_NAMES[bandIndex];
        source.sendSuccess(
                () -> Component.literal("Teleported to dimblend " + bandLabel + " at " + x + " " + y + " " + z),
                true
        );
        return 1;
    }
}
