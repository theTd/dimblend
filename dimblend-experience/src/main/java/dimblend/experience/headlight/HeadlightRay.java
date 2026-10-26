package dimblend.experience.headlight;

import net.minecraft.world.phys.Vec3;

/**
 * A beam frame in world space: where the lens sits and which way it points. Implemented by the
 * client's {@code HeadlightBeam} (which also carries the owning sub-level) and by {@link HeadlightLens},
 * the ownerless frame the server tracker and the unit tests use, so both sides run the same photometry.
 */
public interface HeadlightRay {
    Vec3 lens();

    Vec3 direction();
}
