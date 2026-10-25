package dimblend.spyglass.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import dimblend.spyglass.structure.StructureShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * CPU-side result of meshing one structure shape: per-layer {@link MeshData} plus the native
 * {@link ByteBufferBuilder}s that hold their bytes.
 *
 * <p>Built on the mesh worker, then handed to the render thread through a concurrent queue (which
 * publishes it safely); exactly one thread owns it at a time. {@link #upload()} turns it into GPU
 * buffers; whatever happens, {@link #close()} (also called by {@code upload}) frees the native
 * memory — {@code MeshData} only points into its builder ({@code ByteBufferBuilder.Result} reads
 * {@code builder.pointer + offset} lazily), so the builders must outlive the upload and then be
 * closed explicitly.
 */
final class BuiltShapeMesh implements AutoCloseable {
    private final StructureKey key;
    private final int revision;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final OutlineBox bounds;
    private final @Nullable MeshData[] layers;
    private final @Nullable ByteBufferBuilder[] buffers;
    private final long vertexCount;
    private final int failedBlocks;
    private final @Nullable RuntimeException firstFailure;
    private final @Nullable BlockState firstFailedState;
    private boolean closed;

    BuiltShapeMesh(
            StructureShape shape,
            @Nullable MeshData[] layers,
            @Nullable ByteBufferBuilder[] buffers,
            int failedBlocks,
            @Nullable RuntimeException firstFailure,
            @Nullable BlockState firstFailedState) {
        this.key = shape.key();
        this.revision = shape.revision();
        this.originX = shape.originX();
        this.originY = shape.originY();
        this.originZ = shape.originZ();
        this.bounds = meshBounds(shape);
        this.layers = layers;
        this.buffers = buffers;
        long vertices = 0L;
        for (MeshData layer : layers) {
            if (layer != null) {
                vertices += layer.drawState().vertexCount();
            }
        }
        this.vertexCount = vertices;
        this.failedBlocks = failedBlocks;
        this.firstFailure = firstFailure;
        this.firstFailedState = firstFailedState;
    }

    /**
     * Box the mesh is guaranteed to stay in: the shape's kept-block extent grown by one block, since a
     * few block models (and random offsets) reach slightly past their own cell.
     *
     * <p>The grown coordinates are clamped to {@link OutlineBox#MAX_COORDINATE}: a shape that reaches
     * the limit would otherwise create an {@link OutlineBox} that rejects its own bounds, and the
     * exception would escape into {@code LevelRenderer}. A clamped box still contains the shape's
     * blocks (the clamp never moves an edge inward), so culling stays conservative.
     */
    static OutlineBox meshBounds(StructureShape shape) {
        return new OutlineBox(
                clampCoordinate((long) shape.originX() - 1L),
                clampCoordinate((long) shape.originY() - 1L),
                clampCoordinate((long) shape.originZ() - 1L),
                clampCoordinate((long) shape.originX() + shape.sizeX()),
                clampCoordinate((long) shape.originY() + shape.sizeY()),
                clampCoordinate((long) shape.originZ() + shape.sizeZ()));
    }

    private static int clampCoordinate(long coordinate) {
        return (int) Math.max(-OutlineBox.MAX_COORDINATE, Math.min(OutlineBox.MAX_COORDINATE, coordinate));
    }

    StructureKey key() {
        return this.key;
    }

    int revision() {
        return this.revision;
    }

    long vertexCount() {
        return this.vertexCount;
    }

    int failedBlocks() {
        return this.failedBlocks;
    }

    @Nullable RuntimeException firstFailure() {
        return this.firstFailure;
    }

    @Nullable BlockState firstFailedState() {
        return this.firstFailedState;
    }

    /**
     * Uploads every non-empty layer into its own static {@link VertexBuffer}, the way vanilla uploads
     * section layers ({@code SectionRenderDispatcher.java:203-212}: bind, upload, unbind;
     * {@code VertexBuffer.upload} closes the {@code MeshData}). Render thread only. Frees the native
     * memory whether or not the upload succeeds.
     */
    UploadedShapeMesh upload() {
        RenderSystem.assertOnRenderThread();
        if (this.closed) {
            throw new IllegalStateException("Mesh already closed");
        }
        VertexBuffer[] uploaded = new VertexBuffer[this.layers.length];
        try {
            for (int layer = 0; layer < this.layers.length; layer++) {
                MeshData mesh = this.layers[layer];
                if (mesh == null) {
                    continue;
                }
                this.layers[layer] = null;
                VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                uploaded[layer] = buffer;
                buffer.bind();
                buffer.upload(mesh);
                VertexBuffer.unbind();
            }
            UploadedShapeMesh result = new UploadedShapeMesh(
                    this.key, this.revision, this.originX, this.originY, this.originZ, this.bounds, uploaded,
                    this.vertexCount);
            uploaded = null;
            return result;
        } finally {
            if (uploaded != null) {
                VertexBuffer.unbind();
                for (VertexBuffer buffer : uploaded) {
                    if (buffer != null) {
                        buffer.close();
                    }
                }
            }
            close();
        }
    }

    /** Frees the native vertex memory; idempotent, safe on any one owning thread. */
    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        closeAll(this.layers, this.buffers);
    }

    static void closeAll(@Nullable MeshData[] layers, @Nullable ByteBufferBuilder[] buffers) {
        for (int i = 0; i < layers.length; i++) {
            MeshData mesh = layers[i];
            if (mesh != null) {
                layers[i] = null;
                mesh.close();
            }
        }
        for (int i = 0; i < buffers.length; i++) {
            ByteBufferBuilder buffer = buffers[i];
            if (buffer != null) {
                buffers[i] = null;
                buffer.close();
            }
        }
    }
}
