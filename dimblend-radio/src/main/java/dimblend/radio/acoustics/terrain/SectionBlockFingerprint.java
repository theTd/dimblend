package dimblend.radio.acoustics.terrain;

/** Compare palette and Sodium-slice contents without relying on palette-specific ID ordering. */
public final class SectionBlockFingerprint {
    @FunctionalInterface public interface Blocks { Object get(int x, int y, int z); }

    public static long of(Blocks blocks) {
        long hash = 0xcbf29ce484222325L;
        for (int y=0; y<16; y++) for (int z=0; z<16; z++) for (int x=0; x<16; x++) {
            hash ^= java.util.Objects.hashCode(blocks.get(x,y,z));
            hash *= 0x100000001b3L;
        }
        return hash;
    }
    private SectionBlockFingerprint() { }
}
