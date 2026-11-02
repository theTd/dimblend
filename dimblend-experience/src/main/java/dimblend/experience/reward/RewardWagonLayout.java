package dimblend.experience.reward;

import java.util.ArrayList;
import java.util.List;

/**
 * The reward wagon's 9 x 3 blueprint, exactly as the requirement draws it.
 *
 * <p>Rows run along X (west to east), columns along Z (north, middle, south). The two
 * end rows carry nothing but the middle-column coupler; the two rows next to them carry
 * a double slab in the middle; the middle row carries the physical bogey.
 *
 * <p><b>The digits are not block kinds.</b> They mark which cells get ore at
 * {@code y + 1}: ten cells are marked {@code 1}, six are marked {@code 2}, and exactly
 * one — the bogey cell — is marked {@code 3}. The requirement's ore procedure talks
 * about "position 1/2/3", and those three groups are what it means.
 *
 * <p><b>Why this class holds no Minecraft types.</b> The cell table is a fact about the
 * requirement, not about a level; keeping it free of registry access lets the unit tests
 * read it without a game running.
 */
public final class RewardWagonLayout {
    /** What stands on one cell of the deck. */
    public enum CellKind {
        /** Nothing at all: the four corners of the two coupler rows. */
        AIR,
        /** {@code type=top} clean copycat slab. */
        SLAB,
        /** {@code type=double} clean copycat slab. */
        DOUBLE_SLAB,
        /** {@code simurail:automatic_coupler}. */
        COUPLER,
        /** {@code simurail:physics_bogey}. */
        BOGEY
    }

    /** One deck cell: offset from the wagon's centre, what it is, and its ore marker. */
    public record Cell(int dx, int dz, CellKind kind, int marker) {
    }

    /** Cells from the centre to either end. The wagon is {@code 2 * 4 + 1 = 9} long. */
    public static final int HALF_LENGTH = 4;
    /** Cells from the centre to either side. The wagon is {@code 2 * 1 + 1 = 3} wide. */
    public static final int HALF_WIDTH = 1;
    public static final int LENGTH = HALF_LENGTH * 2 + 1;
    public static final int WIDTH = HALF_WIDTH * 2 + 1;

    /** Deck height above the rails. The existing chassis parks on the same layer. */
    public static final int STRUCTURE_DY = 1;
    /** Content layer: one above the deck, i.e. above every brick's own y. */
    public static final int CONTENT_DY = 2;
    /** Ore that sits on top of a raw-ore block: one above the content layer. */
    public static final int ORE_TOP_DY = 3;

    /**
     * The drawing, one row per X offset, three characters per row.
     *
     * <ul>
     *   <li>{@code .} air</li>
     *   <li>{@code C} automatic coupler</li>
     *   <li>{@code b} physical bogey (marker {@code 3})</li>
     *   <li>{@code 0}, {@code 1}, {@code 2} slab with that ore marker</li>
     *   <li>{@code D} double slab, marker {@code 1}</li>
     * </ul>
     */
    private static final String[] DRAWING = {
            ".C.",
            "0D0",
            "121",
            "121",
            "2b2",
            "121",
            "121",
            "0D0",
            ".C.",
    };

    private RewardWagonLayout() {
    }

    /** Every deck cell, in row order then column order. */
    public static List<Cell> cells() {
        return CELLS;
    }

    /** The cells the drawing marks with {@code marker}, i.e. ore position 1, 2 or 3. */
    public static List<Cell> marked(int marker) {
        List<Cell> out = new ArrayList<>();
        for (Cell cell : CELLS) {
            if (cell.marker() == marker) {
                out.add(cell);
            }
        }
        return out;
    }

    /** The single cell carrying the physical bogey. */
    public static Cell bogey() {
        for (Cell cell : CELLS) {
            if (cell.kind() == CellKind.BOGEY) {
                return cell;
            }
        }
        throw new IllegalStateException("The drawing has no bogey");
    }

    /** The coupler cells: one at each end, middle column. */
    public static List<Cell> couplers() {
        List<Cell> out = new ArrayList<>();
        for (Cell cell : CELLS) {
            if (cell.kind() == CellKind.COUPLER) {
                out.add(cell);
            }
        }
        return out;
    }

    /** Cells that are not air: the 21 slabs plus the two couplers and the bogey. */
    public static List<Cell> structure() {
        List<Cell> out = new ArrayList<>();
        for (Cell cell : CELLS) {
            if (cell.kind() != CellKind.AIR) {
                out.add(cell);
            }
        }
        return out;
    }

    /**
     * The motor wagon's three extra cells, west of the bogey along X.
     *
     * <p>The requirement puts the clutch one step west of the bogey and the motor two steps
     * west, so power runs motor → clutch → bogey and shoves the wagon east. The lever sits on
     * top of the clutch.
     *
     * <p>The clutch is Create: Connected's {@code inverted_clutch}, which the player chose.
     * That block <b>passes no rotation until it is powered</b>, so a fresh wagon stands still
     * and a player who pulls the lever sets it moving. The player confirmed this reading
     * explicitly: "once the clutch engages it makes the wagon move in the needed direction —
     * it does not mean the wagon must already be moving when it spawns". So the lever starts
     * unpowered, and {@code POWERED=false} on the clutch is the resting state, not a bug.
     */
    public record MotorParts(Cell motor, Cell clutch, Cell lever) {
    }

    /** The motor, the clutch and the lever, in world-relative cell offsets. */
    public static MotorParts motorParts() {
        Cell bogey = bogey();
        Cell motor = new Cell(bogey.dx() - 2, bogey.dz(), CellKind.SLAB, 2);
        Cell clutch = new Cell(bogey.dx() - 1, bogey.dz(), CellKind.SLAB, 2);
        Cell lever = new Cell(bogey.dx() - 1, bogey.dz(), CellKind.SLAB, 2); // same column, one layer up
        return new MotorParts(motor, clutch, lever);
    }

    /** The layer the lever sits on: one above the deck, same layer as the content. */
    public static int LEVER_DY = RewardWagonLayout.CONTENT_DY - RewardWagonLayout.STRUCTURE_DY;

    private static final List<Cell> CELLS = build();

    private static List<Cell> build() {
        List<Cell> cells = new ArrayList<>(LENGTH * WIDTH);
        for (int row = 0; row < DRAWING.length; row++) {
            String line = DRAWING[row];
            if (line.length() != WIDTH) {
                throw new IllegalStateException("Row " + row + " is not " + WIDTH + " wide");
            }
            int dx = row - HALF_LENGTH;
            for (int column = 0; column < WIDTH; column++) {
                int dz = column - HALF_WIDTH;
                char symbol = line.charAt(column);
                cells.add(switch (symbol) {
                    case '.' -> new Cell(dx, dz, CellKind.AIR, 0);
                    case 'C' -> new Cell(dx, dz, CellKind.COUPLER, 0);
                    case 'b' -> new Cell(dx, dz, CellKind.BOGEY, 3);
                    case 'D' -> new Cell(dx, dz, CellKind.DOUBLE_SLAB, 1);
                    case '0' -> new Cell(dx, dz, CellKind.SLAB, 0);
                    case '1' -> new Cell(dx, dz, CellKind.SLAB, 1);
                    case '2' -> new Cell(dx, dz, CellKind.SLAB, 2);
                    default -> throw new IllegalStateException("Unknown symbol " + symbol + " in row " + row);
                });
            }
        }
        return List.copyOf(cells);
    }
}
