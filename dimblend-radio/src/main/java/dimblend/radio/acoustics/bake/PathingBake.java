package dimblend.radio.acoustics.bake;

import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A radio's baked pathing: the serialized probe batch and what it was baked from. The probes are
 * relative to the radio block's lower corner (the frame the runtime traces in).
 *
 * @param sectionKeys every terrain section the bake mesh spans ({@code SectionPos.asLong})
 * @param sectionStates each section's state when baked ({@code ReflectionGeometry.terrainSection})
 * @param probes probe centres relative to {@link #origin()}, x y z per probe (the batch keeps no
 *     positions that can be read back)
 * @param batch the probe batch as {@code iplProbeBatchSave} wrote it
 */
public record PathingBake(BlockPos radio, long[] sectionKeys, long[] sectionStates, float[] probes, int cellSize,
        byte[] batch) {
    public PathingBake {
        radio = radio.immutable();
        if (sectionKeys.length != sectionStates.length) throw new IllegalArgumentException("One state per section");
        if (probes.length % 3 != 0) throw new IllegalArgumentException("Three coordinates per probe");
    }

    /** The frame the probes are stored in. */
    public Vec3 origin() { return Vec3.atLowerCornerOf(radio); }

    public int probeCount() { return probes.length / 3; }

    /** A probe centre in world coordinates. */
    public Vec3 probe(int index) {
        return origin().add(probes[index * 3], probes[index * 3 + 1], probes[index * 3 + 2]);
    }

    /** The probes inside any of these sections, or within {@code margin} blocks of one. */
    public BitSet probesNear(long[] sections, double margin) {
        BitSet near = new BitSet(probeCount());
        if (sections.length == 0) return near;
        AABB[] boxes = new AABB[sections.length];
        for (int i = 0; i < sections.length; i++) {
            long key = sections[i];
            boxes[i] = new AABB(SectionPos.sectionToBlockCoord(SectionPos.x(key)), SectionPos.sectionToBlockCoord(SectionPos.y(key)),
                    SectionPos.sectionToBlockCoord(SectionPos.z(key)), SectionPos.sectionToBlockCoord(SectionPos.x(key) + 1),
                    SectionPos.sectionToBlockCoord(SectionPos.y(key) + 1), SectionPos.sectionToBlockCoord(SectionPos.z(key) + 1))
                    .inflate(margin);
        }
        for (int i = 0; i < probeCount(); i++) {
            Vec3 probe = probe(i);
            for (AABB box : boxes) {
                if (box.contains(probe)) {
                    near.set(i);
                    break;
                }
            }
        }
        return near;
    }
}
