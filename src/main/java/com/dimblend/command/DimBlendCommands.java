package com.dimblend.command;

import com.dimblend.DimBlendRegistries;
import com.dimblend.worldgen.BandIndex;
import com.dimblend.worldgen.BandLayout;
import com.dimblend.worldgen.OverworldSlice;
import com.dimblend.worldgen.RotatingChunkGenerator;
import com.dimblend.worldgen.SlicedOverworldChunkGenerator;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.levelgen.RandomState;

public final class DimBlendCommands {
    private static final DynamicCommandExceptionType UNKNOWN_BAND = new DynamicCommandExceptionType(
            value -> Component.literal("unknown band: " + value)
    );

    private DimBlendCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dimblend")
                        .requires(source -> source.hasPermission(2))
                        .executes(context -> teleport(context.getSource(), 0, true))
                        .then(Commands.argument("band", StringArgumentType.word())
                                .suggests(bandSuggestions())
                                .executes(context -> teleport(
                                        context.getSource(),
                                        parseBand(context.getSource(), StringArgumentType.getString(context, "band")),
                                        false
                                )))
                        .then(Commands.literal("sample")
                                .then(Commands.argument("band", StringArgumentType.word())
                                        .suggests(bandSuggestions())
                                        .executes(context -> sampleHeights(
                                                context.getSource(),
                                                parseBand(context.getSource(), StringArgumentType.getString(context, "band"))
                                        ))))
        );
    }

    private static SuggestionProvider<CommandSourceStack> bandSuggestions() {
        return (context, builder) -> SharedSuggestionProvider.suggest(bandNames(context.getSource()), builder);
    }

    private static List<String> bandNames(CommandSourceStack source) {
        List<String> names = new ArrayList<>();
        names.add("origin");
        for (int i = 0; i <= 40; i++) {
            names.add(Integer.toString(i));
        }
        return names;
    }

    private static int parseBand(CommandSourceStack source, String raw) throws CommandSyntaxException {
        String value = raw.toLowerCase();
        if (value.equals("origin")) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            throw UNKNOWN_BAND.create(raw);
        }
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
        Integer undergroundY = undergroundLandingY(generator, x, z, level);
        if (isUndergroundColumn(generator, x) && undergroundY == null) {
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
        String bandLabel = origin ? "origin" : Integer.toString(bandIndex);
        source.sendSuccess(
                () -> Component.literal("Teleported to dimblend " + bandLabel + " at " + x + " " + y + " " + z),
                true
        );
        return 1;
    }

    private static int sampleHeights(CommandSourceStack source, int bandIndex) {
        MinecraftServer server = source.getServer();
        ServerLevel level = server.getLevel(DimBlendRegistries.ROTATING_LEVEL);
        if (level == null) {
            source.sendFailure(Component.literal("dimblend dimension not loaded"));
            return 0;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            source.sendFailure(Component.literal("rotating generator not loaded"));
            return 0;
        }
        int x0 = bandIndex * BandIndex.DEFAULT_BAND_SIZE + BandIndex.DEFAULT_BAND_SIZE / 2;
        int delegateIndex = rotating.layout().delegateIndex(bandIndex);
        ChunkGenerator delegate = rotating.delegates().get(delegateIndex);
        LevelHeightAccessor sourceHeight = LevelHeightAccessor.create(delegate.getMinY(), delegate.getGenDepth());
        RandomState random = rotating.delegateRandom(delegateIndex);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        long sum = 0;
        int samples = 0;
        for (int dx = 0; dx < 256; dx += 16) {
            for (int dz = 0; dz < 256; dz += 16) {
                int height = delegate.getBaseHeight(
                        x0 + dx,
                        dz,
                        Heightmap.Types.OCEAN_FLOOR_WG,
                        sourceHeight,
                        random
                );
                min = Math.min(min, height);
                max = Math.max(max, height);
                sum += height;
                samples++;
            }
        }
        final int sampleCount = samples;
        final int minHeight = min;
        final int maxHeight = max;
        final int meanHeight = (int) (sum / samples);
        final int originX = x0;
        String label = "region" + bandIndex;
        source.sendSuccess(
                () -> Component.literal(
                        "sample " + label + " ocean_floor_wg n=" + sampleCount
                                + " min=" + minHeight + " mean=" + meanHeight + " max=" + maxHeight
                                + " at x=" + originX
                ),
                true
        );
        return sampleCount;
    }

    private static boolean isUndergroundColumn(ChunkGenerator generator, int x) {
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return false;
        }
        ChunkGenerator delegate = rotating.delegates().get(
                rotating.layout().delegateIndex(BandLayout.regionOfBlockX(x, rotating.bandSize()))
        );
        return delegate instanceof SlicedOverworldChunkGenerator sliced
                && sliced.slice() != OverworldSlice.SURFACE;
    }

    private static Integer undergroundLandingY(ChunkGenerator generator, int x, int z, ServerLevel level) {
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return null;
        }
        ChunkGenerator delegate = rotating.delegates().get(
                rotating.layout().delegateIndex(BandLayout.regionOfBlockX(x, rotating.bandSize()))
        );
        if (!(delegate instanceof SlicedOverworldChunkGenerator sliced)
                || sliced.slice() == OverworldSlice.SURFACE) {
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
