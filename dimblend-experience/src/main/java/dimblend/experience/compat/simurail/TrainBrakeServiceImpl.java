package dimblend.experience.compat.simurail;

import dimblend.api.TrainBrakeService;
import dimblend.experience.Config;
import dimblend.experience.ModSounds;
import java.util.UUID;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;

/**
 * The ender brake of the Dragon Chase (requirements.md §3.4): forced brake + overloaded powertrain
 * + purple particles + whole-train brake announcement, latched on a whole consist.
 *
 * <p>This class is only the service adapter. The whole effect is the unattended brake's latch
 * ({@link TrainDeadmanBrake}), so the two features cannot drift apart: the same flag forces
 * {@code getBrakeStrength() == 1.0}, the same table forces the network impact, the same
 * {@code DeadmanLatchPayload} drives the client particles, and the brake sound pitch follows the
 * latch ({@code TrainSpeedGateMath.brakePitch}). Release is the unattended brake's own rule: any
 * raw redstone level change on the consist.
 *
 * <p>Registered at mod construction only when Simurail is present; the service then lives for the
 * process, and a server that never sees a chase never calls it.
 */
public final class TrainBrakeServiceImpl implements TrainBrakeService {

    /** Called next to {@code TrainDeadmanBrake.register()} behind the same Simurail gate. */
    public static void register() {
        TrainBrakeService.register(new TrainBrakeServiceImpl());
    }

    @Override
    public boolean latch(ServerLevel level, UUID carriage) {
        return TrainDeadmanBrake.latchConsist(level, carriage);
    }

    @Override
    public boolean isLatched(ServerLevel level, UUID carriage) {
        return TrainDeadmanBrake.isCarriageLatched(level, carriage);
    }

    /**
     * One ordinary brake application heard by every player in the level.
     *
     * <p>{@code level.playSound} only reaches clients within the sound's linear range (about 16
     * blocks), so this sends the packet to each player at their own position with a shared seed:
     * the volume does not fall off with distance and everyone hears the same one-shot.
     */
    @Override
    public void playGlobalNormalBrakeSound(ServerLevel level) {
        if (!Config.TRAIN_SOUNDS.get()) {
            return;
        }
        if (!ModSounds.TRAIN_BRAKE.isBound()) {
            return;
        }
        long seed = level.getRandom().nextLong();
        for (ServerPlayer player : level.players()) {
            if (player.connection == null) {
                continue;
            }
            player.connection.send(new ClientboundSoundPacket(ModSounds.TRAIN_BRAKE, SoundSource.BLOCKS,
                    player.getX(), player.getY(), player.getZ(), 0.5F,
                    TrainSpeedGateMath.NORMAL_BRAKE_PITCH, seed));
        }
    }

    private TrainBrakeServiceImpl() {
    }
}
