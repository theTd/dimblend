package dimblend.experience.reward;

import java.util.List;

/**
 * When a reward wagon has been emptied and must disappear.
 *
 * <p><b>The rule.</b> The requirement dissolves the wagon when "no other block remains and
 * only the structure above is left". For the twilight wagon the only content is the chest,
 * and the player chose that an <i>empty</i> chest counts as taken — otherwise a chest that
 * cannot be broken would stand there forever and the wagon would never qualify.
 *
 * <p><b>What counts as structure.</b> The three brick kinds, the two couplers and the
 * bogey. Everything else is content: ore, raw ore, the ore stacked on top of a raw block,
 * the chest, the sensor, the TNT, the creative motor.
 *
 * <p><b>The two triggers.</b> Either the wagon becomes empty ({@link #isEmpty}) or it
 * touches a warp gate ({@link #touchesGate}). Both lead to the same dissolve.
 *
 * <p>This class is pure. Reading the wagon is the caller's job; deciding is this one's.
 */
public final class RewardWagonDissolve {
    /** Delay between the decision to dissolve and the sub-level actually going away. */
    public static final int DISSOLVE_DELAY_TICKS = 10;

    /** What one block inside the wagon turned out to be. */
    public enum Occupant {
        /** Structure, including the couplers. */
        STRUCTURE,
        /** A container with something still in it. */
        FILLED_CONTAINER,
        /** A container that has been emptied. */
        EMPTY_CONTAINER,
        /** Anything else: ore, chest contents, sensor, TNT, motor, foreign blocks. */
        CONTENT
    }

    private RewardWagonDissolve() {
    }

    /**
     * Decides whether the wagon is empty.
     *
     * <p>An empty container does not keep the wagon alive; a filled one does.
     */
    public static boolean isEmpty(List<Occupant> occupants) {
        for (Occupant occupant : occupants) {
            if (occupant == Occupant.STRUCTURE || occupant == Occupant.EMPTY_CONTAINER) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Decides whether the wagon's world box meets a warp gate.
     *
     * <p>The player chose the bounding-box test over "one block away": the wagon has to
     * actually reach the gate before it vanishes. The caller supplies the box's block
     * extents, already ordered.
     */
    public static boolean touchesGate(Box box, List<BlockPos> gatePositions) {
        for (BlockPos gate : gatePositions) {
            if (box.contains(gate.getX(), gate.getY(), gate.getZ())) {
                return true;
            }
        }
        return false;
    }

    /** An inclusive block box, ordered the way Sable reports one. */
    public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX
                    && y >= minY && y <= maxY
                    && z >= minZ && z <= maxZ;
        }
    }

    /** A mutable block position, so this file needs no Minecraft import in its tests. */
    public record BlockPos(int getX, int getY, int getZ) {
    }
}