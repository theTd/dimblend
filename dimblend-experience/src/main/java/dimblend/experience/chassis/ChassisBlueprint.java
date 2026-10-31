package dimblend.experience.chassis;

import java.util.ArrayList;
import java.util.List;

/** Rows run from X-22 to X+22; columns run from Z-2 to Z+2. */
public enum ChassisBlueprint {
    POWER(List.of(
            "..C..", "..B..", "HHDHH", "P.S.P", "P.S.P", "P.W.P", "P.S.P", "P.S.P", "P.I.P",
            "P.S.P", "P.S.P", "P.W.P", "P...P", "P...P", "H...H", "H...H", "H...H", "H...H",
            "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H",
            "H...H", "H...H", "H...H", "H...H", "P...P", "P...P", "P.W.P", "P.S.P", "P.S.P",
            "P.I.P", "P.S.P", "P.S.P", "P.W.P", "P.S.P", "P.S.P", "HHDHH", "..B..", "..C..")),
    CARRIAGE(List.of(
            "..C..", "..B..", "HHDHH", "P.N.P", "P.N.P", "P.W.P", "P.N.P", "P.N.P", "HNNNH",
            "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H",
            "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H",
            "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H", "H...H",
            "HNNNH", "P.N.P", "P.N.P", "P.W.P", "P.N.P", "P.N.P", "HHDHH", "..B..", "..C.."));

    public static final int LENGTH = 45;
    public static final int HALF_LENGTH = 22;
    public static final int SAFE_X = 24;
    public static final int SAFE_Z = 3;
    public record Cell(int x, int z, char symbol) {}
    private final List<String> rows;
    private final List<Cell> cells;

    ChassisBlueprint(List<String> rows) {
        if (rows.size() != 45 || rows.stream().anyMatch(row -> row.length() != 5)) {
            throw new IllegalArgumentException("Chassis must have 45 rows of 5 cells");
        }
        this.rows = rows;
        List<Cell> cells = new ArrayList<>();
        for (int row = 0; row < rows.size(); row++) {
            for (int col = 0; col < 5; col++) {
                char symbol = rows.get(row).charAt(col);
                if (symbol != '.') cells.add(new Cell(row - 22, col - 2, symbol));
            }
        }
        this.cells = List.copyOf(cells);
    }

    public List<Cell> cells() { return cells; }
    public String row(int index) { return rows.get(index); }

    /** Stress in simurail's 0..32 range, and whether the bogey frame may shift on both axes. */
    public record BogeyTuning(float stress, boolean allowOffset) {}

    /** Tuning for the bogey at {@code x}, or empty when no bogey sits there. */
    public static java.util.Optional<BogeyTuning> bogeyTuning(ChassisBlueprint blueprint, int x) {
        boolean bogey = blueprint.cells().stream()
                .anyMatch(cell -> cell.x() == x && (cell.symbol() == 'W' || cell.symbol() == 'I'));
        if (!bogey) return java.util.Optional.empty();
        if (blueprint == CARRIAGE) return java.util.Optional.of(new BogeyTuning(0.0F, false));
        // Ranked from the X+ end: the two end bogeys pull, the four inner ones only float.
        int rank = 0;
        int total = 0;
        for (var cell : blueprint.cells()) {
            if (cell.symbol() != 'W' && cell.symbol() != 'I') continue;
            if (cell.x() > x) rank++;
            total++;
        }
        boolean end = rank == 0 || rank == total - 1;
        return java.util.Optional.of(end ? new BogeyTuning(32.0F, false) : new BogeyTuning(0.0F, true));
    }
    public String translationKey() { return "chassis.dimblend_experience." + name().toLowerCase(java.util.Locale.ROOT); }
}
