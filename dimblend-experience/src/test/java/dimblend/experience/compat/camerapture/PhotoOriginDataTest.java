package dimblend.experience.compat.camerapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * NBT round-trip of the {@code dimblend_photo_origin} compound. The compound itself is plain data,
 * so the round-trip is testable without an ItemStack (whose items need a bootstrapped registry).
 */
class PhotoOriginDataTest {
    private static final ResourceLocation ROTATING =
            ResourceLocation.fromNamespaceAndPath("dimblend", "rotating");

    @Test
    void theCompoundRoundTripsDimensionAndPosition() {
        PhotoOriginData.Origin origin =
                new PhotoOriginData.Origin(ROTATING, -1234.5, 78.25, 9876.0);

        CompoundTag encoded = PhotoOriginData.encode(origin);
        PhotoOriginData.Origin decoded = PhotoOriginData.decode(encoded);

        assertEquals(origin, decoded);
        assertEquals("dimblend:rotating", encoded.getString("dimension"));
        assertEquals(-1234.5, encoded.getDouble("x"));
        assertEquals(78.25, encoded.getDouble("y"));
        assertEquals(9876.0, encoded.getDouble("z"));
    }

    @Test
    void theStoredKeyIsTheDocumentedOne() {
        assertEquals("dimblend_photo_origin", PhotoOriginData.KEY);
    }

    @Test
    void aCompoundWithoutEveryFieldIsIgnored() {
        CompoundTag missingZ = PhotoOriginData.encode(
                new PhotoOriginData.Origin(ROTATING, 1.0, 2.0, 3.0));
        missingZ.remove("z");
        assertNull(PhotoOriginData.decode(missingZ));

        CompoundTag wrongType = PhotoOriginData.encode(
                new PhotoOriginData.Origin(ROTATING, 1.0, 2.0, 3.0));
        wrongType.putString("x", "not a double");
        assertNull(PhotoOriginData.decode(wrongType));
    }

    @Test
    void anUnparseableDimensionIsIgnored() {
        CompoundTag tag = PhotoOriginData.encode(
                new PhotoOriginData.Origin(ROTATING, 1.0, 2.0, 3.0));
        tag.putString("dimension", "NOT A RESOURCE LOCATION");

        assertNull(PhotoOriginData.decode(tag));
    }

    @Test
    void anOriginNeedsAFinitePosition() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new PhotoOriginData.Origin(ROTATING, Double.NaN, 0.0, 0.0));
    }
}
