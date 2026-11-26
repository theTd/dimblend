package dimblend.experience.compat.simurail;

import dimblend.api.TrainBrakeService;
import dimblend.experience.Config;
import dimblend.experience.ModSounds;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

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
    private static final float NORMAL_VOLUME = 0.65F;
    /** Ender brake announcement volume; louder still so the chase BGM cannot bury it (I22). */
    private static final float ENDER_VOLUME = 0.9F;

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
     * (about 16 blocks), so this sends one {@link GlobalTrainBrakeSoundPayload} to each player and
     * lets their own client build a non-positional instance: the volume does not fall off with
     * distance and everyone hears the same one-shot.</p>
     */
    @Override
    public void playGlobalNormalBrakeSound(ServerLevel level) {
        broadcast(level, NORMAL_VOLUME, TrainSpeedGateMath.NORMAL_BRAKE_PITCH);
    }

    /**
     * I22: the ender brake announcement, played exactly once per newly latched consist
     * ({@link TrainDeadmanBrake#latchConsist}: the shared latch grows <b>or</b> the service's own
     * ender set grows — the latter matters when the unattended brake already latched that consist,
     * where the shared latch alone would look unchanged).
     *
     * <p>Deliberately <b>not</b> gated on speed: the train may already be stopped by the latch,
     * yet the debuff must still be audible. Uses the ordinary {@code train_brake} event at the
     * emergency pitch {@link TrainSpeedGateMath#EMERGENCY_BRAKE_PITCH} (0.5), louder than the
     * ordinary application (2026-11-27: 0.9 vs 0.65) so neither the bogey's own edge sound nor
     * the chase BGM can bury the latch announcement.</p>
     */
    public static void playGlobalEnderBrakeSound(ServerLevel level) {
        broadcast(level, ENDER_VOLUME, TrainSpeedGateMath.EMERGENCY_BRAKE_PITCH);
    }

    /**
     * One {@code train_brake} one-shot for the whole server, with a 0.5-pitch ender announcement
     * long enough (10 s) that a position-bound packet cannot survive the chase.
     *
     * <p><b>Why a custom payload instead of {@code ClientboundSoundPacket}</b> (2026-11-27
     * Slice 10): the vanilla packet becomes a fixed world position + {@code Attenuation.LINEAR}
     * instance (16 blocks for {@code train_brake}, {@code relative = false}). A chasing train
     * runs at 15–32 m/s, so a listener leaves that point by more than 16 blocks within half a
     * second and the whole announcement fades to silence right after its first note; worse, a
     * server-side {@code player.getX()/Y()/Z()} for a player on a Sable seat is plot space
     * ({@code ~1.6×10^7}), so the source lands in the plot region entirely. Here each client
     * builds a relative, {@code Attenuation.NONE} instance at the origin
     * ({@code TrainBrakeSoundClient#playGlobal}): no distance falloff, no dependence on any
     * world coordinate, and no Sable sound delegate wrap.</p>
     *
     * <p>Config / missing sound gate applies. {@code player.connection == null} is skipped for
     * safety exactly as before.</p>
     */
    private static void broadcast(ServerLevel level, float volume, float pitch) {
        if (!Config.TRAIN_SOUNDS.get()) {
            return;
        }
        if (!ModSounds.TRAIN_BRAKE.isBound()) {
            return;
        }
        long seed = level.getRandom().nextLong();
        GlobalTrainBrakeSoundPayload payload = new GlobalTrainBrakeSoundPayload(volume, pitch, seed);
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (player.connection == null) {
                continue;
            }
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private TrainBrakeServiceImpl() {
    }
}
