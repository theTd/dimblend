package dimblend.experience.compat.camerapture;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/**
 * The capture position stored on a CameraCapture picture.
 *
 * <p>The server records the player's position when the capture request arrives, under the picture
 * UUID it reserved, and {@code PictureItem.create} attaches the compound to the finished stack. The
 * data lives in {@link DataComponents#CUSTOM_DATA} under {@value #KEY}, so vanilla syncs it to
 * clients with the stack and CameraCapture's own cloning recipe copies it with the components.
 *
 * <p>The compound is {@code {dimension: "<id>", x: double, y: double, z: double}}. {@code y} is the
 * player's eye height at capture time.
 */
public final class PhotoOriginData {
    /** NBT compound key inside {@link DataComponents#CUSTOM_DATA}. */
    public static final String KEY = "dimblend_photo_origin";

    private static final String DIMENSION = "dimension";
    private static final String X = "x";
    private static final String Y = "y";
    private static final String Z = "z";

    /** Uploads that never complete would otherwise pin an entry per capture forever. */
    private static final int MAX_PENDING = 1024;

    private static final Map<UUID, Origin> PENDING = new ConcurrentHashMap<>();

    /** A dimension plus a world position; {@code y} is the eye height at capture time. */
    public record Origin(ResourceLocation dimension, double x, double y, double z) {
        public Origin {
            Objects.requireNonNull(dimension, "dimension");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Non-finite photo origin");
            }
        }
    }

    /** Remembers the origin of the picture currently being uploaded under {@code pictureId}. */
    public static void record(UUID pictureId, Origin origin) {
        Objects.requireNonNull(pictureId, "pictureId");
        Objects.requireNonNull(origin, "origin");
        if (PENDING.size() >= MAX_PENDING) {
            PENDING.clear();
        }
        PENDING.put(pictureId, origin);
    }

    /**
     * Writes the recorded origin of {@code pictureId} onto a freshly created picture, if any, and
     * forgets it. Called from the picture upload callback on the server.
     */
    public static ItemStack attach(ItemStack stack, UUID pictureId) {
        Origin origin = PENDING.remove(pictureId);
        if (origin != null) {
            write(stack, origin);
        }
        return stack;
    }

    /** Writes {@code origin} into the stack's custom data, replacing an older value. */
    public static void write(ItemStack stack, Origin origin) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, custom -> custom.put(KEY, encode(origin)));
    }

    /** The picture's origin, or {@code null} for a picture without one (e.g. an album upload). */
    public static @Nullable Origin read(ItemStack stack) {
        CustomData custom = stack.get(DataComponents.CUSTOM_DATA);
        if (custom == null) {
            return null;
        }
        CompoundTag tag = custom.copyTag();
        if (!tag.contains(KEY, Tag.TAG_COMPOUND)) {
            return null;
        }
        return decode(tag.getCompound(KEY));
    }

    /** Encodes the origin compound exactly as stored under {@link #KEY}. */
    public static CompoundTag encode(Origin origin) {
        CompoundTag tag = new CompoundTag();
        tag.putString(DIMENSION, origin.dimension().toString());
        tag.putDouble(X, origin.x());
        tag.putDouble(Y, origin.y());
        tag.putDouble(Z, origin.z());
        return tag;
    }

    /** Decodes a stored origin compound; a malformed one is ignored rather than guessed. */
    public static @Nullable Origin decode(CompoundTag tag) {
        if (!tag.contains(DIMENSION, Tag.TAG_STRING)
                || !tag.contains(X, Tag.TAG_DOUBLE)
                || !tag.contains(Y, Tag.TAG_DOUBLE)
                || !tag.contains(Z, Tag.TAG_DOUBLE)) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString(DIMENSION));
        if (dimension == null) {
            return null;
        }
        return new Origin(dimension, tag.getDouble(X), tag.getDouble(Y), tag.getDouble(Z));
    }

    private PhotoOriginData() {
    }
}
