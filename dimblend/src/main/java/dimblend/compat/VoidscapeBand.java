package dimblend.compat;

import dimblend.DimBlendRegistries;
import dimblend.worldgen.BandLayout;
import dimblend.worldgen.RotatingChunkGenerator;
import java.util.ArrayDeque;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Runtime predicates for the Voidscape latitude inside {@code dimblend:rotating}.
 * Never treats the whole rotating dimension as the void — other bands stay
 * outside {@code LevelUtil.isInVoidDimension}.
 */
public final class VoidscapeBand {
    public static final ResourceKey<Level> VOIDSCAPE_LEVEL = ResourceKey.create(
            Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("voidscape", "void"));

    private static final ThreadLocal<ArrayDeque<BlockPos>> POSITION = ThreadLocal.withInitial(ArrayDeque::new);
    /** Pushed when {@link #enter} is a pairing no-op so {@link #exit} stays balanced. */
    private static final BlockPos SKIP = new BlockPos(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE);

    private VoidscapeBand() {
    }

    public static boolean isRotating(LevelAccessor level) {
        return level instanceof Level world && world.dimension() == DimBlendRegistries.ROTATING_LEVEL;
    }

    public static boolean isVoidscapeBiome(Holder<Biome> biome) {
        return biome.unwrapKey()
                .map(key -> VoidscapeNetherDezombifyRules.VOIDSCAPE_NAMESPACE.equals(key.location().getNamespace()))
                .orElse(false);
    }

    /**
     * Server uses the band layout (seed-accurate at partition walls). Client
     * has no generator, so it falls back to the voidscape biome namespace.
     */
    public static boolean isVoidscapePos(LevelAccessor level, BlockPos pos) {
        if (!isRotating(level)) {
            return false;
        }
        if (level instanceof ServerLevel server) {
            return isVoidscapeColumn(server, pos.getX());
        }
        return isVoidscapeBiome(level.getBiome(pos));
    }

    public static boolean isVoidscapeColumn(LevelAccessor level, int blockX) {
        if (!(level instanceof Level world) || world.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return false;
        }
        ChunkGenerator generator = chunkGenerator(world);
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return false;
        }
        return BandLayout.isVoidscape(rotating.delegateForBlockX(blockX));
    }

    /**
     * True when a {@link #run} (or enter/exit) position is on the rotating
     * voidscape lane. Callers without a pushed position get false — client
     * light/fog use {@link dimblend.client.ClientBandLane} instead.
     */
    public static boolean rotatingVoidscapeMatches(@Nullable Level level) {
        if (level == null || !isRotating(level)) {
            return false;
        }
        BlockPos pos = contextPos();
        return pos != null && isVoidscapePos(level, pos);
    }

    public static void run(LevelAccessor level, BlockPos pos, Runnable action) {
        call(level, pos, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T call(LevelAccessor level, BlockPos pos, Supplier<T> action) {
        enter(level, pos);
        try {
            return action.get();
        } finally {
            exit();
        }
    }

    public static void enter(LevelAccessor level, BlockPos pos) {
        if (!isRotating(level)) {
            POSITION.get().push(SKIP);
            return;
        }
        POSITION.get().push(pos.immutable());
    }

    public static void enterEntity(Entity entity) {
        enter(entity.level(), entity.blockPosition());
    }

    public static void exit() {
        ArrayDeque<BlockPos> stack = POSITION.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
        if (stack.isEmpty()) {
            POSITION.remove();
        }
    }

    @Nullable
    public static BlockPos contextPos() {
        BlockPos pos = POSITION.get().peek();
        return pos == null || pos == SKIP ? null : pos;
    }

    public static void onPlayerTickEnter(PlayerTickEvent.Pre event) {
        enterEntity(event.getEntity());
    }

    public static void onPlayerTickExit(PlayerTickEvent.Post event) {
        exit();
    }

    public static void onSpawnPlacementEnter(MobSpawnEvent.SpawnPlacementCheck event) {
        enter(event.getLevel().getLevel(), event.getPos());
    }

    public static void onSpawnPlacementExit(MobSpawnEvent.SpawnPlacementCheck event) {
        exit();
    }

    public static void onSpawnPositionEnter(MobSpawnEvent.PositionCheck event) {
        enter(event.getLevel().getLevel(), BlockPos.containing(event.getX(), event.getY(), event.getZ()));
    }

    public static void onSpawnPositionExit(MobSpawnEvent.PositionCheck event) {
        exit();
    }

    public static void onFinalizeSpawnEnter(FinalizeSpawnEvent event) {
        enter(event.getLevel().getLevel(), BlockPos.containing(event.getX(), event.getY(), event.getZ()));
    }

    public static void onFinalizeSpawnExit(FinalizeSpawnEvent event) {
        exit();
    }

    @Nullable
    private static ChunkGenerator chunkGenerator(Level world) {
        if (world instanceof ServerLevel server) {
            return server.getChunkSource().getGenerator();
        }
        return null;
    }
}
