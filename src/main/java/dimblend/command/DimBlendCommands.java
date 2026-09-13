package dimblend.command;

import dimblend.DimBlend;
import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandIndex;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.OverworldSlice;
import dimblend.worldgen.PregenConfig;
import dimblend.worldgen.PregenController;
import dimblend.worldgen.RotatingChunkGenerator;
import dimblend.worldgen.SlicedOverworldChunkGenerator;
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
                        .then(Commands.literal("sample")
                                .then(Commands.argument("band", StringArgumentType.word())
                                        .suggests(bandSuggestions())
                                        .executes(context -> sampleHeights(
                                                context.getSource(),
                                                parseBand(context.getSource(), StringArgumentType.getString(context, "band"))
                                        ))))
                        .then(Commands.literal("find")
                                .then(Commands.argument("lane", StringArgumentType.word())
                                        .suggests(laneSuggestions())
                                        .executes(context -> findLane(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "lane")
                                        ))))
                        .then(Commands.literal("pregen")
                                .executes(context -> dumpPregen(context.getSource()))
                                .then(Commands.literal("on")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> setPregenOverride(context.getSource(), true, "on")))
                                .then(Commands.literal("off")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> setPregenOverride(context.getSource(), false, "off")))
                                .then(Commands.literal("auto")
                                        .requires(source -> source.hasPermission(2))
                                        .executes(context -> setPregenOverride(context.getSource(), null, "auto"))))
                        .then(Commands.literal("watch")
                                .executes(context -> dumpWatch(context.getSource()))
                                .then(Commands.literal("on")
                                        .executes(context -> setWatch(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> setWatch(context.getSource(), false))))
                        .then(Commands.literal("watchdog")
                                .executes(context -> dumpWatchdog(context.getSource()))
                                .then(Commands.literal("on")
                                        .executes(context -> setWatchdog(context.getSource(), true)))
                                .then(Commands.literal("off")
                                        .executes(context -> setWatchdog(context.getSource(), false))))
                        .then(Commands.argument("band", StringArgumentType.word())
                                .suggests(bandSuggestions())
                                .executes(context -> teleport(
                                        context.getSource(),
                                        parseBand(context.getSource(), StringArgumentType.getString(context, "band")),
                                        false
                                )))
        );
    }

    private static SuggestionProvider<CommandSourceStack> laneSuggestions() {
        return (context, builder) -> SharedSuggestionProvider.suggest(laneNames(context.getSource()), builder);
    }

    private static SuggestionProvider<CommandSourceStack> bandSuggestions() {
        return (context, builder) -> SharedSuggestionProvider.suggest(bandNames(context.getSource()), builder);
    }

    private static List<String> laneNames(CommandSourceStack source) {
        List<String> names = new ArrayList<>();
        MinecraftServer server = source.getServer();
        ServerLevel level = server != null ? server.getLevel(DimBlendRegistries.ROTATING_LEVEL) : null;
        if (level == null) {
            return names;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return names;
        }
        for (ChunkGenerator delegate : rotating.delegates()) {
            String name = BandLayout.laneName(delegate);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private static int findLane(CommandSourceStack source, String lane) throws CommandSyntaxException {
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
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            source.sendFailure(Component.literal("dimblend dimension not loaded"));
            return 0;
        }
        int bandSize = rotating.bandSize();
        int from = BandLayout.regionOfBlockX(player.blockPosition().getX(), bandSize);
        Integer found = null;
        for (int radius = 0; radius <= 1024 && found == null; radius++) {
            for (int candidate : new int[]{from + radius, from - radius}) {
                ChunkGenerator delegate = rotating.delegates().get(
                        rotating.layout().delegateIndex(candidate)
                );
                if (BandLayout.laneName(delegate).equals(lane)) {
                    found = candidate;
                    break;
                }
            }
        }
        if (found == null) {
            source.sendFailure(Component.literal("no band with lane " + lane + " within 1024 bands"));
            return 0;
        }
        return teleport(source, found, false);
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

        ChunkGenerator generator = level.getChunkSource().getGenerator();
        int bandSize = generator instanceof RotatingChunkGenerator rotating
                ? rotating.bandSize()
                : BandIndex.DEFAULT_BAND_SIZE;
        int x = origin ? 0 : bandIndex * bandSize + bandSize / 2;
        int z = 0;
        Integer undergroundY = undergroundLandingY(generator, x, z, level);
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
        int bandSize = rotating.bandSize();
        int x0 = bandIndex * bandSize + bandSize / 2;
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

    private static int setPregenOverride(CommandSourceStack source, Boolean override, String label) {
        DimBlend.pregen().setPregenOverride(override);
        PregenController.Snapshot snapshot = DimBlend.pregen().snapshot(source.getServer());
        String configState = PregenConfig.ENABLED.get() ? "on" : "off";
        String mode = "pregen " + label + ": effective " + (snapshot.enabled() ? "on" : "off")
                + (snapshot.enabled() == PregenConfig.ENABLED.get() ? "" : " (config " + configState + ")")
                + ", inFlight " + snapshot.inFlight();
        source.sendSuccess(() -> Component.literal(mode), false);
        return 1;
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

    private static int dumpWatch(CommandSourceStack source) {
        for (String line : DimBlend.monitor().dump(source.getServer())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

    private static int setWatch(CommandSourceStack source, boolean on) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("watch on/off requires a player"));
            return 0;
        }
        if (on) {
            DimBlend.monitor().watchOn(player);
        } else {
            DimBlend.monitor().watchOff(player);
        }
        source.sendSuccess(() -> Component.literal("chunkgen watch " + (on ? "on" : "off")), false);
        return 1;
    }

    private static int dumpWatchdog(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("hang watchdog " + (DimBlend.watchdog().isEnabled() ? "on" : "off")), false);
        return 1;
    }

    private static int setWatchdog(CommandSourceStack source, boolean on) {
        if (on) {
            DimBlend.watchdog().enable(source.getServer());
        } else {
            DimBlend.watchdog().disable();
        }
        source.sendSuccess(() -> Component.literal("hang watchdog " + (on ? "on" : "off")), false);
        return 1;
    }

    private static int dumpPregen(CommandSourceStack source) {
        for (String line : DimBlend.pregen().snapshot(source.getServer()).lines()) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return 1;
    }

}
