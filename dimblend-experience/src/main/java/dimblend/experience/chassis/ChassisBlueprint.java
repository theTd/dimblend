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
    public String translationKey() { return "chassis.dimblend_experience." + name().toLowerCase(java.util.Locale.ROOT); }
}
