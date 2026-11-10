package dimblend.experience.chassis;

import dimblend.worldgen.CorridorTrackProfile;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端可用的 lane → 轨道材质映射，口径与 {@link CorridorTrackProfile#forDelegate} 一致：
 * surface/underground 由 SlicedOverworld 决定，twilight/voidscape 走专门分支，其余已知
 * lane 按名字匹配，未知 modded lane 落到 {@link CorridorTrackProfile#MOD}。
 *
 * <p>"unknown" 或空串表示客户端还没收到 {@code BandLaneSync} 的当前 lane，返回 null；
 * 调用方必须放行点击，不能猜一个 MOD 轨道去取消。</p>
 */
public final class ChassisLaneRule {
    public static ResourceLocation trackId(String lane) {
        if (lane == null || lane.isEmpty() || "unknown".equals(lane)) {
            return null;
        }
        CorridorTrackProfile profile = switch (lane) {
            case "surface" -> CorridorTrackProfile.SURFACE;
            case "underground" -> CorridorTrackProfile.UNDERGROUND;
            case "nether" -> CorridorTrackProfile.NETHER;
            case "end" -> CorridorTrackProfile.END;
            case "twilight" -> CorridorTrackProfile.TWILIGHT;
            case "starlight" -> CorridorTrackProfile.STARLIGHT;
            case "deeperdarker" -> CorridorTrackProfile.OTHERSIDE;
            case "aether" -> CorridorTrackProfile.AETHER;
            case "voidscape" -> CorridorTrackProfile.VOIDSCAPE;
            default -> CorridorTrackProfile.MOD;
        };
        return profile.trackId();
    }

    private ChassisLaneRule() {
    }
}
