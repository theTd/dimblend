package dimblend.spyglass.client;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dimblend.spyglass.structure.StructureShape;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

/**
 * Meshes one {@link StructureShape} into per-layer vertex data on a worker thread, the way vanilla's
 * section compiler meshes a chunk section on its worker threads ({@code SectionCompiler.java:43-101};
 * NeoForge's patched copy passes {@code ModelData} and the {@link RenderType} to the same
 * {@code renderBatched} overload used here, NeoForge-patched {@code BlockRenderDispatcher.java:74}).
 *
 * <p>Per kept block with {@link RenderShape#MODEL}: seed the random from the position, ask the
 * model for its chunk layers with {@code getRenderTypes(state, random, ModelData.EMPTY)} and call
 * {@link BlockRenderDispatcher#renderBatched(BlockState, BlockPos, net.minecraft.world.level.BlockAndTintGetter,
 * PoseStack, com.mojang.blaze3d.vertex.VertexConsumer, boolean, RandomSource, ModelData, RenderType)}
 * with side culling on, so faces between kept full blocks are dropped through
 * {@link StructureShapeBlockGetter}. Each layer gets its own {@link ByteBufferBuilder} and a
 * {@link BufferBuilder} in the chunk vertex format ({@link DefaultVertexFormat#BLOCK}, quads).
 * Translucent quads are sorted once, back to front from the camera position at request time.
 *
 * <p>Vertices are written relative to the shape origin (small floats); the renderer adds the
 * camera-relative origin through the chunk shader's {@code ChunkOffset} uniform.
 *
 * <p>Thread safety: vanilla runs the same calls on its section compile threads; the model renderer
 * keeps its per-thread state in a {@code ThreadLocal} ({@code ModelBlockRenderer.CACHE},
 * enabled/cleared here as in {@code SectionCompiler}), and every level query goes to the immutable
 * shape. Blocks without a {@code MODEL} render shape (chests, signs, banners and other block-entity
 * renderers) are skipped. A block whose model throws is skipped and counted instead of failing the
 * whole structure.
 */
final class ShapeMeshBuilder implements StructureShape.BlockConsumer {
    /** Initial bytes per layer buffer; buffers grow on demand. */
    private static final int INITIAL_LAYER_BYTES = 64 * 1024;
    /** Upper bound for the pre-sized solid buffer, so a huge shape does not reserve memory upfront. */
    private static final int MAX_INITIAL_SOLID_BYTES = 8 * 1024 * 1024;
    /** One quad in the chunk vertex format. */
    private static final int QUAD_BYTES = 4 * DefaultVertexFormat.BLOCK.getVertexSize();

    private final StructureShape shape;
    private final StructureShapeBlockGetter level;
    private final BlockRenderDispatcher dispatcher;
    private final List<RenderType> layers = ShapeMeshLayers.order();
    private final ByteBufferBuilder[] buffers;
    private final BufferBuilder[] builders;
    private final PoseStack pose = new PoseStack();
    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private int failedBlocks;
    private @Nullable RuntimeException firstFailure;
    private @Nullable BlockState firstFailedState;

    private ShapeMeshBuilder(StructureShape shape, StructureShapeBlockGetter level, BlockRenderDispatcher dispatcher) {
        this.shape = shape;
        this.level = level;
        this.dispatcher = dispatcher;
        this.buffers = new ByteBufferBuilder[this.layers.size()];
        this.builders = new BufferBuilder[this.layers.size()];
    }

    /**
     * Builds the mesh. The caller owns the result and must upload or close it.
     *
     * @param sortX camera position relative to the shape origin, for the translucent sort
     */
    static BuiltShapeMesh build(
            StructureShape shape,
            StructureShapeBlockGetter.LevelValues levelValues,
            BlockRenderDispatcher dispatcher,
            float sortX,
            float sortY,
            float sortZ) {
        ShapeMeshBuilder builder = new ShapeMeshBuilder(
                shape, new StructureShapeBlockGetter(shape, levelValues), dispatcher);
        return builder.run(VertexSorting.byDistance(sortX, sortY, sortZ));
    }

    private BuiltShapeMesh run(VertexSorting sorting) {
        MeshData[] meshes = new MeshData[this.layers.size()];
        ModelBlockRenderer.enableCaching();
        try {
            this.shape.forEachBlock(this);
            for (int layer = 0; layer < this.builders.length; layer++) {
                BufferBuilder builder = this.builders[layer];
                if (builder == null) {
                    continue;
                }
                MeshData mesh = builder.build();
                if (mesh == null) {
                    continue;
                }
                meshes[layer] = mesh;
                if (ShapeMeshLayers.isSorted(layer)) {
                    // The sorted index buffer goes into the same builder, as in SectionCompiler.
                    mesh.sortQuads(this.buffers[layer], sorting);
                }
            }
            return new BuiltShapeMesh(this.shape, meshes, this.buffers, this.failedBlocks, this.firstFailure,
                    this.firstFailedState);
        } catch (RuntimeException | Error exception) {
            BuiltShapeMesh.closeAll(meshes, this.buffers);
            throw exception;
        } finally {
            ModelBlockRenderer.clearCache();
        }
    }

    @Override
    public void accept(int x, int y, int z, BlockState state) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            return;
        }
        BlockPos pos = this.position.set(x, y, z);
        try {
            BakedModel model = this.dispatcher.getBlockModel(state);
            this.random.setSeed(state.getSeed(pos));
            for (RenderType type : model.getRenderTypes(state, this.random, ModelData.EMPTY)) {
                int layer = ShapeMeshLayers.indexOf(type);
                if (layer < 0) {
                    continue;
                }
                BufferBuilder consumer = builderFor(layer);
                this.pose.pushPose();
                try {
                    this.pose.translate(
                            (float) (x - this.shape.originX()),
                            (float) (y - this.shape.originY()),
                            (float) (z - this.shape.originZ()));
                    this.dispatcher.renderBatched(
                            state, pos, this.level, this.pose, consumer, true, this.random, ModelData.EMPTY, type);
                } finally {
                    this.pose.popPose();
                }
            }
        } catch (RuntimeException exception) {
            // renderBatched wraps model failures in a ReportedException; one broken mod model must not
            // cost the whole structure its mesh.
            this.failedBlocks++;
            if (this.firstFailure == null) {
                this.firstFailure = exception;
                this.firstFailedState = state;
            }
        }
    }

    private BufferBuilder builderFor(int layer) {
        BufferBuilder builder = this.builders[layer];
        if (builder == null) {
            int initial = INITIAL_LAYER_BYTES;
            if (this.layers.get(layer) == RenderType.solid()) {
                // Solid usually dominates: about one visible quad per kept block is a fair first guess.
                long guess = (long) this.shape.blockCount() * QUAD_BYTES;
                initial = (int) Math.max(INITIAL_LAYER_BYTES, Math.min(MAX_INITIAL_SOLID_BYTES, guess));
            }
            ByteBufferBuilder buffer = new ByteBufferBuilder(initial);
            this.buffers[layer] = buffer;
            builder = new BufferBuilder(buffer, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
            this.builders[layer] = builder;
        }
        return builder;
    }
}
