package dimblend.client.mirage;

/**
 * Per-section packed light cache for one mirage section: high nibble sky light, low nibble block
 * light, index {@code (y << 8) | (z << 4) | x}.
 *
 * <p><b>Why a BFS instead of the heightmap rule.</b> The old approximation answered every block
 * under the surface with {@code 15 - depth}, which is wrong the moment the surface is not a flat
 * column: a canyon wall two or three blocks under an overhanging step has no vertical path to the
 * sky, and vanilla gives it the light of the neighbouring open air (15 → 14 → 13 → 12), not
 * darkness. Vanilla's {@code ModelBlockRenderer} samples exactly those air cells when it shades a
 * solid face (ambient occlusion corners and the flat-face neighbour), so seeding the open cells and
 * flooding sideways reproduces the vanilla gradient without a {@code LevelLightEngine}.</p>
 *
 * <p><b>Cost.</b> One {@code 16×16×16} flood per section, only when that section is baked. The
 * flood is a level-ordered relaxation: a cell at level {@code L} can only be reached from a cell
 * above {@code L}, so processing 15 down to 1 relaxes every cell exactly once and never needs a
 * queue.</p>
 *
 * <p>The class is pure (no Minecraft types) so the gradient can be unit tested.</p>
 */
public final class MirageSectionLight {
    public static final int SECTION_BLOCKS = 16 * 16 * 16;
    public static final int SKY_OPEN = 15;

    private MirageSectionLight() {
    }

    /** World-space light inputs; all coordinates are absolute block coordinates. */
    public interface Sampler {
        /** Opaque cost of the cell in {@code 0..15}; {@code 15} blocks all propagation. */
        int lightBlock(int x, int y, int z);

        /** {@code 15} when the cell is open to the sky, else {@code 0}. */
        int skySeed(int x, int y, int z);

        /** Block light emitted by the cell itself, {@code 0..15}. */
        int emission(int x, int y, int z);
    }

    /** Packs one section. {@code originY} is the section's lowest block Y. */
    public static byte[] compute(int originX, int originY, int originZ, Sampler sampler) {
        int[] sky = new int[SECTION_BLOCKS];
        int[] block = new int[SECTION_BLOCKS];
        byte[] cost = new byte[SECTION_BLOCKS];

        for (int y = 0; y < 16; y++) {
            int worldY = originY + y;
            for (int z = 0; z < 16; z++) {
                int worldZ = originZ + z;
                for (int x = 0; x < 16; x++) {
                    int worldX = originX + x;
                    int index = index(x, y, z);
                    int opaque = clamp(sampler.lightBlock(worldX, worldY, worldZ));
                    cost[index] = (byte) opaque;
                    // Block light is seeded even inside an opaque emitter (glowstone, lava): the
                    // flood refuses to enter solids but must start from them.
                    block[index] = clamp(sampler.emission(worldX, worldY, worldZ));
                    if (opaque >= 15) {
                        continue;
                    }
                    sky[index] = clamp(sampler.skySeed(worldX, worldY, worldZ));
                }
            }
        }

        // One-block relay across the six section faces: an open cell just outside the section keeps
        // feeding its inside neighbour, which is what makes a wall on the section border as smooth
        // as one in the middle.
        relayFace(sky, block, cost, sampler, originX, originY, originZ, 0, -1);
        relayFace(sky, block, cost, sampler, originX, originY, originZ, 0, 1);
        relayFace(sky, block, cost, sampler, originX, originY, originZ, 1, -1);
        relayFace(sky, block, cost, sampler, originX, originY, originZ, 1, 1);
        relayFace(sky, block, cost, sampler, originX, originY, originZ, 2, -1);
        relayFace(sky, block, cost, sampler, originX, originY, originZ, 2, 1);

        flood(sky, cost);
        flood(block, cost);

        byte[] packed = new byte[SECTION_BLOCKS];
        for (int i = 0; i < SECTION_BLOCKS; i++) {
            packed[i] = (byte) ((sky[i] << 4) | block[i]);
        }
        return packed;
    }

    /**
     * Feeds the inside cells of one section face from the cells one block outside it.
     *
     * @param axis {@code 0} = X face, {@code 1} = Y face, {@code 2} = Z face
     * @param side {@code -1} = the low face, {@code +1} = the high face
     */
    private static void relayFace(
            int[] sky, int[] block, byte[] cost, Sampler sampler,
            int originX, int originY, int originZ, int axis, int side) {
        int face = side < 0 ? 0 : 15;
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                int x = axis == 0 ? face : i;
                int y = axis == 1 ? face : (axis == 0 ? i : j);
                int z = axis == 2 ? face : j;
                int index = index(x, y, z);
                int insideCost = cost[index] & 0xFF;
                if (insideCost >= 15) {
                    continue;
                }
                int outsideX = originX + x + (axis == 0 ? side : 0);
                int outsideY = originY + y + (axis == 1 ? side : 0);
                int outsideZ = originZ + z + (axis == 2 ? side : 0);
                int drop = Math.max(1, insideCost);
                sky[index] = Math.max(sky[index], clamp(sampler.skySeed(outsideX, outsideY, outsideZ)) - drop);
                block[index] = Math.max(block[index], clamp(sampler.emission(outsideX, outsideY, outsideZ)) - drop);
            }
        }
    }

    /** Level-ordered relaxation; see the class note for why one pass per level is exact. */
    static void flood(int[] light, byte[] cost) {
        for (int level = SKY_OPEN; level >= 2; level--) {
            for (int index = 0; index < SECTION_BLOCKS; index++) {
                if (light[index] != level) {
                    continue;
                }
                int x = index & 15;
                int z = (index >> 4) & 15;
                int y = index >> 8;
                if (x < 15) {
                    relax(light, cost, index + 1, level);
                }
                if (x > 0) {
                    relax(light, cost, index - 1, level);
                }
                if (z < 15) {
                    relax(light, cost, index + 16, level);
                }
                if (z > 0) {
                    relax(light, cost, index - 16, level);
                }
                if (y < 15) {
                    relax(light, cost, index + 256, level);
                }
                if (y > 0) {
                    relax(light, cost, index - 256, level);
                }
            }
        }
    }

    private static void relax(int[] light, byte[] cost, int neighbour, int level) {
        int next = level - Math.max(1, cost[neighbour] & 0xFF);
        if (next > light[neighbour]) {
            light[neighbour] = next;
        }
    }

    public static int index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    public static int sky(byte packed) {
        return (packed >> 4) & 15;
    }

    public static int block(byte packed) {
        return packed & 15;
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 15);
    }
}
