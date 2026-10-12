package dimblend.experience.tuning.perf;

import dimblend.experience.DimBlend;
import java.io.IOException;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server handler for checklist requests; same permission gate as the tuning panel (level 2). */
public final class PerfCheckService {
    public static void handle(ServerPlayer player, PerfCheckRequestPayload request) {
        if (!player.hasPermissions(2)) {
            PacketDistributor.sendToPlayer(player, new PerfCheckReportPayload(List.of(), "no_permission"));
            return;
        }
        String message = "";
        if (!request.fix().isEmpty()) {
            PerfCheck check = PerfCheck.byId(request.fix());
            try {
                message = check != null && ServerPerfChecks.fix(player.server, check) ? "fixed" : "fix_unavailable";
            } catch (IOException | RuntimeException failure) {
                DimBlend.LOGGER.error("Performance checklist fix {} failed", request.fix(), failure);
                message = "fix_failed";
            }
        }
        PacketDistributor.sendToPlayer(player,
                new PerfCheckReportPayload(ServerPerfChecks.collect(player.server), message));
    }

    private PerfCheckService() {
    }
}
