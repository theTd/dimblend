package dimblend.experience.compat.doublejump;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 向 Double Jump 模组发送一次 {@code DoubleJumpPacket}（目标类不在编译 classpath，走反射）。
 *
 * <p>反射只解析一次：{@code DoubleJumpPacket()} 构造器 + {@code DoubleJumpNetworking.send(DoubleJumpPacket)}。
 * 失败时告警一次并禁用，绝不在每 tick 抛异常。</p>
 */
public final class DoubleJumpPacketBridge {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("dimblend_experience/double-jump");

    private static boolean resolved;
    private static boolean broken;
    private static Constructor<?> packetConstructor;
    private static Method sendMethod;

    /**
     * 发送一次二段跳包；反射不可用时静默跳过（已告警一次）。
     */
    public static void send() {
        if (!resolved) {
            resolve();
        }
        if (broken) {
            return;
        }
        try {
            sendMethod.invoke(null, packetConstructor.newInstance());
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOGGER.warn("[double-jump] 无法发送 DoubleJumpPacket，后续二段跳将被跳过：{}", e.toString());
        }
    }

    private static void resolve() {
        resolved = true;
        try {
            Class<?> packetClass = Class.forName("net.foufakirby.double_jump.network.DoubleJumpPacket");
            packetConstructor = packetClass.getConstructor();
            sendMethod = Class.forName("net.foufakirby.double_jump.network.DoubleJumpNetworking")
                    .getMethod("send", packetClass);
        } catch (ReflectiveOperationException | LinkageError e) {
            broken = true;
            LOGGER.warn("[double-jump] 无法解析 Double Jump 网络类，后续二段跳将被跳过：{}", e.toString());
        }
    }

    private DoubleJumpPacketBridge() {
    }
}
