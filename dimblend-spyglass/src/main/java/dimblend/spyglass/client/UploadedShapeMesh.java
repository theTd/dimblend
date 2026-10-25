package dimblend.spyglass.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dimblend.spyglass.structure.OutlineBox;
import dimblend.spyglass.structure.StructureKey;
import org.jetbrains.annotations.Nullable;

/**
 * GPU side of one structure mesh: a static {@link VertexBuffer} per non-empty chunk layer
 * ({@link ShapeMeshLayers}), vertices relative to {@code origin}. Render thread only.
 *
 * <p>An empty mesh (no layer has geometry, e.g. a structure made only of block-entity-rendered
 * blocks) is cached too, so it is not rebuilt every frame; the renderer shows nothing for it.
 *
 * <p>The frame stamps tell the AFTER_PARTICLES overlay pass and the label hand-off what happened to
 * this mesh in the selection pass: selected (it replaced everything else for this structure) and
 * drawn (inside the view, in one of the two passes, gets a label).
 */
final class UploadedShapeMesh implements ShapeMeshCache.Mesh {
    private final StructureKey key;
    private final int revision;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final OutlineBox bounds;
    private final @Nullable VertexBuffer[] layers;
    private final long vertexCount;
    private boolean closed;
    private long selectedFrame = -1L;
    private long drawnFrame = -1L;

    UploadedShapeMesh(
            StructureKey key,
            int revision,
            int originX,
            int originY,
            int originZ,
            OutlineBox bounds,
            @Nullable VertexBuffer[] layers,
            long vertexCount) {
        this.key = key;
        this.revision = revision;
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.bounds = bounds;
        this.layers = layers;
        this.vertexCount = vertexCount;
    }

    StructureKey key() {
        return this.key;
    }

    @Override
    public int revision() {
        return this.revision;
    }

    @Override
    public long vertexCount() {
        return this.vertexCount;
    }

    int originX() {
        return this.originX;
    }

    int originY() {
        return this.originY;
    }

    int originZ() {
        return this.originZ;
    }

    /** Box the geometry stays in (kept-block extent grown by one block). */
    OutlineBox bounds() {
        return this.bounds;
    }

    boolean isEmpty() {
        return this.vertexCount == 0L;
    }

    /** The buffer of a layer, or {@code null} when the layer has no geometry or the mesh is closed. */
    @Nullable VertexBuffer layer(int index) {
        return this.closed ? null : this.layers[index];
    }

    void markSelected(long frame) {
        this.selectedFrame = frame;
    }

    void markDrawn(long frame) {
        this.drawnFrame = frame;
    }

    boolean selectedIn(long frame) {
        return this.selectedFrame == frame;
    }

    boolean drawnIn(long frame) {
        return this.drawnFrame == frame;
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (int i = 0; i < this.layers.length; i++) {
            VertexBuffer buffer = this.layers[i];
            if (buffer != null) {
                this.layers[i] = null;
                buffer.close();
            }
        }
    }
}
