package dimblend.experience.client.headlight;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dimblend.experience.compat.dndecor.FrontlightSpotlight;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Indexes lit and unlit frontlights in loaded world and Sable plot chunks. Client thread only. */
public final class FrontlightIndex {
    private static final Map<LevelChunk, Set<BlockPos>> LAMPS = new IdentityHashMap<>();
    private static ClientLevel level;
    private static boolean bootstrapNeeded = true;

    public static boolean isFrontlight(BlockState state) {
        return FrontlightSpotlight.isFrontlight(state);
    }

    public static void onLoad(ChunkEvent.Load event) {
        if (event.getLevel() == Minecraft.getInstance().level && event.getChunk() instanceof LevelChunk chunk) {
            ensureLevel(Minecraft.getInstance().level);
            scan(chunk); // A packet can replace the contents of an existing chunk object.
        }
    }

    public static void onUnload(ChunkEvent.Unload event) {
        if (event.getLevel() == level) LAMPS.remove(event.getChunk());
    }

    public static void clear() {
        LAMPS.clear();
        level = null;
        bootstrapNeeded = true;
    }

    private static void ensureLevel(ClientLevel current) {
        if (level != current) {
            clear();
            level = current;
        }
    }

    /** Bootstraps loads before sublevel registration, and removes chunks without Sable unload events. */
    public static Map<LevelChunk, Set<BlockPos>> reconcile(ClientLevel current, Vec3 camera) {
        ensureLevel(current);
        if (bootstrapNeeded) {
            // Some world chunks arrive before Minecraft.level is assigned. Bootstrap once;
            // subsequent arrivals and replacements are handled by ChunkEvent.Load.
            var mc = Minecraft.getInstance();
            ChunkPos center = mc.player == null ? new ChunkPos(BlockPos.containing(camera)) : mc.player.chunkPosition();
            int radius = mc.options.getEffectiveRenderDistance() + 3;
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                for (int x = center.x - radius; x <= center.x + radius; x++) {
                    LevelChunk chunk = current.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk != null && !LAMPS.containsKey(chunk)) scan(chunk);
                }
            }
            bootstrapNeeded = false;
        }
        Set<LevelChunk> live = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var sub : SubLevelContainer.getContainer(current).getAllSubLevels()) {
            for (var holder : sub.getPlot().getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) continue;
                live.add(chunk);
                if (!LAMPS.containsKey(chunk)) scan(chunk);
            }
        }
        LAMPS.keySet().removeIf(chunk -> !live.contains(chunk)
                && current.getChunkSource().getChunk(chunk.getPos().x, chunk.getPos().z, ChunkStatus.FULL, false) != chunk);
        return LAMPS;
    }

    private static void scan(LevelChunk chunk) {
        Set<BlockPos> lamps = new HashSet<>();
        var sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (!section.maybeHas(FrontlightIndex::isFrontlight)) continue;
            int baseY = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (isFrontlight(section.getBlockState(x, y, z))) {
                            lamps.add(new BlockPos(baseX + x, baseY + y, baseZ + z));
                        }
                    }
                }
            }
        }
        LAMPS.put(chunk, lamps);
    }

    /** Called after a successful client LevelChunk write, including Sable plot updates. */
    public static void blockChanged(LevelChunk chunk, BlockPos pos, BlockState state) {
        Set<BlockPos> lamps = LAMPS.get(chunk);
        if (lamps == null) return;
        if (isFrontlight(state)) lamps.add(pos.immutable());
        else lamps.remove(pos);
    }

    private FrontlightIndex() {}
}
