package dimblend.spyglass.server;

import dimblend.spyglass.structure.OutlineBox;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * Buckets a structure's piece boxes by 16³ section so {@link BuriedBlockFilter} can answer "is this
 * cell inside a piece?" without scanning every piece for every visited cell.
 *
 * <p>Pieces come from the saved start and are validated as packable before they reach this class
 * ({@link StructureShapeExtractor#coveringChunks}), so section coordinates are well inside
 * {@link net.minecraft.core.BlockPos#asLong}'s range; the section key is simply the block key of the
 * section's minimum corner. A piece spanning many sections is stored in each of them, which is
 * proportional to its volume (one entry per 4096 cells) and therefore bounded by the extraction's
 * visited-cell budget.
 */
final class PieceBoxIndex implements BuriedBlockFilter.PieceMembership {
    private static final int SECTION_SIZE = 16;
    private static final int SECTION_SHIFT = 4;

    private final Long2ObjectOpenHashMap<List<OutlineBox>> bySection = new Long2ObjectOpenHashMap<>();

    PieceBoxIndex(List<OutlineBox> pieces) {
        for (OutlineBox piece : pieces) {
            int minSectionX = piece.minX() >> SECTION_SHIFT;
            int maxSectionX = piece.maxX() >> SECTION_SHIFT;
            int minSectionY = piece.minY() >> SECTION_SHIFT;
            int maxSectionY = piece.maxY() >> SECTION_SHIFT;
            int minSectionZ = piece.minZ() >> SECTION_SHIFT;
            int maxSectionZ = piece.maxZ() >> SECTION_SHIFT;
            for (int sectionX = minSectionX; sectionX <= maxSectionX; sectionX++) {
                for (int sectionY = minSectionY; sectionY <= maxSectionY; sectionY++) {
                    for (int sectionZ = minSectionZ; sectionZ <= maxSectionZ; sectionZ++) {
                        this.bySection
                                .computeIfAbsent(
                                        sectionKey(sectionX, sectionY, sectionZ),
                                        ignored -> new ArrayList<>(1))
                                .add(piece);
                    }
                }
            }
        }
    }

    @Override
    public boolean contains(int x, int y, int z) {
        List<OutlineBox> pieces = this.bySection.get(
                sectionKey(x >> SECTION_SHIFT, y >> SECTION_SHIFT, z >> SECTION_SHIFT));
        if (pieces == null) {
            return false;
        }
        for (int index = 0; index < pieces.size(); index++) {
            OutlineBox piece = pieces.get(index);
            if (x >= piece.minX() && x <= piece.maxX()
                    && y >= piece.minY() && y <= piece.maxY()
                    && z >= piece.minZ() && z <= piece.maxZ()) {
                return true;
            }
        }
        return false;
    }

    int sectionBuckets() {
        return this.bySection.size();
    }

    private static long sectionKey(int sectionX, int sectionY, int sectionZ) {
        return BlockPos.asLong(
                sectionX * SECTION_SIZE, sectionY * SECTION_SIZE, sectionZ * SECTION_SIZE);
    }
}
