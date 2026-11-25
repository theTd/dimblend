package dimblend.experience.compat.gunsmith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * TaCZ 客户端骨骼动画与关键帧音效的播放速度倍率（仅客户端使用）：
 *
 * <ul>
 *   <li>换弹动画（动画名含 {@code reload}）：按 {@link GunsmithMath#reloadSpeedMultiplier(int)}
 *       （{@code 1.0x..1.5x}）同步加速，与服务端 {@code getReloadTime()} 保持 1:1 严格对齐；</li>
 *   <li>卡壳排障动画（{@code unjam} 排除 {@code unjam_idle}，以及枪械处于 {@code JAMMED}
 *       状态时回退播放的 {@code inspect} / {@code inspect_empty}）：按
 *       {@link GunsmithMath#unjamSpeedMultiplier(int)}（{@code 1.0x..3.0x}）同步加速，
 *       与路径 1 的 {@code UnjamPacket} 及路径 2 的 {@code StartJamWorkerPacket} 严格对齐。</li>
 * </ul>
 */
public final class GunsmithAnimations {

    private static Method getAnimation;
    private static Method getTransitionTo;
    private static Field nameField;
    private static boolean resolved;

    /** 返回该 runner 当前应套用的时间轴推进倍率（1.0f 表示不加速）。 */
    public static float speedMultiplier(Object runner) {
        if (runner == null) {
            return 1.0f;
        }
        ItemStack held = localHeldGun();
        int level = GunsmithData.getLevel(held);
        if (level <= 0) {
            return 1.0f;
        }
        String name = animationName(runner);
        if (name == null) {
            return 1.0f;
        }
        if (isUnjamAnimationName(name, held)) {
            return GunsmithMath.unjamSpeedMultiplier(level);
        }
        if (isReloadAnimationName(name)) {
            return GunsmithMath.reloadSpeedMultiplier(level);
        }
        return 1.0f;
    }

    /**
     * 过渡混合（{@code transitionProgressNs}）应套用的推进倍率：
     * 取当前 runner 与目标 {@code transitionTo} runner 两者的最大倍率，
     * 避免切入 3x 排障或 1.5x 换弹时被 0.2s 固定过渡拖慢。
     */
    public static float transitionSpeedMultiplier(Object runner) {
        if (runner == null) {
            return 1.0f;
        }
        float self = speedMultiplier(runner);
        Object target = transitionTo(runner);
        if (target == null) {
            return self;
        }
        return Math.max(self, speedMultiplier(target));
    }

    /** 关键帧音效（{@code ObjectAnimationSoundChannel#playSound}）应套用的音高倍率。 */
    public static float soundPitchMultiplier(Object runner) {
        if (runner == null) {
            return 1.0f;
        }
        ItemStack held = localHeldGun();
        int level = GunsmithData.getLevel(held);
        if (level <= 0) {
            return 1.0f;
        }
        String name = animationName(runner);
        if (name == null) {
            return 1.0f;
        }
        if (isUnjamAnimationName(name, held)) {
            return GunsmithMath.unjamSoundPitch(level);
        }
        if (isReloadAnimationName(name)) {
            return GunsmithMath.reloadSoundPitch(level);
        }
        return 1.0f;
    }

    /** 该 runner 是否正在播放换弹动画。 */
    public static boolean isReloadAnimation(Object runner) {
        String name = animationName(runner);
        return name != null && isReloadAnimationName(name);
    }

    /** runner 正在播的动画名；读不到返回 {@code null}。 */
    public static String animationName(Object runner) {
        if (runner == null) {
            return null;
        }
        resolve();
        if (getAnimation == null || nameField == null) {
            return null;
        }
        try {
            Object animation = getAnimation.invoke(runner);
            if (animation == null) {
                return null;
            }
            Object name = nameField.get(animation);
            return name instanceof String string ? string : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object transitionTo(Object runner) {
        resolve();
        if (getTransitionTo == null) {
            return null;
        }
        try {
            return getTransitionTo.invoke(runner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static boolean isReloadAnimationName(String name) {
        return name.toLowerCase(Locale.ROOT).contains("reload");
    }

    private static boolean isUnjamAnimationName(String name, ItemStack heldGun) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("unjam") || (lower.contains("unjam") && !lower.contains("idle"))) {
            return true;
        }
        return lower.contains("inspect") && GunsmithAccess.isJammed(heldGun);
    }

    private static ItemStack localHeldGun() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) {
            return ItemStack.EMPTY;
        }
        ItemStack held = minecraft.player.getMainHandItem();
        return GunsmithAccess.isGun(held) ? held : ItemStack.EMPTY;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> runnerClass = Class.forName("com.tacz.guns.api.client.animation.ObjectAnimationRunner");
            getAnimation = runnerClass.getMethod("getAnimation");
            getTransitionTo = runnerClass.getMethod("getTransitionTo");
            Class<?> animationClass = Class.forName("com.tacz.guns.api.client.animation.ObjectAnimation");
            nameField = animationClass.getField("name");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            getAnimation = null;
            getTransitionTo = null;
            nameField = null;
        }
    }

    private GunsmithAnimations() {
    }
}
