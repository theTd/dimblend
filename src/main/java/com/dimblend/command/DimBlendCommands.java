package com.dimblend.command;

import com.dimblend.DimBlendRegistries;
import com.dimblend.worldgen.BandIndex;
import com.dimblend.worldgen.OverworldSlice;
import com.dimblend.worldgen.RotatingChunkGenerator;
import com.dimblend.worldgen.SlicedOverworldChunkGenerator;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public final class DimBlendCommands {
    private static final String[] BAND_NAMES = {"overworld", "overworld_caves", "end", "nether", "twilight"};
    private static final DynamicCommandExceptionType UNKNOWN_BAND = new DynamicCommandExceptionType(
            value -> Component.literal("unknown band: " + value)
    );
    private static final SuggestionProvider<CommandSourceStack> BAND_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(List.of("overworld", "overworld_caves", "end", "nether", "twilight", "0", "1", "2", "3", "4"), builder);

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
        Integer undergroundY = undergroundLandingY(generator, bandIndex, x, z, level);
        if (isUndergroundBand(generator, bandIndex) && undergroundY == null) {
            source.sendFailure(Component.literal("no safe landing in overworld caves band"));
            return 0;
        }
        int sampledY = undergroundY != null
                ? undergroundY
                : generator.getFirstFreeHeight(
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

    private static boolean isUndergroundBand(ChunkGenerator generator, int bandIndex) {
        if (!(generator instanceof RotatingChunkGenerator rotating) || bandIndex < 0 || bandIndex >= rotating.delegates().size()) {
            return false;
        }
        ChunkGenerator delegate = rotating.delegates().get(bandIndex);
        return delegate instanceof SlicedOverworldChunkGenerator sliced
                && sliced.slice() == OverworldSlice.UNDERGROUND;
    }

    private static Integer undergroundLandingY(ChunkGenerator generator, int bandIndex, int x, int z, ServerLevel level) {
        if (!(generator instanceof RotatingChunkGenerator rotating) || bandIndex < 0 || bandIndex >= rotating.delegates().size()) {
            return null;
        }
        ChunkGenerator delegate = rotating.delegates().get(bandIndex);
        if (!(delegate instanceof SlicedOverworldChunkGenerator sliced)
                || sliced.slice() != OverworldSlice.UNDERGROUND) {
            return null;
        }
        var column = rotating.getBaseColumn(x, z, level, level.getChunkSource().randomState());
        int minY = Math.max(sliced.slice().targetMinY() + 1, level.getMinBuildHeight() + 1);
        int maxY = Math.min(sliced.slice().sealY() - 2, level.getMaxBuildHeight() - 2);
        for (int feet = maxY; feet >= minY; feet--) {
            BlockState feetState = column.getBlock(feet);
            BlockState headState = column.getBlock(feet + 1);
            BlockState floor = column.getBlock(feet - 1);
            if (!feetState.blocksMotion()
                    && feetState.getFluidState().isEmpty()
                    && !headState.blocksMotion()
                    && headState.getFluidState().isEmpty()
                    && floor.blocksMotion()) {
                return feet;
            }
        }
        return null;
    }
}
