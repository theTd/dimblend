package dimblend.dragonchase.structure;

import java.util.ArrayList;
import java.util.List;

/**
 * The three car blueprints, as pure data: the drawings of {@code docs/structures.md} parsed into
 * cells with their offsets from the car's centre.
 *
 * <p><b>Coordinates.</b> Rows run along X (the direction of travel; one row = one block of X) and
 * columns along Z (7 wide, the middle column is {@code dz = 0}, the corridor line). Layer 0 is the
 * deck, i.e. the layer the physical bogey sits on, one above the rails. Every cell therefore
 * carries {@code (dx, dy, dz)} relative to the car's centre block, and the builder turns that into
 * a world position with {@code center.offset(dx, dy, dz)}.
 *
 * <p><b>Why no Minecraft types.</b> Which block stands on a cell is a fact about the requirement,
 * not about a level. Keeping the tables registry-free lets the unit tests read them with no game
 * running, and leaves {@link CarBlocks} as the only place that names blocks.
 *
 * <p><b>Ore and chest cells are holes, not blocks.</b> {@code 矿} and {@code 箱} are left empty
 * when the car is built and filled after the kill ({@link NestReward}); the drawing still names
 * them so the reward code can address them.
 *
 * <p><b>One correction to the transcription.</b> The raw prompt's crystal car drawing had two rows
 * with eight tokens ({@code 空 末 黑 末 黑 末 黑 空}) although the car is seven columns wide. Both
 * {@code docs/structures.md} and these tables normalize those rows to the seven-token checkerboard
 * {@code 空 末 黑 末 黑 末 空} — which gives the alternation every neighbouring row shows and keeps the
 * car 7 wide; the extra token is dropped. Counts are pinned by {@code CarPlansTest}.
 */
public final class CarPlans {

    /** What stands on one drawn cell. */
    public enum CellKind {
        /** Nothing: the corners of the deck rows, and the gaps between the coupler arms. */
        AIR,
        /** {@code simurail:automatic_coupler}, facing out of the car. */
        COUPLER,
        /** Creative copycat beam, axis X, dressed as purpur. */
        BEAM,
        /** Creative clean copycat double slab, dressed as purpur ({@code 黑} on the nest). */
        PURPUR_SLAB,
        /** Creative clean copycat double slab, dressed as obsidian ({@code 黑} on the cars). */
        OBSIDIAN_SLAB,
        /** Double slab: top half redstone ore, bottom half deepslate redstone ore ({@code 红}). */
        REDSTONE_ORE_SLAB,
        /** Creative clean copycat double slab, dressed as end stone bricks ({@code 末}). */
        END_STONE_SLAB,
        /** {@code simurail:physics_bogey}. */
        BOGEY,
        /** A reward hole ({@code 矿}): left empty at build time. */
        ORE,
        /** The reward hole that receives the shulker box ({@code 箱}): left empty at build time. */
        CHEST,
        /** {@code minecraft:redstone_block}: the brake car's bogey reads its signal from above. */
        REDSTONE_BLOCK,
        /** {@code minecraft:redstone_lamp}, one above the redstone block. */
        REDSTONE_LAMP
    }

    /** One cell: offset from the car's centre block, and what stands there. */
    public record Cell(int dx, int dy, int dz, CellKind kind) {
    }

    /**
     * Everything the builder and the runtime rules need about one car.
     *
     * @param parts     every cell that receives a block (never {@code AIR}, {@code ORE} or {@code CHEST})
     * @param oreCells  the {@code 矿} holes, in drawing order
     * @param chestCell the single {@code 箱} hole, or {@code null} for the two flat cars
     * @param bogey     the physical bogey cell
     * @param couplers  the two coupler cells, west first
     * @param endCells  the {@code 末} cells that may carry an end crystal (crystal car only)
     * @param redstone  the brake car's redstone block, or {@code null}
     * @param lamp      the brake car's redstone lamp, or {@code null}
     * @param halfLength blocks from the centre to the outer end cell
     * @param halfWidth  blocks from the centre column to the outer column (always 3)
     * @param topDy     the highest layer the plot must cover, so the reward layer is inside it
     */
    public record Plan(CarKind kind, int halfLength, int halfWidth, int topDy, List<Cell> parts,
            List<Cell> oreCells, Cell chestCell, Cell bogey, List<Cell> couplers, List<Cell> endCells,
            Cell redstone, Cell lamp) {

        /** True when this is the nest, i.e. the car whose holes receive the kill rewards. */
        public boolean isNest() {
            return kind == CarKind.NEST;
        }

        /** Every cell of {@code kindToFind}, in drawing order. */
        public List<Cell> all(CellKind kindToFind) {
            List<Cell> out = new ArrayList<>();
            for (Cell cell : parts) {
                if (cell.kind() == kindToFind) {
                    out.add(cell);
                }
            }
            return out;
        }
    }

    // ---------------------------------------------------------------- nest (11 x 7 x 5)

    /** Layer 1: deck. 20 purpur double slabs, four purpur beams, the bogey, a coupler at each end. */
    private static final String[] NEST_DECK = {
            "...C...",
            "...B...",
            "...B...",
            "..ppp..",
            ".ppppp.",
            ".ppbpp.",
            ".ppppp.",
            "..ppp..",
            "...B...",
            "...B...",
            "...C...",
    };

    /** Layer 2: the purpur ring around the 20 ore holes. */
    private static final String[] NEST_ORE = {
            ".......",
            ".......",
            "..ppp..",
            ".pMMMp.",
            "pMMMMMp",
            "pMMpMMp",
            "pMMMMMp",
            ".pMMMp.",
            "..ppp..",
            ".......",
            ".......",
    };

    /** Layers 3 and 4: the single centre pillar block. */
    private static final String[] NEST_PILLAR = {
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "...p...",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
    };

    /** Layer 5: the shulker box hole, on top of the pillar. */
    private static final String[] NEST_CHEST = {
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
            "...X...",
            ".......",
            ".......",
            ".......",
            ".......",
            ".......",
    };

    // ---------------------------------------------------------------- flat cars (13 x 7 x 1)

    /** The brake car's deck. {@code 红} is the redstone-ore double slab. */
    private static final String[] BRAKE_DECK = {
            "...C...",
            "...B...",
            "...B...",
            "...B...",
            "..rrr..",
            ".orrro.",
            ".orbro.",
            ".orrro.",
            "..rrr..",
            "...B...",
            "...B...",
            "...B...",
            "...C...",
    };

    /** The crystal car's deck. {@code 末} is the end-stone-brick double slab. */
    private static final String[] CRYSTAL_DECK = {
            "...C...",
            "...B...",
            "...B...",
            "...B...",
            ".oeoeo.",
            ".eoeoe.",
            ".oebeo.",
            ".eoeoe.",
            ".oeoeo.",
            "...B...",
            "...B...",
            "...B...",
            "...C...",
    };

    private static final int NEST_LENGTH = NEST_DECK.length;
    private static final int FLAT_LENGTH = BRAKE_DECK.length;
    private static final int WIDTH = 7;

    private static final Plan NEST = buildNest();
    private static final Plan BRAKE = buildFlat(CarKind.BRAKE);
    private static final Plan CRYSTAL = buildFlat(CarKind.CRYSTAL);

    private CarPlans() {
    }

    /** The blueprint of one car kind. */
    public static Plan of(CarKind kind) {
        return switch (kind) {
            case NEST -> NEST;
            case BRAKE -> BRAKE;
            case CRYSTAL -> CRYSTAL;
        };
    }

    private static Plan buildNest() {
        List<Cell> solid = new ArrayList<>();
        List<Cell> ore = new ArrayList<>();
        List<Cell> chest = new ArrayList<>();
        collect(solid, ore, chest, 0, NEST_LENGTH, NEST_DECK);
        collect(solid, ore, chest, 1, NEST_LENGTH, NEST_ORE);
        collect(solid, ore, chest, 2, NEST_LENGTH, NEST_PILLAR);
        collect(solid, ore, chest, 3, NEST_LENGTH, NEST_PILLAR);
        collect(solid, ore, chest, 4, NEST_LENGTH, NEST_CHEST);
        Cell bogey = only(solid, CellKind.BOGEY, "nest");
        if (chest.size() != 1) {
            throw new IllegalStateException("The nest drawing has " + chest.size() + " chest cells");
        }
        // Layer 5 (dy 4) is the highest layer: it must be inside the plot box, or the reward write
        // would grow the plot after the mass has been computed.
        return new Plan(CarKind.NEST, (NEST_LENGTH - 1) / 2, (WIDTH - 1) / 2, 4,
                List.copyOf(solid), List.copyOf(ore), chest.get(0), bogey,
                couplers(solid), List.of(), null, null);
    }

    private static Plan buildFlat(CarKind kind) {
        String[] drawing = kind == CarKind.BRAKE ? BRAKE_DECK : CRYSTAL_DECK;
        List<Cell> solid = new ArrayList<>();
        List<Cell> ore = new ArrayList<>();
        List<Cell> chest = new ArrayList<>();
        collect(solid, ore, chest, 0, FLAT_LENGTH, drawing);
        Cell bogey = only(solid, CellKind.BOGEY, kind.name());
        List<Cell> ends = new ArrayList<>();
        for (Cell cell : solid) {
            if (cell.kind() == CellKind.END_STONE_SLAB) {
                ends.add(cell);
            }
        }
        Cell redstone = null;
        Cell lamp = null;
        if (kind == CarKind.BRAKE) {
            // The bogey reads its brake signal from the block above it
            // (PhysicsBogeyBlockEntity#getControlStrength: getSignalSafe(getBlockPos().above())).
            redstone = new Cell(bogey.dx(), bogey.dy() + 1, bogey.dz(), CellKind.REDSTONE_BLOCK);
            lamp = new Cell(bogey.dx(), bogey.dy() + 2, bogey.dz(), CellKind.REDSTONE_LAMP);
            solid.add(redstone);
            solid.add(lamp);
        }
        int topDy = kind == CarKind.BRAKE ? 2 : 0;
        return new Plan(kind, (FLAT_LENGTH - 1) / 2, (WIDTH - 1) / 2, topDy,
                List.copyOf(solid), List.copyOf(ore), null, bogey, couplers(solid),
                List.copyOf(ends), redstone, lamp);
    }

    /**
     * Parses one layer into cells, sorting {@code ORE}/{@code CHEST} into their own lists.
     *
     * @param layer the layer index, i.e. {@code dy}
     */
    private static void collect(List<Cell> solid, List<Cell> ore, List<Cell> chest, int layer,
            int rows, String[] drawing) {
        if (drawing.length != rows) {
            throw new IllegalStateException("Layer " + layer + " has " + drawing.length + " rows, expected " + rows);
        }
        for (int row = 0; row < rows; row++) {
            String line = drawing[row];
            if (line.length() != WIDTH) {
                throw new IllegalStateException("Layer " + layer + " row " + row + " is "
                        + line.length() + " wide, expected " + WIDTH);
            }
            int dx = row - (rows - 1) / 2;
            for (int column = 0; column < WIDTH; column++) {
                CellKind kind = kindOf(line.charAt(column));
                if (kind == CellKind.AIR) {
                    continue;
                }
                int dz = column - (WIDTH - 1) / 2;
                Cell cell = new Cell(dx, layer, dz, kind);
                switch (kind) {
                    case ORE -> ore.add(cell);
                    case CHEST -> chest.add(cell);
                    default -> solid.add(cell);
                }
            }
        }
    }

    /** The single cell of {@code kind}; a drawing with none or several is a bug. */
    private static Cell only(List<Cell> cells, CellKind kind, String owner) {
        Cell found = null;
        for (Cell cell : cells) {
            if (cell.kind() != kind) {
                continue;
            }
            if (found != null) {
                throw new IllegalStateException("The " + owner + " drawing has more than one " + kind);
            }
            found = cell;
        }
        if (found == null) {
            throw new IllegalStateException("The " + owner + " drawing has no " + kind);
        }
        return found;
    }

    private static List<Cell> couplers(List<Cell> cells) {
        List<Cell> out = new ArrayList<>(2);
        for (Cell cell : cells) {
            if (cell.kind() == CellKind.COUPLER) {
                out.add(cell);
            }
        }
        out.sort((a, b) -> Integer.compare(a.dx(), b.dx()));
        return List.copyOf(out);
    }

    /** One drawing character to a cell kind. */
    private static CellKind kindOf(char symbol) {
        return switch (symbol) {
            case '.' -> CellKind.AIR;
            case 'C' -> CellKind.COUPLER;
            case 'B' -> CellKind.BEAM;
            case 'p' -> CellKind.PURPUR_SLAB;
            case 'o' -> CellKind.OBSIDIAN_SLAB;
            case 'r' -> CellKind.REDSTONE_ORE_SLAB;
            case 'e' -> CellKind.END_STONE_SLAB;
            case 'b' -> CellKind.BOGEY;
            case 'M' -> CellKind.ORE;
            case 'X' -> CellKind.CHEST;
            default -> throw new IllegalStateException("Unknown drawing symbol '" + symbol + "'");
        };
    }
}
