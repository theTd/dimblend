package dimblend.experience.compat.doublejump;

import java.lang.reflect.Field;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * 读取 Double Jump 模组自己的 {@code Keybinds.DOUBLE_JUMP_KEY}（默认 SPACE）。
 *
 * <p>该模组的类不在编译 classpath 上，因此只反射取一次静态字段；取不到时退回原版跳跃键
 * （只读 {@code isDown()}，绝不 {@code consumeClick()}，否则会吞掉原版跳跃的点击计数）。</p>
 *
 * <p>用 {@code consumeClick()} 而不是只看 {@code isDown()} 的沿：同一 tick 内完成的
 * “松手再按”只在 click 计数里留痕，这正是上游 {@code airTicks >= 2} 丢弃快速二连点的原因。</p>
 */
public final class DoubleJumpClientInput {

    /** 单 tick 的按键采样。 */
    public record Poll(boolean down, boolean clicked) {
    }

    private static boolean resolved;
    private static KeyMapping doubleJumpMapping;

    public static Poll poll() {
        KeyMapping mapping = resolveDoubleJumpMapping();
        if (mapping != null) {
            return new Poll(mapping.isDown(), mapping.consumeClick());
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null) {
            return new Poll(false, false);
        }
        return new Poll(minecraft.options.keyJump.isDown(), false);
    }

    private static KeyMapping resolveDoubleJumpMapping() {
        if (resolved) {
            return doubleJumpMapping;
        }
        resolved = true;
        try {
            Class<?> keybinds = Class.forName("net.foufakirby.double_jump.common.Keybinds");
            Field field = keybinds.getField("DOUBLE_JUMP_KEY");
            doubleJumpMapping = (KeyMapping) field.get(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            doubleJumpMapping = null;
        }
        return doubleJumpMapping;
    }

    private DoubleJumpClientInput() {
    }
}
