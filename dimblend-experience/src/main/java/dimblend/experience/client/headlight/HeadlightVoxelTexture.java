package dimblend.experience.client.headlight;

import java.nio.ByteBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

/**
 * An R8 3D occupancy texture (0 = clear, 255 = occluder; read with texelFetch). The first GL call of a frame
 * opens an {@link Upload} scope that pins the unpack state and 3D binding (vanilla does not track them);
 * {@link #finish()} restores them. Frames without uploads touch no GL state.
 */
final class HeadlightVoxelTexture implements AutoCloseable {
    final int width, height, depth;
    private int id;
    private Upload scope;

    HeadlightVoxelTexture(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    int id() { return id; }

    boolean allocated() { return id != 0; }

    /** Allocates (zero-filled) on first use. */
    void ensure() {
        if (id != 0) return;
        begin();
        id = GL11.glGenTextures();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL12.GL_TEXTURE_3D, GL12.GL_TEXTURE_WRAP_R, GL12.GL_CLAMP_TO_EDGE);
        ByteBuffer zeros = MemoryUtil.memCalloc(width * height * depth);
        try {
            GL12.glTexImage3D(GL12.GL_TEXTURE_3D, 0, GL30.GL_R8, width, height, depth, 0,
                    GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, zeros);
        } finally {
            MemoryUtil.memFree(zeros);
        }
    }

    /** Region upload, x fastest then y then z. */
    void upload(int x, int y, int z, int sx, int sy, int sz, ByteBuffer data) {
        begin();
        GL11.glBindTexture(GL12.GL_TEXTURE_3D, id);
        GL12.glTexSubImage3D(GL12.GL_TEXTURE_3D, 0, x, y, z, sx, sy, sz, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, data);
    }

    private void begin() {
        if (scope == null) scope = new Upload();
    }

    /** Restores the state saved by the first upload of this frame, if any. */
    void finish() {
        if (scope != null) scope.close();
        scope = null;
    }

    @Override
    public void close() {
        finish();
        if (id != 0) GL11.glDeleteTextures(id);
        id = 0;
    }

    /** Saves and restores the unpack parameters, unpack buffer and the active unit's 3D binding. */
    static final class Upload implements AutoCloseable {
        private final int buffer = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        private final int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        private final int rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        private final int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        private final int skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        private final int imageHeight = GL11.glGetInteger(GL12.GL_UNPACK_IMAGE_HEIGHT);
        private final int skipImages = GL11.glGetInteger(GL12.GL_UNPACK_SKIP_IMAGES);
        private final int binding = GL11.glGetInteger(GL12.GL_TEXTURE_BINDING_3D);

        Upload() {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, 0);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, 0);
        }

        @Override
        public void close() {
            GL11.glBindTexture(GL12.GL_TEXTURE_3D, binding);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL11.glPixelStorei(GL12.GL_UNPACK_IMAGE_HEIGHT, imageHeight);
            GL11.glPixelStorei(GL12.GL_UNPACK_SKIP_IMAGES, skipImages);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, buffer);
        }
    }
}
