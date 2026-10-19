package dimblend.experience.client.headlight;

import net.minecraft.world.phys.Vec3;

/** World-space frame input, independent of discovery and the eventual occlusion/volume passes. */
public record HeadlightBeam(Vec3 lens, Vec3 direction) {
}
