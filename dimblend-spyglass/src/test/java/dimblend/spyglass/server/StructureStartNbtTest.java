package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureOutline;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class StructureStartNbtTest {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("test", "house");
    /**
     * Target data version used by these tests. Passing it in keeps the read path free of
     * {@code SharedConstants}, which is not initialized in plain unit tests.
     */
    private static final int DATA_VERSION = 4000;

    @Test
    void parsesSavedStartLayout() throws IOException {
        CompoundTag tag = chunk(DATA_VERSION, 0, 0);
        tag.getCompound("structures").getCompound("starts").put(
                ID.toString(), start(ID, new int[][] {
                        {-20, -10, -30, -1, 10, -1},
                        {5, 0, 7, 15, 20, 27},
                        {1, 2, 3, 4, 5}
                }));

        StructureStartNbt.StartsRead read = read(tag, 0, 0);

        assertEquals(StructureStartNbt.SkipReason.NONE, read.skipReason());
        assertEquals(1, read.outlines().size());
        StructureOutline outline = read.outlines().get(0);
        assertEquals(ID, outline.id());
        assertEquals(2, outline.pieces().size());
        assertEquals(new OutlineBox(-20, -10, -30, -1, 10, -1), outline.pieces().get(0));
        assertEquals(new OutlineBox(5, 0, 7, 15, 20, 27), outline.pieces().get(1));
        assertEquals(new OutlineBox(-20, -10, -30, 15, 20, 27), outline.bounds());
    }

    @Test
    void skipsOlderDataVersion() throws IOException {
        assertUnsupported(DATA_VERSION - 1);
    }

    @Test
    void skipsNewerDataVersion() throws IOException {
        assertUnsupported(DATA_VERSION + 1);
    }

    @Test
    void rejectsChunkThatReportsDifferentCoordinates() throws IOException {
        CompoundTag tag = chunk(DATA_VERSION, 4, -9);
        tag.getCompound("structures").getCompound("starts").put(
                ID.toString(), start(ID, new int[][] {{0, 0, 0, 1, 1, 1}}));

        StructureStartNbt.StartsRead mismatch = read(tag, 0, 0);
        assertEquals(StructureStartNbt.SkipReason.IDENTITY_MISMATCH, mismatch.skipReason());
        assertTrue(mismatch.outlines().isEmpty());

        StructureStartNbt.StartsRead match = read(tag, 4, -9);
        assertEquals(StructureStartNbt.SkipReason.NONE, match.skipReason());
        assertEquals(1, match.outlines().size());
    }

    @Test
    void rejectsChunkWithoutCoordinates() throws IOException {
        CompoundTag tag = chunk(DATA_VERSION, 0, 0);
        tag.remove("xPos");
        tag.getCompound("structures").getCompound("starts").put(
                ID.toString(), start(ID, new int[][] {{0, 0, 0, 1, 1, 1}}));

        assertEquals(StructureStartNbt.SkipReason.IDENTITY_MISMATCH,
                read(tag, 0, 0).skipReason());
    }

    @Test
    void skipsInvalidEmptyAndPieceLessStarts() throws IOException {
        CompoundTag tag = chunk(DATA_VERSION, 0, 0);
        CompoundTag starts = tag.getCompound("structures").getCompound("starts");
        CompoundTag invalid = new CompoundTag();
        invalid.putString("id", "INVALID");
        starts.put(ID.toString(), invalid);
        CompoundTag empty = new CompoundTag();
        empty.putString("id", ID.toString());
        empty.put("Children", new ListTag());
        starts.put("test:empty", empty);
        CompoundTag noChildren = new CompoundTag();
        noChildren.putString("id", ID.toString());
        starts.put("test:no_children", noChildren);
        CompoundTag inverted = start(ResourceLocation.fromNamespaceAndPath("test", "inverted"),
                new int[][] {{5, 0, 0, 1, 1, 1}});
        starts.put("test:inverted", inverted);
        CompoundTag shortBounds = start(ResourceLocation.fromNamespaceAndPath("test", "short"),
                new int[][] {{1, 2, 3, 4, 5}});
        starts.put("test:short", shortBounds);

        assertTrue(read(tag, 0, 0).outlines().isEmpty());
    }

    @Test
    void keepsValidPiecesAndDropsOutOfWorldOnes() throws IOException {
        CompoundTag tag = chunk(DATA_VERSION, 0, 0);
        tag.getCompound("structures").getCompound("starts").put(
                ID.toString(), start(ID, new int[][] {
                        {0, 0, 0, 3, 3, 3},
                        {OutlineBox.MAX_COORDINATE + 1, 0, 0, OutlineBox.MAX_COORDINATE + 2, 1, 1}
                }));

        StructureStartNbt.StartsRead result = read(tag, 0, 0);

        assertEquals(1, result.outlines().size());
        assertEquals(1, result.outlines().get(0).pieces().size());
    }

    @Test
    void rejectsNonCompoundRootAsParseError() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.write(new byte[] {'n', 'o', 't', '-', 'n', 'b', 't', '!'});
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertThrows(IOException.class,
                    () -> StructureStartNbt.readOutlines(input, DATA_VERSION, 0, 0));
        }
    }

    @Test
    void streamsOnlySelectedFields() throws IOException {
        CompoundTag tag = chunk(DATA_VERSION, 2, -3);
        tag.putString("ignored_payload", "not collected");
        tag.put("big", new ListTag());

        CompoundTag partial = readPartial(tag);

        assertNotNull(partial);
        assertFalse(partial.contains("ignored_payload"));
        assertFalse(partial.contains("big"));
        assertEquals(DATA_VERSION, partial.getInt("DataVersion"));
        assertEquals(2, partial.getInt("xPos"));
        assertEquals(-3, partial.getInt("zPos"));
    }

    private static void assertUnsupported(int dataVersion) throws IOException {
        CompoundTag tag = chunk(dataVersion, 0, 0);
        tag.getCompound("structures").getCompound("starts").put(
                ID.toString(), start(ID, new int[][] {{0, 0, 0, 1, 1, 1}}));

        StructureStartNbt.StartsRead read = read(tag, 0, 0);

        assertEquals(StructureStartNbt.SkipReason.UNSUPPORTED_DATA_VERSION, read.skipReason());
        assertTrue(read.outlines().isEmpty());
    }

    private static StructureStartNbt.StartsRead read(
            CompoundTag tag, int chunkX, int chunkZ) throws IOException {
        byte[] bytes = bytes(tag);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return StructureStartNbt.readOutlines(input, DATA_VERSION, chunkX, chunkZ);
        }
    }

    private static CompoundTag readPartial(CompoundTag tag) throws IOException {
        byte[] bytes = bytes(tag);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return StructureStartNbt.readPartial(input);
        }
    }

    private static byte[] bytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            NbtIo.write(tag, output);
        }
        return bytes.toByteArray();
    }

    private static CompoundTag chunk(int dataVersion, int chunkX, int chunkZ) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", dataVersion);
        root.putInt("xPos", chunkX);
        root.putInt("zPos", chunkZ);
        CompoundTag structures = new CompoundTag();
        structures.put("starts", new CompoundTag());
        root.put("structures", structures);
        return root;
    }

    private static CompoundTag start(ResourceLocation id, int[][] boxes) {
        CompoundTag start = new CompoundTag();
        start.putString("id", id.toString());
        ListTag children = new ListTag();
        for (int[] box : boxes) {
            CompoundTag child = new CompoundTag();
            child.putIntArray("BB", box);
            children.add(child);
        }
        start.put("Children", children);
        return start;
    }
}
