package dimblend.api;

/**
 * How many purple portal particles the warp gate emits when a Sable sub-level passes through.
 *
 * <p>User requirement, 2026-11-03: "折跃门方块在有 sable 穿过时 会播放「下界传送门方块音效」
 * 并发出「紫色传送门粒子」<更改：粒子量 x2>".
 *
 * <p><b>Why the count is not read from the block entity.</b> The gate extends vanilla's end
 * gateway, whose {@code animateTick} emits one particle per <i>visible</i> face and derives the
 * count from {@code TheEndGatewayBlockEntity#getParticleAmount()}. That works for a lone gateway
 * floating in the End. Our gate is a slab of cells tiling the vault cross-section, so every
 * interior cell has all six faces hidden and that formula returns <b>zero</b> — which is exactly
 * the "no particles" the user reported. Doubling zero is still zero.
 *
 * <p>So the count here is per <b>cell the sub-level overlaps</b>: one particle is what a visible
 * face contributes in vanilla, and the requirement's "×2" multiplies that. The slab's own ambient
 * play stays vanilla's business; this class only covers the extra burst a crossing produces.
 *
 * <p><b>Bounded.</b> A large sub-level can overlap hundreds of gate cells. Emitting two particles
 * per cell would be thousands per tick for one structure, so the total is capped and the cells are
 * sampled evenly across the intersection instead of being emitted in block order — the same
 * particles, spread over the whole slab rather than piled at its west edge.
 */
public final class WarpGateParticles {

    /** Vanilla's end gateway emits one particle per visible face; the requirement doubles it. */
    public static final int PARTICLES_PER_CELL = 2;

    /** Hard cap per sub-level per tick, so a huge structure cannot flood the client. */
    public static final int MAX_PARTICLES = 128;

    /** How many cells to actually sample this tick. */
    public static int sampleCount(int overlappingCells) {
        if (overlappingCells <= 0) {
            return 0;
        }
        return Math.min(overlappingCells, MAX_PARTICLES / PARTICLES_PER_CELL);
    }

    /** The total particles for one tick: two per sampled cell, never above the cap. */
    public static int totalParticles(int overlappingCells) {
        return sampleCount(overlappingCells) * PARTICLES_PER_CELL;
    }

    /**
     * Picks which of the overlapping cells to emit at, spread evenly.
     *
     * <p>Even spreading matters because the intersection is walked in block order: taking the
     * first {@code sampleCount} cells would put every particle at the slab's low corner, and a
     * player on the far side of a wide sub-level would see nothing.
     *
     * @param overlappingCells how many cells the sub-level overlaps
     * @return indices in {@code 0..overlappingCells-1} to emit at, evenly spaced
     */
    public static int[] sampledIndices(int overlappingCells) {
        int count = sampleCount(overlappingCells);
        if (count <= 0) {
            return new int[0];
        }
        if (count >= overlappingCells) {
            int[] all = new int[overlappingCells];
            for (int i = 0; i < overlappingCells; i++) {
                all[i] = i;
            }
            return all;
        }
        int[] out = new int[count];
        // Spread over the half-open span so the last sample lands near, but not on, the far edge.
        for (int i = 0; i < count; i++) {
            out[i] = (int) ((long) i * overlappingCells / count);
        }
        return out;
    }

    private WarpGateParticles() {
    }
}