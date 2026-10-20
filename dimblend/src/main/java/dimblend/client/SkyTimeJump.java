package dimblend.client;

/** Compares visible time with natural daytime advance between two rendered frames. */
public final class SkyTimeJump {
    private static final long TOLERANCE = 4L;

    private SkyTimeJump() { }

    public static boolean unexpected(long previousDayTime, long dayTime, long daylightAdvance) {
        long correction = dayTime - previousDayTime - daylightAdvance;
        return correction >= TOLERANCE || correction <= -TOLERANCE;
    }
}
