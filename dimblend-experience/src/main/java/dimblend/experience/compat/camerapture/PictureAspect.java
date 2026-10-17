package dimblend.experience.compat.camerapture;

import java.awt.image.BufferedImage;

/** Shared 4:3 composition for new camera shots, the viewfinder and framed pictures. */
public final class PictureAspect {
    public record Crop(int x, int y, int width, int height) { }

    public record Framed(float width, float height, float u0, float v0, float u1, float v1) { }

    /** Largest exact 4:3 pixel rectangle, centered without stretching or resampling. */
    public static Crop crop(int width, int height) {
        int units = Math.min(width / 4, height / 3);
        if (units < 1) {
            throw new IllegalArgumentException("Camera image must be at least 4 by 3 pixels");
        }
        int croppedWidth = units * 4;
        int croppedHeight = units * 3;
        return new Crop((width - croppedWidth) / 2, (height - croppedHeight) / 2,
                croppedWidth, croppedHeight);
    }

    public static BufferedImage crop(BufferedImage source) {
        Crop crop = crop(source.getWidth(), source.getHeight());
        return source.getSubimage(crop.x(), crop.y(), crop.width(), crop.height());
    }

    /** Leaves the native map frame's one-pixel rim exposed; only UVs crop old pictures. */
    public static Framed framed(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Picture dimensions must be positive");
        }
        float visibleWidth = Math.min(1.0F, (float) height * 4 / (width * 3.0F));
        float visibleHeight = Math.min(1.0F, (float) width * 3 / (height * 4.0F));
        return new Framed(14 / 16.0F, 10.5F / 16.0F,
                (1 - visibleWidth) / 2, (1 - visibleHeight) / 2,
                (1 + visibleWidth) / 2, (1 + visibleHeight) / 2);
    }

    private PictureAspect() { }
}
