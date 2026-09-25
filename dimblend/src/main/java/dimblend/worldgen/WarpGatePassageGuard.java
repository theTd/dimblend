package dimblend.worldgen;

import dimblend.DimBlendRegistries;
import dimblend.block.WarpGateBlock;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Clip/teleport safety net for warp gates (see {@link RegionBoundaryWall}).
 *
 * Walking players are stopped by {@link WarpGateBlock#getCollisionShape}; this guard
 * still pushes unauthorized entities back out if they spawn, teleport, or otherwise
 * clip into a gate slab. Passage policy is {@link WarpGateBlock#mayPass} so collision
 * and this revert cannot disagree (Create carriages / Sable riders pass, other
 * entities do not).
 */
public final class WarpGatePassageGuard {
    private WarpGatePassageGuard() {
    }

    public static void onEntityTickPost(EntityTickEvent.Post event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (level.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        AABB box = entity.getBoundingBox();
        // Cheap rejects before touching the generator: the gate only exists inside the
        // corridor vault cross-section.
        if (box.maxY < OakTrackCorridor.TRACK_Y + OakTrackCorridor.VAULT_FLOOR_DY
                || box.minY > OakTrackCorridor.TRACK_Y + OakTrackCorridor.VAULT_APEX_DY + 1) {
            return;
        }
        if (box.maxZ < OakTrackCorridor.CORRIDOR_Z - OakTrackCorridor.VAULT_RADIUS
                || box.minZ > OakTrackCorridor.CORRIDOR_Z + OakTrackCorridor.VAULT_RADIUS + 1) {
            return;
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        if (!(generator instanceof RotatingChunkGenerator rotating)) {
            return;
        }
        int slabX = RegionBoundaryWall.overlappedBoundaryColumn(
                rotating, box.minX, box.maxX, box.minY, box.maxY, box.minZ, box.maxZ);
        if (slabX < 0 || WarpGateBlock.mayPass(entity)) {
            return;
        }
        revert(entity, slabX);
    }

    /**
     * Push the entity out of the gate slab to the nearest side and kill its momentum.
     * Snapping to the previous-tick position instead would deadlock an entity that
     * spawned or teleported inside the slab (xo already overlapping, zero displacement,
     * intersecting forever), so displacement is always computed relative to the slab.
     */
    private static void revert(Entity entity, int slabX) {
        entity.setDeltaMovement(Vec3.ZERO);
        double half = entity.getBbWidth() / 2.0;
        double targetX = entity.getX() < slabX + 0.5
                ? slabX - half - 0.01
                : slabX + 1.0 + half + 0.01;
        entity.moveTo(targetX, entity.getY(), entity.getZ(), entity.getYRot(), entity.getXRot());
        entity.xOld = entity.getX();
        entity.yOld = entity.getY();
        entity.zOld = entity.getZ();
    }
}
