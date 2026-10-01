package dimblend.experience.exploration;

/**
 * 客户端侧远行诅咒层级：由 {@code FarCurseTierPayload} S2C 同步维护，供 z256 进度条读取。
 * 仅物理客户端读写，无客户端专属依赖；断线时复位，进服由登录同步重新下发。
 */
public final class ClientFarCurseTier {

    private static volatile int tier;

    public static int get() {
        return tier;
    }

    public static void set(int fromServer) {
        tier = Math.max(0, fromServer);
    }

    public static void reset() {
        tier = 0;
    }

    private ClientFarCurseTier() {
    }
}
