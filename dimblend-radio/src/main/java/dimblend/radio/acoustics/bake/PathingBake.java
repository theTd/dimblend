package dimblend.radio.acoustics.bake;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A radio's baked pathing: the serialized probe batch and what it was baked from. The probes are
 * relative to the radio block's lower corner (the frame the runtime traces in).
 *
 * @param sectionKeys every terrain section the bake mesh spans ({@code SectionPos.asLong})
 * @param sectionStates each section's state when baked ({@code ReflectionGeometry.terrainSection})
 * @param batch the probe batch as {@code iplProbeBatchSave} wrote it
 */
public record PathingBake(BlockPos radio, long[] sectionKeys, long[] sectionStates, int probeCount, int cellSize,
        byte[] batch) {
    public PathingBake {
        radio = radio.immutable();
        if (sectionKeys.length != sectionStates.length) throw new IllegalArgumentException("One state per section");
    }

    /** The frame the probes are stored in. */
    public Vec3 origin() { return Vec3.atLowerCornerOf(radio); }
}
