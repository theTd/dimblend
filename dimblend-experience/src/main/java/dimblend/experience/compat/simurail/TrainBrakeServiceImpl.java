package dimblend.experience.compat.simurail;

import dimblend.api.TrainBrakeService;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

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

    private TrainBrakeServiceImpl() {
    }
}
