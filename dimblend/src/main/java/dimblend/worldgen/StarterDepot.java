package dimblend.worldgen;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.redstone.RoseQuartzLampBlock;
import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * New-world-only starter depot: a bedrock wall with a lit rose quartz lamp, carved once per
 * world. It hands out nothing — R7 removed the spawn chest and left its cell empty, so the
 * column above the lamp is air.
 */
@EventBusSubscriber(modid = "dimblend")
public final class StarterDepot {
    public static final int WALL_X = -16;
    private static final String DATA_ID = "dimblend_starter_depot";
    private static final SavedData.Factory<DepotData> FACTORY = new SavedData.Factory<>(DepotData::new,
            (tag, registries) -> new DepotData(tag), null);

    private StarterDepot() {}

    @SubscribeEvent(receiveCanceled = true)
    public static void onNewWorld(LevelEvent.CreateSpawnPosition event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            DepotData data = data(level);
            data.enabled = true;
            data.setDirty();
        }
    }

    @SubscribeEvent
    public static void onLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level
                && level.dimension() == DimBlendRegistries.ROTATING_LEVEL
                && level.getChunkSource().getGenerator() instanceof RotatingChunkGenerator generator) {
            generator.setStarterDepot(data(level.getServer().overworld()).enabled);
        }
    }

    private static DepotData data(ServerLevel overworld) {
        return overworld.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public static boolean removesTrack(int x) { return x >= -256 && x <= WALL_X; }

    public static boolean ownsCell(int x, int y, int z) {
        return x == WALL_X && Math.abs(z) <= 7 && y >= OakTrackCorridor.TRACK_Y
                && y < OakTrackCorridor.TRACK_Y + 7;
    }

    /** What the depot wall column at z=0 holds, counted from the track floor. */
    enum DepotCell { LAMP, AIR, BEDROCK }

    /**
     * Column layout at z=0, one cell per vertical offset: the lamp at the floor, the cell that
     * held the spawn chest before R7 empty, the opening above it empty, bedrock from dy 3 up.
     */
    static DepotCell cellAt(int dy) {
        if (dy == 0) return DepotCell.LAMP;
        if (dy == 1 || dy == 2) return DepotCell.AIR;
        return DepotCell.BEDROCK;
    }

    /** Called only for the decorating origin, never for a neighbor re-carve. */
    public static void place(ChunkAccess chunk) {
        if (chunk.getPos().x != -1 || (chunk.getPos().z != -1 && chunk.getPos().z != 0)) return;
        for (int z = Math.max(-7, chunk.getPos().getMinBlockZ());
                z <= Math.min(7, chunk.getPos().getMaxBlockZ()); z++) {
            for (int dy = 0; dy < 7; dy++) {
                BlockPos pos = new BlockPos(WALL_X, OakTrackCorridor.TRACK_Y + dy, z);
                var state = Blocks.BEDROCK.defaultBlockState();
                if (z == 0) state = depotState(cellAt(dy));
                chunk.setBlockState(pos, state, false);
            }
        }
    }

    private static BlockState depotState(DepotCell cell) {
        return switch (cell) {
            case LAMP -> AllBlocks.ROSE_QUARTZ_LAMP.getDefaultState()
                    .setValue(RoseQuartzLampBlock.POWERING, true);
            case AIR -> Blocks.AIR.defaultBlockState();
            case BEDROCK -> Blocks.BEDROCK.defaultBlockState();
        };
    }

    static final class DepotData extends SavedData {
        private boolean enabled;
        /** Kept in the save format for R6's one-time marker; nothing grants items any more. */
        private boolean supplied;
        DepotData() {}
        DepotData(CompoundTag tag) {
            enabled = tag.getBoolean("enabled");
            supplied = tag.getBoolean("supplied");
        }
        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putBoolean("enabled", enabled);
            tag.putBoolean("supplied", supplied);
            return tag;
        }
    }
}
