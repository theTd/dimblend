package dimblend.spyglass.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShapeCodec;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

class TemplatePreviewExtractorTest {
    private static final StructureKey KEY =
            new StructureKey(ResourceLocation.fromNamespaceAndPath("test", "house"), 0, 0);

    @Test
    void transformUsesThePieceOriginAndRotation() {
        BlockPos origin = new BlockPos(100, 64, 100);
        BlockPos local = new BlockPos(1, 0, 0);

        assertEquals(new BlockPos(101, 64, 100),
                TemplatePreviewExtractor.worldPos(local, origin, Rotation.NONE));
        assertEquals(new BlockPos(100, 64, 101),
                TemplatePreviewExtractor.worldPos(local, origin, Rotation.CLOCKWISE_90));
        assertEquals(new BlockPos(100, 64, 99),
                TemplatePreviewExtractor.worldPos(local, origin, Rotation.COUNTERCLOCKWISE_90));
        assertEquals(new BlockPos(99, 64, 100),
                TemplatePreviewExtractor.worldPos(local, origin, Rotation.CLOCKWISE_180));
    }

    @Test
    void structureMarkersAndVoidAreFilteredOut() {
        assertTrue(TemplatePreviewExtractor.keep(Blocks.STONE.defaultBlockState()));
        assertFalse(TemplatePreviewExtractor.keep(Blocks.AIR.defaultBlockState()));
        assertFalse(TemplatePreviewExtractor.keep(Blocks.STRUCTURE_BLOCK.defaultBlockState()));
        assertFalse(TemplatePreviewExtractor.keep(Blocks.JIGSAW.defaultBlockState()));
        assertFalse(TemplatePreviewExtractor.keep(Blocks.STRUCTURE_VOID.defaultBlockState()));
    }

    @Test
    void parsesTheSinglePaletteAndItsBlocks() {
        CompoundTag tag = templateTag(Blocks.STONE.defaultBlockState(), 2, 3, 4);

        List<TemplatePreviewExtractor.TemplateBlock> blocks = TemplatePreviewExtractor.parseTemplate(tag);

        assertEquals(1, blocks.size());
        assertEquals(new BlockPos(2, 3, 4), blocks.get(0).pos());
        assertEquals(Blocks.STONE.defaultBlockState(), blocks.get(0).state());
    }

    @Test
    void multiplePalettesUseTheFirstOne() {
        CompoundTag tag = new CompoundTag();
        ListTag palettes = new ListTag();
        ListTag first = new ListTag();
        first.add(NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()));
        ListTag second = new ListTag();
        second.add(NbtUtils.writeBlockState(Blocks.DIRT.defaultBlockState()));
        palettes.add(first);
        palettes.add(second);
        tag.put("palettes", palettes);
        ListTag blocks = new ListTag();
        blocks.add(block(0, 0, 0, 0));
        tag.put("blocks", blocks);

        List<TemplatePreviewExtractor.TemplateBlock> parsed = TemplatePreviewExtractor.parseTemplate(tag);

        assertEquals(1, parsed.size());
        assertEquals(Blocks.STONE.defaultBlockState(), parsed.get(0).state());
    }

    @Test
    void encodingNothingIsNull() {
        assertNull(TemplatePreviewExtractor.encode(KEY, List.of()));
    }

    @Test
    void encodingOneBlockRoundTripsThroughTheShapeCodec() {
        List<TemplatePreviewExtractor.WorldBlock> blocks = List.of(
                new TemplatePreviewExtractor.WorldBlock(new BlockPos(5, 6, 7), Blocks.STONE.defaultBlockState()));

        EncodedStructureShape shape = TemplatePreviewExtractor.encode(KEY, blocks);

        assertNotNull(shape);
        assertEquals(1, shape.blockCount());
        StructureShapeCodec.DecodedShape decoded = StructureShapeCodec.decode(shape.joinParts());
        assertEquals(1, decoded.positions().length);
    }

    private static CompoundTag templateTag(BlockState state, int x, int y, int z) {
        CompoundTag tag = new CompoundTag();
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(state));
        tag.put("palette", palette);
        ListTag blocks = new ListTag();
        blocks.add(block(x, y, z, 0));
        tag.put("blocks", blocks);
        return tag;
    }

    private static CompoundTag block(int x, int y, int z, int state) {
        CompoundTag block = new CompoundTag();
        ListTag pos = new ListTag();
        pos.add(IntTag.valueOf(x));
        pos.add(IntTag.valueOf(y));
        pos.add(IntTag.valueOf(z));
        block.put("pos", pos);
        block.putInt("state", state);
        return block;
    }
}
