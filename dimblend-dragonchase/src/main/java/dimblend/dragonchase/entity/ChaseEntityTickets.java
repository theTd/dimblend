package dimblend.dragonchase.entity;

import java.util.Comparator;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Expiring {@code ENTITY_TICKING} region tickets for {@link ChaseDragon} and {@link ChaseFireball}.
 *
 * <p>Sable's {@code PhysicsChunkTicketManager#inhabitChunk} only places a radius-0
 * {@code ENTITY_TICKING} ticket on the nest sub-level's own 1-2 chunks. Because the chase gap is
 * 128-512+ blocks (8-32+ chunks, routinely beyond the server's {@code simulation-distance}), the
 * dragon (spawning 64 blocks behind the nest, swaying {@code z = +-32}, orbiting at {@code r =
 * 40..60}, or swooping toward the train) and its fireballs would otherwise leave
 * {@code DistanceManager#inEntityTickingRange} and stop ticking or unload to chunk NBT while the
 * nest keeps fleeing.
 *
 * <p>Radius 3 sets ticket level {@code 33 - 3 = 30}, keeping a 3x3 chunk window in
 * {@code ENTITY_TICKING} ({@code <= 31}) and a 7x7 ring in {@code FULL} ({@code <= 33}, satisfying
 * {@code ChunkMap#prepareEntityTickingChunk}'s 5x5 requirement asynchronously without blocking the
 * server thread). The 40-tick timeout cleans up automatically after departure or removal.
 */
public final class ChaseEntityTickets {
    public static final TicketType<ChunkPos> TICKET_TYPE =
            TicketType.create("dimblend_dragonchase", Comparator.comparingLong(ChunkPos::toLong), 40);
    private static final int ENTITY_TICKING_RADIUS = 3;

    public static void keepTicking(ServerLevel level, Vec3 pos) {
        ChunkPos chunk = new ChunkPos(
                SectionPos.blockToSectionCoord(pos.x),
                SectionPos.blockToSectionCoord(pos.z));
        level.getChunkSource().addRegionTicket(TICKET_TYPE, chunk, ENTITY_TICKING_RADIUS, chunk);
    }

    public static void keepTicking(ServerLevel level, Entity entity) {
        ChunkPos current = entity.chunkPosition();
        level.getChunkSource().addRegionTicket(TICKET_TYPE, current, ENTITY_TICKING_RADIUS, current);
        Vec3 vel = entity.getDeltaMovement();
        if (vel.horizontalDistanceSqr() > 1.0E-4D) {
            ChunkPos ahead = new ChunkPos(
                    SectionPos.blockToSectionCoord(entity.getX() + vel.x * 8.0D),
                    SectionPos.blockToSectionCoord(entity.getZ() + vel.z * 8.0D));
            if (!ahead.equals(current)) {
                level.getChunkSource().addRegionTicket(TICKET_TYPE, ahead, ENTITY_TICKING_RADIUS, ahead);
            }
        }
    }

    private ChaseEntityTickets() {
    }
}
