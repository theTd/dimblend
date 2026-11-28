package dimblend.mirage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Compact wire format for one in-memory {@link ProtoChunk} mirage chunk.
 *
 * <p>Only non-air sections are sent. Each section is a palette of global
 * {@link Block#BLOCK_STATE_REGISTRY} ids plus 4096 16-bit indices encoded as palette-index runs
 * (varint run length + varint palette index), and the whole section blob is deflated. A typical
 * terrain section has a handful of palette entries with long runs, so this is far smaller than
 * shipping a real chunk packet.</p>
 *
 * <p><b>Biomes travel with the chunk (v2).</b> The mirage is band A's terrain generated inside
 * band B's coordinates, so its biomes are <em>not</em> the ones the client level has at
 * {@code X >= 4096} (those are band B's real biomes: caves, plains, whatever). Without the
 * tableau's own quart biomes the client resolves grass/foliage/water tint against band B and the
 * mirage picks up a visible colour cast. v2 therefore ships a global biome-id palette plus
 * RLE-encoded quart indices, {@link #BIOME_CELLS_PER_SECTION} cells per section, in the same
 * deflated blob. v1 blobs (no biome block at all) still decode: their biome arrays are empty and
 * the client falls back to the level tint.</p>
 *
 * <p>Machine-independent contract, matched by the client decoder:</p>
 * <ul>
 *   <li>cell index inside a section is {@code (y << 8) | (z << 4) | x};</li>
 *   <li>biome cell index is {@code (sectionIndex << 6) | (qy << 4) | (qz << 2) | qx}, each of
 *       {@code qx/qy/qz} in {@code [0,3]} quart cells; world quart is
 *       {@code ((minSectionY + sectionIndex) << 2) + qy};</li>
 *   <li>world Y of a section cell is {@code (minSectionY + sectionIndex) * 16 + y};</li>
 *   <li>heightmap has 256 entries, index {@code (lz << 4) | lx}, value = absolute WORLD_SURFACE Y;</li>
 *   <li>emissive entries pack {@code ((worldY - minSectionY * 16) << 12)
 *       | (lx << 8) | (lz << 4) | (light & 15)}.</li>
 * </ul>
 *
 * <p>{@link #encodeRaw(int, int, List)} / {@link #decodeRaw(byte[])} are pure Java (no registry
 * bootstrap, no level), which is what the JUnit round-trip tests use. {@link #encodeChunk}
 * additionally reads block states, quart biomes and the WORLD_SURFACE heightmap from a real
 * {@code ProtoChunk}.
 */
public final class MirageSectionCodec {
    public static final int SECTION_BLOCKS = 4096;
    public static final int HEIGHTMAP_ENTRIES = 256;
    /** {@code 4 × 4 × 4} quart biomes per 16³ section. */
    public static final int BIOME_CELLS_PER_SECTION = 64;
    private static final int MAGIC = 0x4D495253; // "MIRS"
    /** v1 = block sections only, v2 = plus the quart-biome palette (still accepted on read). */
    private static final int VERSION = 2;
    private static final int VERSION_NO_BIOMES = 1;
    private static final int INFLATE_CHUNK = 8192;

    private MirageSectionCodec() {
    }

    /**
     * One non-air section.
     *
     * @param sectionIndex index into {@code chunk.getSections()} (0 = bottom section)
     * @param palette      global block-state ids, non-empty
     * @param indices      exactly {@link #SECTION_BLOCKS} entries, each {@code < palette.length}
     */
    public record Section(int sectionIndex, int[] palette, short[] indices) {
        public Section {
            if (sectionIndex < 0) {
                throw new IllegalArgumentException("negative section index " + sectionIndex);
            }
            if (palette.length == 0) {
                throw new IllegalArgumentException("empty palette");
            }
            if (indices.length != SECTION_BLOCKS) {
                throw new IllegalArgumentException("section must have " + SECTION_BLOCKS
                        + " indices, got " + indices.length);
            }
            for (short index : indices) {
                if (index < 0 || index >= palette.length) {
                    throw new IllegalArgumentException("palette index " + index
                            + " out of range " + palette.length);
                }
            }
        }
    }

    /** Decoded section blob: block sections plus the quart-biome palette (empty on v1 blobs). */
    public record ChunkData(
            int minSectionY, int sectionCount, List<Section> sections,
            int[] biomePalette, short[] biomeIndices) {

        public ChunkData {
            if (biomePalette == null) {
                biomePalette = new int[0];
            }
            if (biomeIndices == null) {
                biomeIndices = new short[0];
            }
        }

        /** True when this blob carried quart biomes the client may tint against. */
        public boolean hasBiomes() {
            return this.biomePalette.length > 0
                    && this.biomeIndices.length == this.sectionCount * BIOME_CELLS_PER_SECTION;
        }
    }

    /** Fully encoded chunk: sections blob plus the two lookup tables. */
    public record Encoded(byte[] sections, short[] heightmap, int[] emissive) {
    }

    /** Reads a chunk's non-air sections, quart biomes, WORLD_SURFACE heightmap and emissive list. */
    public static Encoded encodeChunk(ProtoChunk chunk, Registry<Biome> biomes) {
        int minSectionY = chunk.getMinSection();
        int sectionCount = chunk.getSectionsCount();
        // Decoration rewrote the surface after the FEATURES prime; re-prime from the final
        // blocks so the client's cheap sky term matches what it renders.
        Heightmap.primeHeightmaps(chunk, java.util.EnumSet.of(Heightmap.Types.WORLD_SURFACE));
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE);
        short[] heightmap = new short[HEIGHTMAP_ENTRIES];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                heightmap[(lz << 4) | lx] = (short) surface.getFirstAvailable(lx, lz);
            }
        }

        int minBuildY = minSectionY << 4;
        List<Section> sections = new ArrayList<>();
        List<Integer> emissive = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        List<Integer> biomePaletteList = new ArrayList<>();
        Map<Integer, Integer> biomeIndexById = new HashMap<>();
        short[] biomeIndices = new short[sectionCount * BIOME_CELLS_PER_SECTION];
        for (int sectionIndex = 0; sectionIndex < sectionCount; sectionIndex++) {
            LevelChunkSection section = chunk.getSection(sectionIndex);
            // Quart biomes are sent for every section, air or not: the client resolves a block's
            // biome from the quart grid, and an air section still holds the biomes its neighbours
            // are interpolated from.
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> holder = section.getBiomes().get(qx, qy, qz);
                        int id = biomes.getId(holder.value());
                        if (id < 0) {
                            throw new IllegalStateException("unregistered biome " + holder.value());
                        }
                        Integer known = biomeIndexById.get(id);
                        int index;
                        if (known == null) {
                            index = biomePaletteList.size();
                            biomePaletteList.add(id);
                            biomeIndexById.put(id, index);
                        } else {
                            index = known;
                        }
                        biomeIndices[(sectionIndex << 6) | (qy << 4) | (qz << 2) | qx] = (short) index;
                    }
                }
            }
            if (section.hasOnlyAir()) {
                continue;
            }
            paletteIndex.clear();
            List<Integer> palette = new ArrayList<>();
            short[] indices = new short[SECTION_BLOCKS];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Integer known = paletteIndex.get(state);
                        int index;
                        if (known == null) {
                            int id = Block.BLOCK_STATE_REGISTRY.getId(state);
                            if (id < 0) {
                                throw new IllegalStateException("unregistered block state " + state);
                            }
                            index = palette.size();
                            palette.add(id);
                            paletteIndex.put(state, index);
                        } else {
                            index = known;
                        }
                        indices[(y << 8) | (z << 4) | x] = (short) index;
                        int light = state.getLightEmission();
                        if (light > 0) {
                            int worldY = minBuildY + (sectionIndex << 4) + y;
                            emissive.add((((worldY - minBuildY) & 0xFFFF) << 12)
                                    | ((x & 15) << 8) | ((z & 15) << 4) | (light & 15));
                        }
                    }
                }
            }
            int[] paletteArray = new int[palette.size()];
            for (int i = 0; i < paletteArray.length; i++) {
                paletteArray[i] = palette.get(i);
            }
            sections.add(new Section(sectionIndex, paletteArray, indices));
        }
        int[] emissiveArray = new int[emissive.size()];
        for (int i = 0; i < emissiveArray.length; i++) {
            emissiveArray[i] = emissive.get(i);
        }
        int[] biomePalette = new int[biomePaletteList.size()];
        for (int i = 0; i < biomePalette.length; i++) {
            biomePalette[i] = biomePaletteList.get(i);
        }
        return new Encoded(
                encodeRaw(minSectionY, sectionCount, sections, biomePalette, biomeIndices),
                heightmap, emissiveArray);
    }

    /** Deflated block-section blob with no quart biomes (v1 semantics, still v2 on the wire). */
    public static byte[] encodeRaw(int minSectionY, int sectionCount, List<Section> sections) {
        return encodeRaw(minSectionY, sectionCount, sections, new int[0], new short[0]);
    }

    /**
     * Deflated section blob: magic, version, minSectionY (zigzag), sectionCount, sections, then
     * the quart-biome palette and its run-encoded indices ({@code sectionCount * 64} cells).
     */
    public static byte[] encodeRaw(int minSectionY, int sectionCount, List<Section> sections,
                                   int[] biomePalette, short[] biomeIndices) {
        if (sectionCount < 0) {
            throw new IllegalArgumentException("negative section count " + sectionCount);
        }
        if (biomeIndices.length != 0
                && biomeIndices.length != sectionCount * BIOME_CELLS_PER_SECTION) {
            throw new IllegalArgumentException("biome indices must be " + (sectionCount * BIOME_CELLS_PER_SECTION)
                    + " cells or empty, got " + biomeIndices.length);
        }
        for (short index : biomeIndices) {
            if (index < 0 || index >= biomePalette.length) {
                throw new IllegalArgumentException("biome palette index " + index
                        + " out of range " + biomePalette.length);
            }
        }
        Writer raw = new Writer(64 + sections.size() * 256);
        raw.writeInt(MAGIC);
        raw.writeByte(VERSION);
        raw.writeZigZagVarInt(minSectionY);
        raw.writeVarInt(sectionCount);
        raw.writeVarInt(sections.size());
        for (Section section : sections) {
            raw.writeVarInt(section.sectionIndex());
            raw.writeVarInt(section.palette().length);
            for (int id : section.palette()) {
                raw.writeVarInt(id);
            }
            raw.writeRuns(section.indices());
        }
        // An empty palette means "no biomes": nothing follows, and the client falls back to the
        // level tint (exactly what a v1 blob does).
        raw.writeVarInt(biomePalette.length);
        if (biomePalette.length > 0) {
            for (int id : biomePalette) {
                raw.writeVarInt(id);
            }
            raw.writeRuns(biomeIndices);
        }
        return deflate(raw.toByteArray());
    }

    /** Inverse of {@link #encodeRaw(int, int, List, int[], short[])}; accepts v1 and v2 blobs. */
    public static ChunkData decodeRaw(byte[] bytes) {
        byte[] raw = inflate(bytes);
        Reader reader = new Reader(raw);
        if (reader.readInt() != MAGIC) {
            throw new IllegalArgumentException("not a mirage section blob");
        }
        int version = reader.readByte();
        if (version != VERSION && version != VERSION_NO_BIOMES) {
            throw new IllegalArgumentException("unsupported mirage section version " + version);
        }
        int minSectionY = reader.readZigZagVarInt();
        int sectionCount = reader.readVarInt();
        if (sectionCount < 0) {
            throw new IllegalArgumentException("negative section count " + sectionCount);
        }
        int sectionEntries = reader.readVarInt();
        if (sectionEntries < 0 || sectionEntries > sectionCount) {
            throw new IllegalArgumentException("section entries out of range: " + sectionEntries);
        }
        List<Section> sections = new ArrayList<>(sectionEntries);
        for (int i = 0; i < sectionEntries; i++) {
            int sectionIndex = reader.readVarInt();
            if (sectionIndex < 0 || sectionIndex >= sectionCount) {
                throw new IllegalArgumentException("section index out of range: " + sectionIndex);
            }
            int paletteSize = reader.readVarInt();
            if (paletteSize <= 0 || paletteSize > SECTION_BLOCKS) {
                throw new IllegalArgumentException("palette size out of range: " + paletteSize);
            }
            int[] palette = new int[paletteSize];
            for (int p = 0; p < paletteSize; p++) {
                palette[p] = reader.readVarInt();
            }
            short[] indices = readRuns(reader, SECTION_BLOCKS, paletteSize);
            sections.add(new Section(sectionIndex, palette, indices));
        }
        int[] biomePalette = new int[0];
        short[] biomeIndices = new short[0];
        if (version >= VERSION) {
            int biomePaletteSize = reader.readVarInt();
            if (biomePaletteSize < 0 || biomePaletteSize > 1 << 16) {
                throw new IllegalArgumentException("biome palette size out of range: " + biomePaletteSize);
            }
            if (biomePaletteSize > 0) {
                biomePalette = new int[biomePaletteSize];
                for (int p = 0; p < biomePaletteSize; p++) {
                    biomePalette[p] = reader.readVarInt();
                }
                biomeIndices = readRuns(
                        reader, sectionCount * BIOME_CELLS_PER_SECTION, biomePaletteSize);
            }
        }
        if (reader.remaining() != 0) {
            throw new IllegalArgumentException("trailing bytes: " + reader.remaining());
        }
        return new ChunkData(minSectionY, sectionCount, sections, biomePalette, biomeIndices);
    }

    /** Decodes one run-length block of {@code expectedLength} palette indices. */
    private static short[] readRuns(Reader reader, int expectedLength, int paletteSize) {
        short[] indices = new short[expectedLength];
        int runCount = reader.readVarInt();
        if (runCount < 0) {
            throw new IllegalArgumentException("negative run count " + runCount);
        }
        int filled = 0;
        for (int run = 0; run < runCount; run++) {
            int runLength = reader.readVarInt();
            int paletteIndex = reader.readVarInt();
            if (runLength <= 0 || paletteIndex < 0 || paletteIndex >= paletteSize
                    || filled + runLength > expectedLength) {
                throw new IllegalArgumentException("bad run " + runLength + "x" + paletteIndex
                        + " at " + filled);
            }
            java.util.Arrays.fill(indices, filled, filled + runLength, (short) paletteIndex);
            filled += runLength;
        }
        if (filled != expectedLength) {
            throw new IllegalArgumentException("runs filled " + filled + "/" + expectedLength);
        }
        return indices;
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(32, raw.length / 3));
            byte[] chunk = new byte[INFLATE_CHUNK];
            while (!deflater.finished()) {
                int count = deflater.deflate(chunk);
                out.write(chunk, 0, count);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] bytes) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, bytes.length * 4));
            byte[] chunk = new byte[INFLATE_CHUNK];
            while (!inflater.finished()) {
                int count;
                try {
                    count = inflater.inflate(chunk);
                } catch (DataFormatException failure) {
                    throw new IllegalArgumentException("corrupt mirage section blob", failure);
                }
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IllegalArgumentException("truncated mirage section blob");
                    }
                } else {
                    out.write(chunk, 0, count);
                }
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static final class Writer {
        private byte[] buffer;
        private int size;

        Writer(int initialCapacity) {
            this.buffer = new byte[Math.max(16, initialCapacity)];
        }

        void writeByte(int value) {
            this.ensure(1);
            this.buffer[this.size++] = (byte) value;
        }

        void writeInt(int value) {
            this.ensure(4);
            this.buffer[this.size++] = (byte) (value >>> 24);
            this.buffer[this.size++] = (byte) (value >>> 16);
            this.buffer[this.size++] = (byte) (value >>> 8);
            this.buffer[this.size++] = (byte) value;
        }

        void writeVarInt(int value) {
            if (value < 0) {
                throw new IllegalArgumentException("negative varint " + value);
            }
            while ((value & 0xFFFFFF80) != 0) {
                this.writeByte((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            this.writeByte(value);
        }

        void writeZigZagVarInt(int value) {
            this.writeVarInt((value << 1) ^ (value >> 31));
        }

        /** Run-length encodes palette indices: count, then (length, index) pairs. */
        void writeRuns(short[] indices) {
            int runs = 1;
            for (int i = 1; i < indices.length; i++) {
                if (indices[i] != indices[i - 1]) {
                    runs++;
                }
            }
            this.writeVarInt(runs);
            int start = 0;
            for (int i = 1; i <= indices.length; i++) {
                if (i == indices.length || indices[i] != indices[start]) {
                    this.writeVarInt(i - start);
                    this.writeVarInt(indices[start]);
                    start = i;
                }
            }
        }

        byte[] toByteArray() {
            byte[] out = new byte[this.size];
            System.arraycopy(this.buffer, 0, out, 0, this.size);
            return out;
        }

        private void ensure(int extra) {
            if (this.size + extra <= this.buffer.length) {
                return;
            }
            int grown = Math.max(this.buffer.length * 2, this.size + extra);
            byte[] next = new byte[grown];
            System.arraycopy(this.buffer, 0, next, 0, this.size);
            this.buffer = next;
        }
    }

    private static final class Reader {
        private final byte[] buffer;
        private int index;

        Reader(byte[] buffer) {
            this.buffer = buffer;
        }

        int readByte() {
            if (this.index >= this.buffer.length) {
                throw new IllegalArgumentException("truncated mirage section blob");
            }
            return this.buffer[this.index++] & 0xFF;
        }

        int readInt() {
            return (this.readByte() << 24) | (this.readByte() << 16) | (this.readByte() << 8) | this.readByte();
        }

        int readVarInt() {
            int value = 0;
            int shift = 0;
            while (true) {
                int b = this.readByte();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
                if (shift > 35) {
                    throw new IllegalArgumentException("varint too long");
                }
            }
        }

        int readZigZagVarInt() {
            int value = this.readVarInt();
            return (value >>> 1) ^ -(value & 1);
        }

        int remaining() {
            return this.buffer.length - this.index;
        }
    }
}
