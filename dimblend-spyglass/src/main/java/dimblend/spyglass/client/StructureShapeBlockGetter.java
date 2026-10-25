package dimblend.spyglass.client;

import dimblend.spyglass.structure.StructureShape;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.model.lighting.QuadLighter;
import org.jetbrains.annotations.Nullable;

/**
 * Read-only level view over one {@link StructureShape}, handed to the vanilla block model renderer
 * to mesh the shape off the render thread ({@link ShapeMeshBuilder}).
 *
 * <p>Everything the model renderer asks for comes from immutable inputs, so an instance is safe on
 * the mesh worker without touching the client level:
 *
 * <ul>
 *   <li><b>Blocks</b> come from the shape; positions outside its kept set are air, so faces against
 *       them are drawn and faces between two kept full blocks are culled by
 *       {@code Block.shouldRenderFace} exactly like in a real chunk.</li>
 *   <li><b>Fluids</b> are never reported: fluids are not rendered, and a waterlogged block renders
 *       its block model only.</li>
 *   <li><b>Light</b> is full sky light and no block light everywhere. {@code LevelRenderer.getLightColor}
 *       reads {@link #getBrightness} for both layers and raises block light to the state's own
 *       emission ({@code LevelRenderer.java:3492-3505}), so glowstone still glows; the packed sky
 *       light 15 then samples the lightmap's day/night column like outdoor terrain.</li>
 *   <li><b>Tint</b> uses the shape's single dominant biome with the requested resolver (grass,
 *       foliage, water); without a biome it falls back to the given plains holder.</li>
 *   <li><b>Shade and height</b> are the client level's values captured on the render thread
 *       ({@link LevelValues}: {@code ClientLevel#getShade(Direction, boolean)},
 *       {@code ClientLevel.java:827}); the normal-based overload mirrors NeoForge's
 *       {@code ClientLevel#getShade(float, float, float, boolean)} (NeoForge-patched
 *       {@code ClientLevel.java:1160-1165}).</li>
 *   <li><b>Block entities</b> do not exist here; block-entity renderers are skipped.</li>
 * </ul>
 */
final class StructureShapeBlockGetter implements BlockAndTintGetter {
    /** Sky light everywhere: the maximum light level. */
    static final int SKY_LIGHT = 15;
    /** Tint used only if neither the shape's biome nor the plains fallback resolves. */
    static final int NO_TINT = 0xFFFFFF;

    private static final FluidState NO_FLUID = Fluids.EMPTY.defaultFluidState();

    /**
     * Client level values captured on the render thread for one build.
     *
     * @param shades {@code ClientLevel#getShade(direction, shade)} for every direction, indexed
     *     {@code direction.ordinal() * 2 + (shade ? 1 : 0)}
     * @param constantAmbientLight the dimension effects' flag (Nether-style flat shading)
     * @param minBuildHeight the client level's minimum build height
     * @param height the client level's build height
     * @param plains the client registry's plains biome, the tint fallback
     */
    record LevelValues(
            float[] shades,
            boolean constantAmbientLight,
            int minBuildHeight,
            int height,
            Optional<Holder<Biome>> plains) {
        LevelValues {
            Objects.requireNonNull(plains, "plains");
            if (shades.length != Direction.values().length * 2) {
                throw new IllegalArgumentException("Need a shade per direction and shade flag");
            }
            shades = shades.clone();
        }

        static int shadeIndex(Direction direction, boolean shade) {
            return direction.ordinal() * 2 + (shade ? 1 : 0);
        }
    }

    private final StructureShape shape;
    private final LevelValues level;
    private final @Nullable Holder<Biome> tintBiome;
    private final LevelLightEngine noLightEngine;

    StructureShapeBlockGetter(StructureShape shape, LevelValues level) {
        this.shape = Objects.requireNonNull(shape, "shape");
        this.level = Objects.requireNonNull(level, "level");
        this.tintBiome = shape.biome().or(level::plains).orElse(null);
        // Only reached by code that bypasses getBrightness/getRawBrightness; both layers are off,
        // so it reports darkness instead of failing on a missing engine.
        this.noLightEngine = new LevelLightEngine(new NoLightChunks(this), false, false);
    }

    /** The biome used for every tint lookup, or empty when neither shape nor fallback has one. */
    Optional<Holder<Biome>> tintBiome() {
        return Optional.ofNullable(this.tintBiome);
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.shape.getBlockState(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return NO_FLUID;
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        Holder<Biome> biome = this.tintBiome;
        if (biome == null) {
            return NO_TINT;
        }
        return colorResolver.getColor(biome.value(), pos.getX(), pos.getZ());
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return this.level.shades()[LevelValues.shadeIndex(direction, shade)];
    }

    @Override
    public float getShade(float normalX, float normalY, float normalZ, boolean shade) {
        boolean constantAmbientLight = this.level.constantAmbientLight();
        if (!shade) {
            return constantAmbientLight ? 0.9F : 1.0F;
        }
        return QuadLighter.calculateShade(normalX, normalY, normalZ, constantAmbientLight);
    }

    @Override
    public int getBrightness(LightLayer lightType, BlockPos blockPos) {
        return lightType == LightLayer.SKY ? SKY_LIGHT : 0;
    }

    @Override
    public int getRawBrightness(BlockPos blockPos, int amount) {
        // LevelLightEngine.getRawBrightness: max(block, sky - amount) with block light 0.
        return Math.max(0, SKY_LIGHT - amount);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.noLightEngine;
    }

    @Override
    public int getHeight() {
        return this.level.height();
    }

    @Override
    public int getMinBuildHeight() {
        return this.level.minBuildHeight();
    }

    private record NoLightChunks(BlockGetter level) implements LightChunkGetter {
        @Override
        public @Nullable LightChunk getChunkForLighting(int chunkX, int chunkZ) {
            return null;
        }

        @Override
        public BlockGetter getLevel() {
            return this.level;
        }
    }
}
