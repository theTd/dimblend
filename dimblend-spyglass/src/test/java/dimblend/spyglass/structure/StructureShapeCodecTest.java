package dimblend.spyglass.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.net.SpyglassShapePayload;
import dimblend.spyglass.test.TestBiomes;
import dimblend.spyglass.test.VanillaTestBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

class StructureShapeCodecTest {
    static {
        VanillaTestBootstrap.ensure();
    }

    private static final Registry<Biome> BIOMES = TestBiomes.create();
    private static final ResourceLocation BIOME = TestBiomes.PLAINS.location();
    private static final StructureKey KEY = new StructureKey(
            ResourceLocation.fromNamespaceAndPath("test", "house"), 3, -7);
    private static final int STONE_ID = Block.getId(Blocks.STONE.defaultBlockState());

    @Test
    void roundTripsPalettePositionsOriginAndBiome() {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.WATERLOGGED, true);
        List<StructureShapeCodec.BlockEntry> blocks = new ArrayList<>();
        blocks.add(entry(4, 3, 5, 0, 0, 0, Blocks.STONE.defaultBlockState()));
        blocks.add(entry(4, 3, 5, 1, 1, 1, stairs));
        blocks.add(entry(4, 3, 5, 3, 2, 4, Blocks.STONE.defaultBlockState()));

        byte[] body = StructureShapeCodec.encode(-5, 60, 7, 4, 3, 5, BIOME, blocks);
        StructureShapeCodec.DecodedShape decoded = StructureShapeCodec.decode(body);

        assertEquals(-5, decoded.originX());
        assertEquals(60, decoded.originY());
        assertEquals(7, decoded.originZ());
        assertEquals(4, decoded.sizeX());
        assertEquals(3, decoded.sizeY());
        assertEquals(5, decoded.sizeZ());
        assertEquals(BIOME, decoded.biome());
        assertEquals(2, decoded.paletteIds().length);
        assertEquals(3, decoded.blockCount());
        assertEquals(0L, decoded.positions()[0]);
        assertEquals(25L, decoded.positions()[1]);
        assertEquals(59L, decoded.positions()[2]);

        StructureShape shape = resolve(decoded);
        assertEquals(KEY, shape.key());
        assertEquals(3, shape.blockCount());
        assertEquals(Blocks.STONE.defaultBlockState(), shape.getBlockState(-5, 60, 7));
        assertEquals(stairs, shape.getBlockState(-4, 61, 8));
        assertEquals(Blocks.STONE.defaultBlockState(), shape.getBlockState(-2, 62, 11));
        assertTrue(shape.getBlockState(0, 0, 0).isAir());
        assertEquals(TestBiomes.PLAINS, shape.biome().orElseThrow().unwrapKey().orElseThrow());
    }

    @Test
    void visitsEveryKeptBlockInIndexOrder() {
        List<StructureShapeCodec.BlockEntry> blocks = List.of(
                entry(2, 2, 2, 1, 1, 1, Blocks.STONE.defaultBlockState()),
                entry(2, 2, 2, 0, 0, 0, Blocks.DIRT.defaultBlockState()),
                entry(2, 2, 2, 0, 1, 0, Blocks.GRASS_BLOCK.defaultBlockState()));

        StructureShape shape = resolve(StructureShapeCodec.decode(
                StructureShapeCodec.encode(10, 20, 30, 2, 2, 2, BIOME, blocks)));

        List<String> visited = new ArrayList<>();
        shape.forEachBlock((x, y, z, state) -> visited.add(
                x + "," + y + "," + z + "=" + Block.getId(state)));
        assertEquals(List.of(
                "10,20,30=" + Block.getId(Blocks.DIRT.defaultBlockState()),
                "10,21,30=" + Block.getId(Blocks.GRASS_BLOCK.defaultBlockState()),
                "11,21,31=" + Block.getId(Blocks.STONE.defaultBlockState())), visited);
    }

    @Test
    void encodesTheBlockCapIntoMoreThanOneWirePart() {
        // 16 x 1024 x 16 blocks = 262,144 blocks: exactly the cap. With one-byte deltas and
        // one-byte palette indices the body is just over 512 KiB and needs many wire parts.
        int sizeX = 16;
        int sizeY = 1024;
        int sizeZ = 16;
        List<StructureShapeCodec.BlockEntry> blocks = new ArrayList<>(sizeX * sizeY * sizeZ);
        for (long index = 0; index < (long) sizeX * sizeY * sizeZ; index++) {
            blocks.add(new StructureShapeCodec.BlockEntry(index, Blocks.STONE.defaultBlockState()));
        }

        byte[] body = StructureShapeCodec.encode(0, 0, 0, sizeX, sizeY, sizeZ, BIOME, blocks);

        assertTrue(body.length > 512 * 1024, "body should need many wire parts: " + body.length);
        assertTrue(body.length > SpyglassShapePayload.MAX_PART_BYTES);
        StructureShapeCodec.DecodedShape decoded = StructureShapeCodec.decode(body);
        assertEquals(StructureShapeCodec.MAX_BLOCKS, decoded.blockCount());
        assertEquals(StructureShapeCodec.MAX_BLOCKS, resolve(decoded).blockCount());
    }

    @Test
    void roundTripsPositionsInALinearSpaceLargerThanAnInt() {
        // 1291^3 > Integer.MAX_VALUE: the index space and the deltas need the var-long encoding.
        int axis = 1291;
        long product = (long) axis * axis * axis;
        List<StructureShapeCodec.BlockEntry> blocks = List.of(
                new StructureShapeCodec.BlockEntry(0L, Blocks.STONE.defaultBlockState()),
                new StructureShapeCodec.BlockEntry(1L << 31, Blocks.DIRT.defaultBlockState()),
                new StructureShapeCodec.BlockEntry(product - 1, Blocks.GRASS_BLOCK.defaultBlockState()));

        StructureShapeCodec.DecodedShape decoded = StructureShapeCodec.decode(StructureShapeCodec.encode(
                0, 0, 0, axis, axis, axis, BIOME, blocks));

        assertEquals(3, decoded.blockCount());
        assertEquals(0L, decoded.positions()[0]);
        assertEquals(1L << 31, decoded.positions()[1]);
        assertEquals(product - 1L, decoded.positions()[2]);
    }

    @Test
    void rejectsDuplicateAndOutOfRangeEncodes() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        assertThrows(IllegalArgumentException.class, () -> StructureShapeCodec.encode(
                0, 0, 0, 2, 2, 2, BIOME,
                List.of(new StructureShapeCodec.BlockEntry(1, stone),
                        new StructureShapeCodec.BlockEntry(1, stone))));
        assertThrows(IllegalArgumentException.class, () -> StructureShapeCodec.encode(
                0, 0, 0, 2, 2, 2, BIOME,
                List.of(new StructureShapeCodec.BlockEntry(8, stone))));
        assertThrows(IllegalArgumentException.class, () -> StructureShapeCodec.encode(
                0, 0, 0, 0, 2, 2, BIOME,
                List.of(new StructureShapeCodec.BlockEntry(0, stone))));
        assertThrows(IllegalArgumentException.class, () -> StructureShapeCodec.encode(
                0, 0, 0, 1, 1, 1, BIOME, List.of()));
    }

    @Test
    void rejectsOversizedBodies() {
        assertThrows(DecoderException.class,
                () -> StructureShapeCodec.decode(new byte[StructureShapeCodec.MAX_BODY_BYTES + 1]));
        assertThrows(DecoderException.class, () -> StructureShapeCodec.decode(new byte[0]));
    }

    @Test
    void rejectsABlockCountAboveTheCap() {
        FriendlyByteBuf body = header(1, 1, 1);
        body.writeVarInt(1);
        body.writeVarInt(STONE_ID);
        body.writeVarInt(StructureShapeCodec.MAX_BLOCKS + 1);

        assertThrows(DecoderException.class, () -> decode(body));
    }

    @Test
    void rejectsPositionsOutsideTheSize() {
        FriendlyByteBuf body = header(1, 1, 1);
        body.writeVarInt(1);
        body.writeVarInt(STONE_ID);
        body.writeVarInt(1);
        body.writeVarInt(2); // only index 0 exists in a 1x1x1 shape
        body.writeVarInt(0);

        assertThrows(DecoderException.class, () -> decode(body));
    }

    @Test
    void rejectsNonIncreasingPositions() {
        FriendlyByteBuf body = header(2, 1, 1);
        body.writeVarInt(1);
        body.writeVarInt(STONE_ID);
        body.writeVarInt(2);
        body.writeVarInt(2); // first index = 1
        body.writeVarInt(0);
        body.writeVarInt(0); // zero delta: duplicate position
        body.writeVarInt(0);

        assertThrows(DecoderException.class, () -> decode(body));
    }

    @Test
    void rejectsPaletteIndicesAndCountsOutsideTheirRange() {
        FriendlyByteBuf body = header(1, 1, 1);
        body.writeVarInt(1);
        body.writeVarInt(STONE_ID);
        body.writeVarInt(1);
        body.writeVarInt(1);
        body.writeVarInt(2); // palette has one entry

        assertThrows(DecoderException.class, () -> decode(body));

        FriendlyByteBuf emptyPalette = header(1, 1, 1);
        emptyPalette.writeVarInt(0);
        emptyPalette.writeVarInt(1);
        assertThrows(DecoderException.class, () -> decode(emptyPalette));
    }

    @Test
    void rejectsTrailingBytes() {
        FriendlyByteBuf body = header(1, 1, 1);
        body.writeVarInt(1);
        body.writeVarInt(STONE_ID);
        body.writeVarInt(1);
        body.writeVarInt(1);
        body.writeVarInt(0);
        body.writeByte(0x42);

        assertThrows(DecoderException.class, () -> decode(body));
    }

    @Test
    void rejectsInvalidAxisSizes() {
        FriendlyByteBuf zeroAxis = headerPrefix();
        zeroAxis.writeVarInt(0);
        assertThrows(DecoderException.class, () -> decode(zeroAxis));

        FriendlyByteBuf hugeAxis = headerPrefix();
        hugeAxis.writeVarInt(StructureShapeCodec.MAX_AXIS + 1);
        assertThrows(DecoderException.class, () -> decode(hugeAxis));
    }

    private static StructureShape resolve(StructureShapeCodec.DecodedShape decoded) {
        BlockState[] palette = new BlockState[decoded.paletteIds().length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = Block.stateById(decoded.paletteIds()[i]);
            assertNotNull(palette[i]);
        }
        Optional<Holder<Biome>> biome = BIOMES
                .getHolder(ResourceKey.create(Registries.BIOME, decoded.biome()))
                .map(holder -> (Holder<Biome>) holder);
        return StructureShape.of(KEY, 5, decoded, palette, biome);
    }

    private static StructureShapeCodec.BlockEntry entry(
            int sizeX, int sizeY, int sizeZ, int x, int y, int z, BlockState state) {
        return new StructureShapeCodec.BlockEntry(((long) y * sizeZ + z) * sizeX + x, state);
    }

    private static FriendlyByteBuf headerPrefix() {
        FriendlyByteBuf body = new FriendlyByteBuf(Unpooled.buffer());
        body.writeInt(0);
        body.writeInt(0);
        body.writeInt(0);
        return body;
    }

    /** Writes everything up to the palette count for a shape of the given size. */
    private static FriendlyByteBuf header(int sizeX, int sizeY, int sizeZ) {
        FriendlyByteBuf body = headerPrefix();
        body.writeVarInt(sizeX);
        body.writeVarInt(sizeY);
        body.writeVarInt(sizeZ);
        body.writeResourceLocation(BIOME);
        return body;
    }

    private static StructureShapeCodec.DecodedShape decode(FriendlyByteBuf body) {
        ByteBuf buffer = body;
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return StructureShapeCodec.decode(bytes);
    }
}
