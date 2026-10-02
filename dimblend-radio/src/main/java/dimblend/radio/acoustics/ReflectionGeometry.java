package dimblend.radio.acoustics;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import java.util.List;
import java.util.UUID;
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

    /** Version of the rendered terrain geometry used over {@code bounds}; constant when none is used. */
    long renderGeometryVersion(AABB bounds);

    /** Terrain surfaces inside {@code bounds}, relative to {@code origin}. */
    AcousticMesh.Data terrainMesh(AABB bounds, Vec3 origin, AcousticMesh.Workspace workspace);

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
