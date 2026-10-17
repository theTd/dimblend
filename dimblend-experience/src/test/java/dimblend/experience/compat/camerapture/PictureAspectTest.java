package dimblend.experience.compat.camerapture;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class PictureAspectTest {
    @Test
    void widescreenShotCropsOnlyTheSides() {
        assertEquals(new PictureAspect.Crop(240, 0, 1440, 1080), PictureAspect.crop(1920, 1080));
    }

    @Test
    void portraitShotCropsTheTopAndBottom() {
        assertEquals(new PictureAspect.Crop(0, 555, 1080, 810), PictureAspect.crop(1080, 1920));
    }

    @Test
    void oddFramebufferStillProducesExactFourByThree() {
        assertEquals(new PictureAspect.Crop(1, 1, 1000, 750), PictureAspect.crop(1003, 753));
        assertEquals(new PictureAspect.Crop(0, 0, 1440, 1080), PictureAspect.crop(1440, 1080));
    }

    @Test
    void croppedImageRetainsPixelsWithoutStretchingOrChangingSource() {
        var image = new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB);
        image.setRGB(2, 0, 0x123456);
        image.setRGB(13, 8, 0xabcdef);
        var cropped = PictureAspect.crop(image);
        assertEquals(12, cropped.getWidth());
        assertEquals(9, cropped.getHeight());
        assertEquals(0xff123456, cropped.getRGB(0, 0));
        assertEquals(0xffabcdef, cropped.getRGB(11, 8));
        assertEquals(16, image.getWidth());
    }

    @Test
    void framedLandscapeCropsHorizontallyAndExposesTheRim() {
        var frame = PictureAspect.framed(1920, 1080);
        assertEquals(0.125F, frame.u0());
        assertEquals(0.875F, frame.u1());
        assertEquals(0, frame.v0());
        assertEquals(1, frame.v1());
        assertEquals(14 / 16.0F, frame.width());
        assertEquals(4 / 3.0F, frame.width() / frame.height(), 0.00001F);
        assertEquals(1920.0F / 1080 * (frame.u1() - frame.u0()) / (frame.v1() - frame.v0()),
                frame.width() / frame.height(), 0.00001F);
    }

    @Test
    void framedPortraitAndSquareKeepTheirCenterWithoutStretching() {
        for (int[] dimensions : new int[][]{{900, 1600}, {1000, 1000}, {1200, 900}}) {
            var frame = PictureAspect.framed(dimensions[0], dimensions[1]);
            assertEquals(1, frame.u0() + frame.u1());
            assertEquals(1, frame.v0() + frame.v1());
            assertEquals(4 / 3.0F, (float) dimensions[0] / dimensions[1]
                    * (frame.u1() - frame.u0()) / (frame.v1() - frame.v0()), 0.00001F);
        }
    }

    @Test
    void invalidDimensionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PictureAspect.crop(3, 3));
        assertThrows(IllegalArgumentException.class, () -> PictureAspect.framed(0, 100));
    }
}
