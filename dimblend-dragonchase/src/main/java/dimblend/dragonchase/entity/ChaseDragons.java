package dimblend.dragonchase.entity;

import dimblend.dragonchase.DimBlendDragonChase;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * CONTRACT (Phase 0; bodies implemented by W3). Spawning and lookup of dragons for the director.
 */
public final class ChaseDragons {
    private static volatile ChaseDragonListener listener = ChaseDragonListener.NONE;

    /** Spawns a prologue dragon (§3.2) at {@code pos} in {@link ChaseDragonHandle.Mode#PROLOGUE_ORBIT}; no boss bar. */
    public static ChaseDragonHandle spawnPrologue(ServerLevel level, Vec3 pos) {
        ChaseEntityTickets.keepTicking(level, pos);
        ChaseDragon dragon = ChaseDragon.createPrologue(level, pos);
        if (!level.addFreshEntity(dragon)) {
            DimBlendDragonChase.LOGGER.warn("Prologue dragon was rejected by {} at {}", level.dimension().location(), pos);
        }
        return dragon;
    }

    /**
     * Spawns a chase dragon (§3.4) at {@code pos} in {@link ChaseDragonHandle.Mode#CHASE_FOLLOW}
     * with {@code maxHealth} (already computed by the director) at full health.
     */
    public static ChaseDragonHandle spawnChase(ServerLevel level, Vec3 pos, float maxHealth) {
        ChaseEntityTickets.keepTicking(level, pos);
        ChaseDragon dragon = ChaseDragon.createChase(level, pos, maxHealth);
        if (!level.addFreshEntity(dragon)) {
            DimBlendDragonChase.LOGGER.warn("Chase dragon was rejected by {} at {}", level.dimension().location(), pos);
        }
        return dragon;
    }

    /** The loaded dragon with this UUID, or null (not loaded, removed, or not a Dragon Chase dragon). */
    @Nullable
    public static ChaseDragonHandle find(ServerLevel level, UUID uuid) {
        Entity entity = level.getEntity(uuid);
        if (entity == null || entity.isRemoved() || !(entity instanceof ChaseDragonHandle handle)) {
            return null;
        }
        return handle;
    }

    public static void setListener(ChaseDragonListener value) {
        listener = value == null ? ChaseDragonListener.NONE : value;
    }

    public static ChaseDragonListener listener() {
        return listener;
    }

    private ChaseDragons() {
    }
}
