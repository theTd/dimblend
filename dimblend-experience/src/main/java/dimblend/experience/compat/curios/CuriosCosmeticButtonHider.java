package dimblend.experience.compat.curios;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;

/**
 * 生存模式下抹掉 Curios 的"饰品/外观切换"按钮（客户端、仅限 Curios 在场所需）。
 *
 * <p>Curios 的按钮是 {@code CuriosScreen} 的私有字段 {@code cosmeticButton}，类型
 * {@code top.theillusivec4.curios.client.gui.CosmeticButton} 不在本模块的编译 classpath 上
 * （{@code build.gradle} 把 curios 整组排除，见 {@code :dimblend-experience} 的 compiling 配置），
 * 所以这里用反射取字段——<b>只能</b>用反射，写成 {@code @Shadow} 会因字段类型在编译期缺失而失败。</p>
 *
 * <p>三步都得做（缺一步都会留下可点的残影）：</p>
 * <ol>
 *   <li>{@code visible = false}——不再绘制；</li>
 *   <li>{@code active = false}——不再吃点击；</li>
 *   <li>{@code Screen#removeWidget}——从 {@code renderables / narratables / children} 三个列表里摘掉，
 *       否则 Tab 键导航和 narration 还会找到它。{@code removeWidget} 在 {@code Screen} 里是
 *       {@code protected}，仍然走反射调用，避免为了访问它去 shadow 一个父类方法。</li>
 * </ol>
 *
 * <p>任何一步反射失败都静默放弃：按钮最多还在，不能因此崩客户端。</p>
 */
public final class CuriosCosmeticButtonHider {

    private static final String BUTTON_FIELD = "cosmeticButton";

    private static Method removeWidget;

    private static boolean removeWidgetResolved;

    /** 隐藏并移除该界面的 Curios 外观按钮；不存在（未构建）时什么也不做。 */
    public static void hide(Screen screen) {
        Object button = readCosmeticButton(screen);
        if (button == null) {
            return;
        }
        if (button instanceof AbstractWidget widget) {
            widget.visible = false;
            widget.active = false;
        }
        if (button instanceof GuiEventListener listener) {
            removeWidget(screen, listener);
        }
    }

    private static Object readCosmeticButton(Screen screen) {
        try {
            Field field = findField(screen.getClass());
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            return field.get(screen);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(BUTTON_FIELD);
            } catch (NoSuchFieldException missing) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static void removeWidget(Screen screen, GuiEventListener widget) {
        Method method = resolveRemoveWidget();
        if (method == null) {
            return;
        }
        try {
            method.invoke(screen, widget);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 静态分析不认：反射签名漂移时宁可按钮留下，也不能抛到渲染循环里。
        }
    }

    private static Method resolveRemoveWidget() {
        if (!removeWidgetResolved) {
            removeWidgetResolved = true;
            try {
                Method method = Screen.class.getDeclaredMethod("removeWidget", GuiEventListener.class);
                method.setAccessible(true);
                removeWidget = method;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                removeWidget = null;
            }
        }
        return removeWidget;
    }

    private CuriosCosmeticButtonHider() {
    }
}
