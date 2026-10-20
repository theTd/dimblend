package dimblend.experience.client.headlight;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.phys.Vec3;

/** World-space frame input; {@code owner} is null for lamps placed in the world. */
public record HeadlightBeam(Vec3 lens, Vec3 direction, ClientSubLevel owner) {
    public HeadlightBeam(Vec3 lens, Vec3 direction) {
        this(lens, direction, null);
    }
}
