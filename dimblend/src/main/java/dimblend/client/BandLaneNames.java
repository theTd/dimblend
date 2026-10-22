package dimblend.client;

/**
 * Display names for band lane keys ({@code BandLayout.laneName} values) on the
 * band progress HUD. Unrecognized mod lanes fall back to 异界; the "unknown"
 * placeholder (payload not yet arrived) and the empty next-lane sentinel
 * (random draw) both display as 未知.
 */
public final class BandLaneNames {
    private BandLaneNames() {
    }

    public static String display(String lane) {
        return switch (lane) {
            case "surface" -> "地表";
            case "underground" -> "地下";
            case "nether" -> "下界";
            case "aether" -> "天域";
            case "twilight" -> "暮色";
            case "starlight" -> "星光";
            case "end" -> "末地";
            case "deeperdarker" -> "深暗";
            case "voidscape" -> "虚空";
            case "unknown", "" -> "未知";
            default -> "异界";
        };
    }
}
