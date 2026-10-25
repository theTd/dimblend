package dimblend.spyglass.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShape;
import dimblend.spyglass.structure.StructureShapeCodec;
import dimblend.spyglass.test.TestBiomes;
import dimblend.spyglass.test.VanillaTestBootstrap;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

class StructureShapeBlockGetterTest {
    static {
        VanillaTestBootstrap.ensure();
    }

    private static final Registry<Biome> BIOMES = TestBiomes.create();
    private static final Holder<Biome> PLAINS = TestBiomes.holder(BIOMES, TestBiomes.PLAINS);
    private static final Holder<Biome> DESERT = TestBiomes.holder(BIOMES, TestBiomes.DESERT);
    private static final StructureKey KEY = new StructureKey(
            ResourceLocation.fromNamespaceAndPath("test", "tower"), 1, 2);
    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState WET_STAIRS = Blocks.OAK_STAIRS.defaultBlockState()
            .setValue(StairBlock.WATERLOGGED, true);
    /** Tells the biomes apart and echoes the coordinates the getter passed in. */
    private static final ColorResolver MARKER = (biome, x, z) ->
            (biome == DESERT.value() ? 0x100000 : biome == PLAINS.value() ? 0x200000 : 0x300000)
                    + (int) x * 100 + (int) z;

    @Test
    void readsBlocksFromTheShapeAndAirElsewhere() {
        StructureShapeBlockGetter getter = getter(shape(Optional.of(DESERT)), values(Optional.of(PLAINS)));

        assertSame(STONE, getter.getBlockState(new BlockPos(10, 64, 20)));
        assertSame(WET_STAIRS, getter.getBlockState(new BlockPos(11, 65, 20)));
        assertTrue(getter.getBlockState(new BlockPos(11, 64, 20)).isAir(), "inside the box but not kept");
        assertTrue(getter.getBlockState(new BlockPos(500, 64, 20)).isAir(), "outside the shape");
    }

    @Test
    void reportsNoFluidsEvenForWaterloggedBlocksAndNoBlockEntities() {
        StructureShapeBlockGetter getter = getter(shape(Optional.of(DESERT)), values(Optional.of(PLAINS)));
        BlockPos stairs = new BlockPos(11, 65, 20);

        assertTrue(WET_STAIRS.getFluidState().isSource(), "the block state itself is waterlogged");
        assertTrue(getter.getFluidState(stairs).isEmpty());
        assertTrue(getter.getFluidState(new BlockPos(10, 64, 20)).isEmpty());
        assertNull(getter.getBlockEntity(stairs));
    }

    @Test
    void tintsWithTheShapeBiomeAndTheQueriedColumn() {
        StructureShapeBlockGetter getter = getter(shape(Optional.of(DESERT)), values(Optional.of(PLAINS)));

        assertEquals(0x100000 + 11 * 100 + 20, getter.getBlockTint(new BlockPos(11, 65, 20), MARKER));
        assertSame(DESERT, getter.tintBiome().orElseThrow());
    }

    @Test
    void fallsBackToPlainsWithoutAShapeBiome() {
        StructureShapeBlockGetter getter = getter(shape(Optional.empty()), values(Optional.of(PLAINS)));

        assertEquals(0x200000 + 10 * 100 + 20, getter.getBlockTint(new BlockPos(10, 64, 20), MARKER));
        assertSame(PLAINS, getter.tintBiome().orElseThrow());
    }

    @Test
    void doesNotTintWithoutAnyBiome() {
        StructureShapeBlockGetter getter = getter(shape(Optional.empty()), values(Optional.empty()));

        assertEquals(StructureShapeBlockGetter.NO_TINT, getter.getBlockTint(new BlockPos(10, 64, 20), MARKER));
        assertTrue(getter.tintBiome().isEmpty());
    }

    @Test
    void hasFullSkyLightAndNoBlockLight() {
        StructureShapeBlockGetter getter = getter(shape(Optional.of(DESERT)), values(Optional.of(PLAINS)));
        BlockPos pos = new BlockPos(10, 64, 20);

        assertEquals(15, getter.getBrightness(LightLayer.SKY, pos));
        assertEquals(0, getter.getBrightness(LightLayer.BLOCK, pos));
        assertEquals(11, getter.getRawBrightness(pos, 4));
        assertEquals(0, getter.getRawBrightness(pos, 20));
        assertTrue(getter.canSeeSky(pos));
        assertNotNull(getter.getLightEngine());
    }

    @Test
    void usesTheCapturedClientLevelShadeAndHeight() {
        StructureShapeBlockGetter getter = getter(shape(Optional.of(DESERT)), values(Optional.of(PLAINS)));

        assertEquals(0.5F, getter.getShade(Direction.DOWN, true));
        assertEquals(1.0F, getter.getShade(Direction.UP, true));
        assertEquals(0.8F, getter.getShade(Direction.NORTH, true));
        assertEquals(0.6F, getter.getShade(Direction.EAST, true));
        assertEquals(1.0F, getter.getShade(Direction.DOWN, false));
        assertEquals(-64, getter.getMinBuildHeight());
        assertEquals(384, getter.getHeight());
    }

    @Test
    void normalShadeMirrorsTheClientLevel() {
        StructureShapeBlockGetter getter = getter(shape(Optional.of(DESERT)), values(Optional.of(PLAINS)));

        assertEquals(1.0F, getter.getShade(0.0F, 1.0F, 0.0F, true), 1.0E-6F);
        assertEquals(0.5F, getter.getShade(0.0F, -1.0F, 0.0F, true), 1.0E-6F);
        assertEquals(0.6F, getter.getShade(1.0F, 0.0F, 0.0F, true), 1.0E-6F);
        assertEquals(1.0F, getter.getShade(1.0F, 0.0F, 0.0F, false), 1.0E-6F);
    }

    private static StructureShapeBlockGetter getter(StructureShape shape, StructureShapeBlockGetter.LevelValues values) {
        return new StructureShapeBlockGetter(shape, values);
    }

    /** Overworld values as ClientLevel#getShade returns them without constant ambient light. */
    private static StructureShapeBlockGetter.LevelValues values(Optional<Holder<Biome>> plains) {
        float[] shades = new float[Direction.values().length * 2];
        for (Direction direction : Direction.values()) {
            float shaded = switch (direction) {
                case DOWN -> 0.5F;
                case UP -> 1.0F;
                case NORTH, SOUTH -> 0.8F;
                case WEST, EAST -> 0.6F;
            };
            shades[StructureShapeBlockGetter.LevelValues.shadeIndex(direction, true)] = shaded;
            shades[StructureShapeBlockGetter.LevelValues.shadeIndex(direction, false)] = 1.0F;
        }
        return new StructureShapeBlockGetter.LevelValues(shades, false, -64, 384, plains);
    }

    /** A 2x2x1 shape at (10, 64, 20) with stone at the origin and a waterlogged stair diagonally above. */
    private static StructureShape shape(Optional<Holder<Biome>> biome) {
        List<StructureShapeCodec.BlockEntry> blocks = new ArrayList<>();
        blocks.add(new StructureShapeCodec.BlockEntry(0L, STONE));
        // (relX 1, relY 1, relZ 0) in a 2 x 2 x 1 box: (1 * 1 + 0) * 2 + 1 = 3
        blocks.add(new StructureShapeCodec.BlockEntry(3L, WET_STAIRS));
        StructureShapeCodec.DecodedShape decoded = StructureShapeCodec.decode(StructureShapeCodec.encode(
                10, 64, 20, 2, 2, 1, TestBiomes.DESERT.location(), blocks));
        BlockState[] palette = new BlockState[decoded.paletteIds().length];
        for (int i = 0; i < palette.length; i++) {
            palette[i] = Block.stateById(decoded.paletteIds()[i]);
        }
        return StructureShape.of(KEY, 7, decoded, palette, biome);
    }
}
