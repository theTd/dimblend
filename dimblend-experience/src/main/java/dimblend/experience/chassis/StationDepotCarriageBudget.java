package dimblend.experience.chassis;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * R6 gate: how many cars the save already holds.
 *
 * <p><b>What the requirement asks.</b> The depot hands out a free carriage only while the
 * save holds at most a set number of force-loaded Sable structures that contain
 * chassis-frame blocks; past that it is skipped. The number is the panel's
 * {@code station_carriage_limit} (3-11, default 6).
 *
 * <p><b>Why the counting is a pure decision plus a level-backed walk.</b> The interesting
 * part — "is this structure a car, and does the count still fit" — is arithmetic nobody can
 * check in game, so it lives in {@link #admits} and {@link #holdsFrameBlock}, and the walk
 * over the world only feeds them. Tests pin the decision without a running server.
 *
 * <p><b>Why force-loaded, and why whole-save.</b> A car the player left somewhere unloaded
 * is not "available to connect" in any sense the player can see, and Sable keeps a
 * force-loaded car alive across chunk unloads — so the force-load ticket set is exactly the
 * set of cars that exist right now. The requirement says "存档内", so every level of the
 * save is counted, not only the rotating dimension.
 */
public final class StationDepotCarriageBudget {
    private StationDepotCarriageBudget() {
    }

    /**
     * Whether one more car may be handed out.
     *
     * @param existing force-loaded frame-carrying structures already in the save
     * @param limit    the panel's limit
     */
    public static boolean admits(int existing, int limit) {
        return existing < limit;
    }

    /**
     * Whether a car block counts as a chassis frame.
     *
     * <p>「车架方块」 already has one definition in this crew: {@code dimblend.carwash.chassis
     * .ChassisBlocks.isChassis} calls the inverted creative copycat panel and the vertical
     * copycat slab the frame. It also excludes the clean variants, but only because those
     * never take dirt — a car built by this crew's own chassis code uses them for the
     * end steps ({@code N} in the blueprint), so a clean slab on its own is still a car
     * block.
     *
     * <p><b>Matched by block id, not by orientation.</b> The carwash identity also looks at
     * {@code FACING} and {@code SLAB_TYPE}. Here it does not, and that is deliberate:
     * the creative copycat blocks exist only to build cars, so counting a structure that
     * merely contains one errs toward counting it. Erring that way makes the gate open
     * <b>sooner</b>, which is the safe direction — the requirement's purpose is a ceiling on
     * free cars, and handing out one too few is recoverable while handing out too many is not.
     *
     * <p>Matched on the registry id so no implementation class is imported across the module
     * boundary, the same rule {@code dimblend.carwash.chassis.ChassisBlocks} follows.
     */
    public static boolean holdsFrameBlock(String blockId) {
        if (blockId == null) {
            return false;
        }
        return switch (blockId) {
            case "dimblend_blocks:creative_copycat_panel",
                    "dimblend_blocks:creative_copycat_slab",
                    "dimblend_blocks:creative_clean_copycat_slab" -> true;
            default -> false;
        };
    }

    /** Force-loaded Sable structures in {@code level} that carry at least one frame block. */
    public static int countCars(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        int cars = 0;
        for (ServerSubLevel subLevel : container.collectForceLoadedSubLevels()) {
            if (subLevel.isRemoved()) {
                continue;
            }
            if (carriesFrame(subLevel)) {
                cars++;
            }
        }
        return cars;
    }

    /** Every level of the save, because the requirement counts the save rather than a dimension. */
    public static int countCarsEverywhere(ServerLevel anyLevel) {
        int cars = 0;
        if (anyLevel.getServer() == null) {
            return 0;
        }
        for (ServerLevel level : anyLevel.getServer().getAllLevels()) {
            cars += countCars(level);
        }
        return cars;
    }

    /** Whether this structure holds a frame block, walking its plot the same way the voxelizer does. */
    static boolean carriesFrame(ServerSubLevel subLevel) {
        LevelPlot plot = subLevel.getPlot();
        if (plot == null) {
            return false;
        }
        var bounds = plot.getBoundingBox();
        for (PlotChunkHolder holder : plot.getLoadedChunks()) {
            LevelChunk chunk = holder.getChunk();
            ChunkPos chunkPos = chunk.getPos();
            LevelChunkSection[] sections = chunk.getSections();
            for (int s = 0; s < sections.length; s++) {
                LevelChunkSection section = sections[s];
                if (section.hasOnlyAir()) {
                    continue;
                }
                int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(s));
                int y0 = Math.max(0, bounds.minY() - baseY);
                int y1 = Math.min(15, bounds.maxY() - baseY);
                if (y0 > y1) {
                    continue;
                }
                int x0 = Math.max(0, bounds.minX() - chunkPos.getMinBlockX());
                int x1 = Math.min(15, bounds.maxX() - chunkPos.getMinBlockX());
                int z0 = Math.max(0, bounds.minZ() - chunkPos.getMinBlockZ());
                int z1 = Math.min(15, bounds.maxZ() - chunkPos.getMinBlockZ());
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        for (int x = x0; x <= x1; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) {
                                continue;
                            }
                            if (holdsFrameBlock(String.valueOf(
                                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                                            state.getBlock())))) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}