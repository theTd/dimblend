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

    /** Announcement volume of the chase brake car coupling (I21). */
    private static final float NORMAL_VOLUME = 0.5F;
    /** Ender brake announcement volume; slightly louder than the ordinary one (I22). */
    private static final float ENDER_VOLUME = 0.65F;

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
     * One ordinary brake application heard by every player on the server (I21).
     *
     * <p>User decision of 2026-11-18: "whole map" is literally every online player in every
     * dimension ({@code existing_low_pitch_all_dims}), not only the players of the level the car
     * coupled in. {@code level.playSound} only reaches clients within the sound's linear range
     * (about 16 blocks), so this sends the packet to each player at their own position with a
     * shared seed: the volume does not fall off with distance and everyone hears the same
     * one-shot.</p>
     */
    @Override
    public void playGlobalNormalBrakeSound(ServerLevel level) {
        broadcast(level, NORMAL_VOLUME, TrainSpeedGateMath.NORMAL_BRAKE_PITCH);
    }

    /**
     * I22: the ender brake announcement, played exactly once per newly latched consist
     * ({@link TrainDeadmanBrake#latchConsist} on the {@code !next.equals(previous)} edge).
     *
     * <p>Deliberately <b>not</b> gated on speed: the train may already be stopped by the latch,
     * yet the debuff must still be audible. Uses the ordinary {@code train_brake} event at the
     * emergency pitch {@link TrainSpeedGateMath#EMERGENCY_BRAKE_PITCH} (0.5), at a slightly
     * higher volume than an ordinary application so the latch announcement is distinguishable
     * from the bogey's own edge sound.</p>
     */
    public static void playGlobalEnderBrakeSound(ServerLevel level) {
        broadcast(level, ENDER_VOLUME, TrainSpeedGateMath.EMERGENCY_BRAKE_PITCH);
    }

    /**
     * One {@code train_brake} one-shot for the whole server, each listener's own position as the
     * source and a shared seed; config / missing sound gate applies.
     */
    private static void broadcast(ServerLevel level, float volume, float pitch) {
        if (!Config.TRAIN_SOUNDS.get()) {
            return;
        }
        if (!ModSounds.TRAIN_BRAKE.isBound()) {
            return;
        }
        long seed = level.getRandom().nextLong();
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.connection == null) {
                continue;
            }
            player.connection.send(new ClientboundSoundPacket(ModSounds.TRAIN_BRAKE, SoundSource.BLOCKS,
                    player.getX(), player.getY(), player.getZ(), volume, pitch, seed));
        }
    }

    private TrainBrakeServiceImpl() {
    }
}
