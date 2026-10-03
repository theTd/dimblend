package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * What a reflection scene is built from ({@link AcousticSnapshot} over frozen palettes). Terrain
 * is addressed per section, so a cached terrain mesh can be checked against later snapshots;
 * moving structures carry a content key and their current pose, so a mesh built once in their own
 * frame can follow them.
 */
public interface ReflectionGeometry {
    /** {@link #terrainSection} outside what this snapshot captured: unknown here, not changed. */
    long UNCAPTURED = -1;
    /** {@link #terrainSection} of a captured column whose chunk was not loaded. */
    long UNLOADED = -2;
    /** {@link #terrainSection} of a captured section holding only air. */
    long AIR = -3;

    /** Block fingerprint (non-negative) of terrain section {@code key}, or one of the sentinels above. */
    long terrainSection(long key);

    /** Lowest section row of the world. */
    int minSection();

    /** Exclusive upper bound of the world's section rows. */
    int maxSection();

    /** Every section {@code box} spans within the world's rows ({@code SectionPos.asLong}), by x, then z, then y. */
    default long[] sectionKeys(AABB box) {
        int minX = (int) Math.floor(box.minX) >> 4, maxX = (int) Math.floor(box.maxX) >> 4;
        int minY = Math.max(minSection(), (int) Math.floor(box.minY) >> 4);
        int maxY = Math.min(maxSection() - 1, (int) Math.floor(box.maxY) >> 4);
        int minZ = (int) Math.floor(box.minZ) >> 4, maxZ = (int) Math.floor(box.maxZ) >> 4;
        int count = maxY < minY ? 0 : (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        long[] keys = new long[count];
        int i = 0;
        for (int x = minX; x <= maxX && count > 0; x++) for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) {
            keys[i++] = net.minecraft.core.SectionPos.asLong(x, y, z);
        }
        return keys;
    }

    /** {@link #terrainSection} of each key. */
    default long[] terrainSections(long[] keys) {
        long[] states = new long[keys.length];
        for (int i = 0; i < keys.length; i++) states[i] = terrainSection(keys[i]);
        return states;
    }

    /** Terrain surfaces inside {@code bounds}, relative to {@code origin}. */
    AcousticMesh.Data terrainMesh(AABB bounds, Vec3 origin, AcousticMesh.Workspace workspace);

    /** Cells meshed as air wherever they are: the radios the scene is simulated for. */
    Set<BlockPos> emitters();

    /** Moving structures in the scene. */
    List<? extends Body> bodies();

    /** A moving structure. */
    interface Body {
        UUID id();

        /** Changes whenever the structure's captured blocks (or the excluded emitter) change. */
        long contentKey();

        /** Where the structure is now. */
        Pose3dc pose();

        /** Surfaces in the structure's own frame; {@link AcousticMesh.Data#origin()} is in that frame too. */
        AcousticMesh.Data localMesh(AcousticMesh.Workspace workspace);
    }
}
