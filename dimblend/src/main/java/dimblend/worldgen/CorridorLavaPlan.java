package dimblend.worldgen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** A complete read phase precedes every write; no Minecraft registries are needed here. */
public final class CorridorLavaPlan {
    public static final int MIN_SOURCE_Y = 67;
    public static final int CLEAR_FLOOR_Y = 66;
    /** Negative-side source band; the central corridor {@code z in [-10, 10]} is never hollowed. */
    public static final int LOWER_BAND_MIN_Z = -48;
    public static final int LOWER_BAND_MAX_Z = -11;
    /** Positive-side source band; the central corridor {@code z in [-10, 10]} is never hollowed. */
    public static final int UPPER_BAND_MIN_Z = 11;
    public static final int UPPER_BAND_MAX_Z = 48;

    public record Cell(int x, int y, int z) {
        public Cell offset(int dx, int dy, int dz) {
            return new Cell(x + dx, y + dy, z + dz);
        }
    }

    private record Column(int x, int z) { }

    private final Set<Cell> glass = new HashSet<>();
    private final Map<Column, Integer> clearCeilings = new HashMap<>();

    public static boolean inSourceRange(int y, int z) {
        return y >= MIN_SOURCE_Y
                && ((z >= LOWER_BAND_MIN_Z && z <= LOWER_BAND_MAX_Z)
                    || (z >= UPPER_BAND_MIN_Z && z <= UPPER_BAND_MAX_Z));
    }

    /** Whether a chunk's {@code [minZ, maxZ]} touches either band's one-block glass overhang. */
    public static boolean touchesOutput(int minZ, int maxZ) {
        return (maxZ >= LOWER_BAND_MIN_Z - 1 && minZ <= LOWER_BAND_MAX_Z + 1)
                || (maxZ >= UPPER_BAND_MIN_Z - 1 && minZ <= UPPER_BAND_MAX_Z + 1);
    }

    /** Whether a chunk's {@code [minZ, maxZ]} can contain a source at all (no overhang). */
    public static boolean intersectsSourceBand(int minZ, int maxZ) {
        return (maxZ >= LOWER_BAND_MIN_Z && minZ <= LOWER_BAND_MAX_Z)
                || (maxZ >= UPPER_BAND_MIN_Z && minZ <= UPPER_BAND_MAX_Z);
    }

    public void openSource(Cell source, Iterable<Cell> originalHorizontalAir) {
        clearCeilings.merge(new Column(source.x, source.z), source.y - 1, Math::max);
        originalHorizontalAir.forEach(glass::add);
    }

    public boolean clears(Cell cell) {
        Integer ceiling = clearCeilings.get(new Column(cell.x, cell.z));
        return ceiling != null && cell.y >= CLEAR_FLOOR_Y && cell.y <= ceiling;
    }

    /** Clear shafts win over glass, including lower original sources. */
    public void forEachClear(Consumer<Cell> action) {
        clearCeilings.forEach((column, ceiling) -> {
            for (int y = CLEAR_FLOOR_Y; y <= ceiling; y++) {
                Cell cell = new Cell(column.x, y, column.z);
                action.accept(cell);
            }
        });
    }

    public void forEachGlass(Consumer<Cell> action) {
        glass.forEach(cell -> {
            if (!clears(cell)) action.accept(cell);
        });
    }
}
