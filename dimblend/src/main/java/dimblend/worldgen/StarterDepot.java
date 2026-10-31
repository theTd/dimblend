package dimblend.worldgen;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.redstone.RoseQuartzLampBlock;
import dimblend.DimBlendRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** New-world-only depot; the inventory grant survives chunk purge and is never repeated. */
@EventBusSubscriber(modid = "dimblend")
public final class StarterDepot {
    public static final int WALL_X = -16;
    public static final BlockPos CHEST = new BlockPos(WALL_X, OakTrackCorridor.TRACK_Y + 1, 0);
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

    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || level.dimension() != DimBlendRegistries.ROTATING_LEVEL) return;
        DepotData data = data(level.getServer().overworld());
        if (!data.enabled || data.supplied || !level.hasChunkAt(CHEST)) return;
        if (level.getBlockEntity(CHEST) instanceof ChestBlockEntity chest) {
            chest.setItem(0, new ItemStack(AllBlocks.TRAIN_CONTROLS.asItem(), 6));
            chest.setChanged();
            data.supplied = true;
            data.setDirty();
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

    /** Called only for the decorating origin, never for a neighbor re-carve. */
    public static void place(ChunkAccess chunk) {
        if (chunk.getPos().x != -1 || (chunk.getPos().z != -1 && chunk.getPos().z != 0)) return;
        for (int z = Math.max(-7, chunk.getPos().getMinBlockZ());
                z <= Math.min(7, chunk.getPos().getMaxBlockZ()); z++) {
            for (int dy = 0; dy < 7; dy++) {
                BlockPos pos = new BlockPos(WALL_X, OakTrackCorridor.TRACK_Y + dy, z);
                var state = Blocks.BEDROCK.defaultBlockState();
                if (z == 0 && dy == 0) state = AllBlocks.ROSE_QUARTZ_LAMP.getDefaultState()
                        .setValue(RoseQuartzLampBlock.POWERING, true);
                if (z == 0 && dy == 1) state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST);
                if (z == 0 && dy == 2) state = Blocks.AIR.defaultBlockState();
                chunk.setBlockState(pos, state, false);
                if (pos.equals(CHEST)) chunk.setBlockEntity(new ChestBlockEntity(pos, state));
            }
        }
    }

    static final class DepotData extends SavedData {
        private boolean enabled;
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
