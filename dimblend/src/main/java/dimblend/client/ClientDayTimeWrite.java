package dimblend.client;

import dimblend.DimBlendRegistries;
import dimblend.mixin.ClientLevelAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;

/**
 * Raw client day-time field write. {@link ClientLevel#setDayTime} is
 * NeoForge-patched to force-enable doDaylightCycle; the lock/unlock path
 * must not do that. Lives next to the holder rather than inside it so
 * {@link ClientTimeLock} stays a mode pin.
 */
public final class ClientDayTimeWrite {
    private ClientDayTimeWrite() {
    }

    public static void restore(Level level, long dayTime) {
        if (!(level instanceof ClientLevel client)
                || client.dimension() != DimBlendRegistries.ROTATING_LEVEL) {
            return;
        }
        ((ClientLevelAccessor) client).dimblend$getClientLevelData().setDayTime(dayTime);
    }
}
