package dimblend.worldgen;

import dev.ryanhcode.sable.companion.SableCompanion;
import dimblend.DimBlendRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * Runtime passage control for warp gates (see {@link RegionBoundaryWall}).
 *
 * The gate block is collision-free; instead, every entity whose bounding box enters a
 * gate region is pushed back out to the nearest side of the boundary slab unless it may
 * pass:
 *
 * <ul>
 *   <li>players in creative or spectator mode — always;</li>
 *   <li>other players — only while standing on (or riding a vehicle inside) a Sable
 *       physics structure, detected via the JiJ'd sable-companion, which is a safe no-op
 *       returning null when Sable is not installed;</li>
 *   <li>everything else (mobs, minecarts, trains, items, projectiles) — never.</li>
 * </ul>
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
        if (slabX < 0 || mayPass(entity)) {
            return;
        }
        revert(entity, slabX);
    }

    private static boolean mayPass(Entity entity) {
        if (entity instanceof Player player) {
            if (player.isSpectator() || player.isCreative()) {
                return true;
            }
            return SableCompanion.INSTANCE.getTrackingOrVehicleSubLevel(player) != null;
        }
        return false;
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
