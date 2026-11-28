package dimblend.client.mirage;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import javax.annotation.Nullable;

/**
 * GPU buffers of one mirage section: up to three {@link VertexBuffer}s (solid / cutout /
 * translucent) holding {@code DefaultVertexFormat.BLOCK} geometry in section-local 0..16 space,
 * plus the section origin the shader adds through {@code u_MirageOrigin}.
 *
 * <p>Every upload follows the POC crash rule: {@code vbo.bind(); vbo.upload(mesh); unbind();}.
 * {@code upload} closes the {@link MeshData} (and therefore its {@code ByteBufferBuilder.Result}),
 * so the caller's builder can be freed immediately afterwards.</p>
 *
 * <p>Creation and {@link #close()} both require the render thread;
 * {@link ClientMirageManager} only calls them from the render stage or a level unload.</p>
 */
public final class MirageSectionMesh {
    public static final int LAYER_SOLID = 0;
    public static final int LAYER_CUTOUT = 1;
    public static final int LAYER_TRANSLUCENT = 2;
    public static final int LAYERS = 3;

    private final int originX;
    private final int originY;
    private final int originZ;
    private final VertexBuffer[] buffers = new VertexBuffer[LAYERS];

    MirageSectionMesh(int originX, int originY, int originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    public int originX() {
        return this.originX;
    }

    public int originY() {
        return this.originY;
    }

    public int originZ() {
        return this.originZ;
    }

    @Nullable
    public VertexBuffer buffer(int layer) {
        return this.buffers[layer];
    }

    public boolean hasAny() {
        for (VertexBuffer buffer : this.buffers) {
            if (buffer != null) {
                return true;
            }
        }
        return false;
    }

    public boolean has(int layer) {
        return this.buffers[layer] != null;
    }

    /** Uploads one rebuilt layer; a null mesh clears that layer. Render thread only. */
    void accept(int layer, @Nullable MeshData mesh) {
        VertexBuffer existing = this.buffers[layer];
        if (mesh == null) {
            if (existing != null) {
                existing.close();
                this.buffers[layer] = null;
            }
            return;
        }
        if (existing == null) {
            existing = new VertexBuffer(VertexBuffer.Usage.STATIC);
            this.buffers[layer] = existing;
        }
        existing.bind();
        try {
            existing.upload(mesh);
        } finally {
            VertexBuffer.unbind();
        }
    }

    /** Draws one layer; the caller has already applied the shader and written the uniforms. */
    public boolean draw(int layer) {
        VertexBuffer buffer = this.buffers[layer];
        if (buffer == null) {
            return false;
        }
        buffer.bind();
        try {
            buffer.draw();
        } finally {
            VertexBuffer.unbind();
        }
        return true;
    }

    /** Releases every buffer. Render thread only; safe to call twice. */
    public void close() {
        for (int i = 0; i < LAYERS; i++) {
            VertexBuffer buffer = this.buffers[i];
            if (buffer != null) {
                buffer.close();
                this.buffers[i] = null;
            }
        }
    }
}
