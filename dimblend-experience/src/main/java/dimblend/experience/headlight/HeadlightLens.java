package dimblend.experience.headlight;

import net.minecraft.world.phys.Vec3;

/** A beam frame with no owner: lamps enumerated on the server, and the frame unit tests build. */
public record HeadlightLens(Vec3 lens, Vec3 direction) implements HeadlightRay {
    public HeadlightLens(Vec3 lens, Vec3 direction) {
        this.lens = lens;
        this.direction = direction.normalize();
    }
}
