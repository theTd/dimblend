package dimblend.experience.compat.create;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Client crosshair raycast extension for paired Create sliding doors whose centered 2-wide
 * geometry overhangs {@code [-0.5, +1.5]} (closed) or up to {@code [-1.3125, +2.3125]} (open)
 * outside the door's single logical {@link BlockPos}, in both the main {@link ClientLevel}
 * and (when Sable is loaded, via {@link SlidingDoorSableOverhangPick}) Sable client sublevels.
 */
public final class SlidingDoorOverhangPick {
    static final double SEARCH_HORIZONTAL_RADIUS = 1.9;
    static final double MAX_HORIZONTAL_SEGMENT_DIST_SQ = 4.0;

    private SlidingDoorOverhangPick() {
    }

    public static void updateCrosshairPick(Minecraft mc, float partialTick) {
        ClientLevel level = mc.level;
        Entity camera = mc.getCameraEntity();
        if (level == null || mc.player == null || camera == null) {
            return;
        }

        boolean sableLoaded = ModList.get().isLoaded("sable");
        Vec3 worldStart = sableLoaded
                ? SlidingDoorSableOverhangPick.eyePosition(camera, partialTick)
                : camera.getEyePosition(partialTick);
        Vec3 viewVec = camera.getViewVector(partialTick);
        double range = mc.player.blockInteractionRange();
        Vec3 worldEnd = worldStart.add(viewVec.x * range, viewVec.y * range, viewVec.z * range);

        double maxWorldDistSq = range * range;
        HitResult current = mc.hitResult;
        if (current != null && current.getType() != HitResult.Type.MISS) {
            if (current instanceof EntityHitResult entityHit) {
                maxWorldDistSq = Math.min(maxWorldDistSq, entityHit.getLocation().distanceToSqr(worldStart));
            } else if (current instanceof BlockHitResult blockHit) {
                BlockPos hitPos = blockHit.getBlockPos();
                boolean existingIsPairedDoor = isLoadedPairedDoor(level, hitPos);
                if (!existingIsPairedDoor) {
                    Vec3 hitWorld = sableLoaded
                            ? SlidingDoorSableOverhangPick.plotToWorld(level, blockHit.getLocation(), partialTick)
                            : blockHit.getLocation();
                    maxWorldDistSq = Math.min(maxWorldDistSq, hitWorld.distanceToSqr(worldStart));
                } else {
                    // Create's BigOutlines.pick() clamps offsets to exactly +-1.0, which the server's
                    // strict "< 1.0000001" test only just survives. Normalise any earlier paired-door
                    // hit to our own +-0.999 bound so both paths agree.
                    Vec3 clamped = SlidingDoorPairing.clampHitLocationToBlockCenter(blockHit.getLocation(), hitPos);
                    mc.hitResult = new BlockHitResult(clamped, blockHit.getDirection(), hitPos, blockHit.isInside());
                }
            }
        }

        BestHit best = new BestHit(maxWorldDistSq);
        clipPairedDoorsInMainLevel(level, worldStart, worldEnd, best);
        if (sableLoaded) {
            SlidingDoorSableOverhangPick.clipPairedDoorsInSubLevels(level, worldStart, worldEnd, partialTick, best);
        }

        if (best.result != null) {
            mc.hitResult = best.result;
        }
    }

    private static boolean isLoadedPairedDoor(ClientLevel level, BlockPos pos) {
        int cx = SectionPos.blockToSectionCoord(pos.getX());
        int cz = SectionPos.blockToSectionCoord(pos.getZ());
        if (!level.hasChunk(cx, cz)) {
            return false;
        }
        return SlidingDoorPairing.isPaired(level.getBlockState(pos));
    }

    private static void clipPairedDoorsInMainLevel(
            ClientLevel level, Vec3 worldStart, Vec3 worldEnd, BestHit best) {
        int minX = Mth.floor(Math.min(worldStart.x, worldEnd.x) - SEARCH_HORIZONTAL_RADIUS);
        int maxX = Mth.floor(Math.max(worldStart.x, worldEnd.x) + SEARCH_HORIZONTAL_RADIUS);
        int minY = Mth.floor(Math.min(worldStart.y, worldEnd.y) - 0.5);
        int maxY = Mth.floor(Math.max(worldStart.y, worldEnd.y) + 0.5);
        int minZ = Mth.floor(Math.min(worldStart.z, worldEnd.z) - SEARCH_HORIZONTAL_RADIUS);
        int maxZ = Mth.floor(Math.max(worldStart.z, worldEnd.z) + SEARCH_HORIZONTAL_RADIUS);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int bx = minX; bx <= maxX; bx++) {
            int cx = SectionPos.blockToSectionCoord(bx);
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (distSqPointToSegment2d(
                        bx + 0.5, bz + 0.5,
                        worldStart.x, worldStart.z,
                        worldEnd.x, worldEnd.z) > MAX_HORIZONTAL_SEGMENT_DIST_SQ) {
                    continue;
                }
                int cz = SectionPos.blockToSectionCoord(bz);
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                for (int by = minY; by <= maxY; by++) {
                    cursor.set(bx, by, bz);
                    BlockState state = level.getBlockState(cursor);
                    if (!SlidingDoorPairing.isPaired(state)) {
                        continue;
                    }
                    BlockPos doorPos = cursor.immutable();
                    BlockHitResult hit = state.getShape(level, doorPos).clip(worldStart, worldEnd, doorPos);
                    if (hit == null || hit.getType() == HitResult.Type.MISS) {
                        continue;
                    }
                    double distSq = hit.getLocation().distanceToSqr(worldStart);
                    if (distSq < best.bestDistSq) {
                        Vec3 clamped = SlidingDoorPairing.clampHitLocationToBlockCenter(hit.getLocation(), doorPos);
                        best.bestDistSq = distSq;
                        best.result = new BlockHitResult(clamped, hit.getDirection(), doorPos, hit.isInside());
                    }
                }
            }
        }
    }

    static double distSqPointToSegment2d(
            double px, double pz, double ax, double az, double bx, double bz) {
        double dx = bx - ax;
        double dz = bz - az;
        double lenSq = dx * dx + dz * dz;
        if (lenSq <= 1.0e-12) {
            double ex = px - ax;
            double ez = pz - az;
            return ex * ex + ez * ez;
        }
        double t = Mth.clamp(((px - ax) * dx + (pz - az) * dz) / lenSq, 0.0, 1.0);
        double projX = ax + t * dx;
        double projZ = az + t * dz;
        double ex = px - projX;
        double ez = pz - projZ;
        return ex * ex + ez * ez;
    }

    static final class BestHit {
        double bestDistSq;
        BlockHitResult result;

        BestHit(double maxWorldDistSq) {
            this.bestDistSq = maxWorldDistSq;
        }
    }
}
