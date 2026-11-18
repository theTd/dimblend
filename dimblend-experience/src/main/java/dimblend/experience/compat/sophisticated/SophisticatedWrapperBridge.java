package dimblend.experience.compat.sophisticated;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * 精妙核心（sophisticatedcore / sophisticatedbackpacks）升级容器到当前 wrapper 的反射桥。
 *
 * <p>两个目标模组都不在本模块编译期 classpath（{@code @Pseudo} + {@code remap = false}），
 * 因此这里只按运行时类型走公开方法解析，绝不在源码里出现 Sophisticated 类型名：
 * {@code @Shadow} 需要精确字段描述符，而 {@code BlockConverterRecipeContainer#inputInventory}
 * 的字段类型正是 {@code CraftingItemHandler}，没法用 {@code Container} 或 {@code Object} 影子化。</p>
 *
 * <p>语义要点：所有解析方法都"调用时"读取容器<b>当前</b>的 wrapper。上游 bug 的形状是
 * 构造期把 {@code upgradeWrapper::getInventory} 之类的供应商绑死在旧 wrapper 实例上，
 * 模块槽同步替换 wrapper 后不跟随。本类只提供解析，绑定由各 mixin 的 lambda 完成。</p>
 *
 * <p>句柄按接收者的运行时类缓存；{@link MethodHandles#publicLookup()} 只解析 public 成员，
 * 目标类的 public 方法都在 public 类上（抽象基类也是 public），解析失败直接抛
 * {@link IllegalStateException}——静默降级会让"补丁没生效"重新变成不可见故障。</p>
 */
public final class SophisticatedWrapperBridge {

    private static final ConcurrentHashMap<Class<?>, MethodHandle> GET_UPGRADE_WRAPPER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, MethodHandle> GET_INVENTORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, MethodHandle> GET_INPUT_INVENTORY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, MethodHandle> GET_RECIPE_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, MethodHandle> SET_RECIPE_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, MethodHandle> GET_RECIPE_CONTAINER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> INPUT_INVENTORY_FIELD = new ConcurrentHashMap<>();

    private SophisticatedWrapperBridge() {
    }

    /** {@code UpgradeContainerBase#getUpgradeWrapper()}；容器或 wrapper 为空时返回 {@code null}。 */
    public static Object getUpgradeWrapper(Object upgradeContainer) {
        if (upgradeContainer == null) {
            return null;
        }
        return invoke(GET_UPGRADE_WRAPPER, upgradeContainer, "getUpgradeWrapper");
    }

    /**
     * 容器当前 wrapper 的合成/锻造/切石库存。
     *
     * <p>同时适用于 {@code CraftingUpgradeWrapper#getInventory()} 与
     * {@code SmithingUpgradeWrapper#getInventory()}（两者都返回
     * {@code StatefulComponentItemHandler}，即 {@link IItemHandlerModifiable}）。</p>
     */
    public static IItemHandlerModifiable resolveInventory(Object upgradeContainer) {
        Object wrapper = getUpgradeWrapper(upgradeContainer);
        if (wrapper == null) {
            return null;
        }
        return (IItemHandlerModifiable) invoke(GET_INVENTORY, wrapper, "getInventory");
    }

    /** 切石机 wrapper 当前的输入库存（{@code BlockConverterUpgradeWrapper#getInputInventory()}）。 */
    public static IItemHandlerModifiable resolveBlockConverterInputInventory(Object upgradeContainer) {
        Object wrapper = getUpgradeWrapper(upgradeContainer);
        if (wrapper == null) {
            return null;
        }
        return (IItemHandlerModifiable) invoke(GET_INPUT_INVENTORY, wrapper, "getInputInventory");
    }

    /** 切石机 wrapper 当前选中的配方 id；wrapper 为空时返回空 {@link Optional}。 */
    @SuppressWarnings("unchecked")
    public static Optional<ResourceLocation> resolveBlockConverterRecipeId(Object upgradeContainer) {
        Object wrapper = getUpgradeWrapper(upgradeContainer);
        if (wrapper == null) {
            return Optional.empty();
        }
        Object recipeId = invoke(GET_RECIPE_ID, wrapper, "getRecipeId");
        return recipeId == null ? Optional.empty() : (Optional<ResourceLocation>) recipeId;
    }

    /** 把选中配方 id 写进容器<b>当前</b>的 wrapper。 */
    public static void writeBlockConverterRecipeId(Object upgradeContainer, ResourceLocation recipeId) {
        Object wrapper = getUpgradeWrapper(upgradeContainer);
        if (wrapper == null) {
            return;
        }
        invoke(SET_RECIPE_ID, wrapper, "setRecipeId", recipeId);
    }

    /** {@code BlockConverterUpgradeContainer#getRecipeContainer()}；空容器返回 {@code null}。 */
    public static Object getRecipeContainer(Object blockConverterUpgradeContainer) {
        if (blockConverterUpgradeContainer == null) {
            return null;
        }
        return invoke(GET_RECIPE_CONTAINER, blockConverterUpgradeContainer, "getRecipeContainer");
    }

    /**
     * {@code BlockConverterRecipeContainer#inputInventory} 的私有 {@code CraftingItemHandler} 字段。
     *
     * <p>用反射读取而不是 {@code @Shadow}：字段描述符是
     * {@code Lnet/p3pp3rf1y/sophisticatedcore/upgrades/crafting/CraftingItemHandler;}，
     * 编译期不可见，写成 {@code Container}/{@code Object} 都会因描述符不匹配被 Mixin 拒绝。
     * 返回类型 {@link Container} 是它实现的普通原版接口。</p>
     */
    public static Container getBlockConverterInputInventoryField(Object blockConverterRecipeContainer) {
        if (blockConverterRecipeContainer == null) {
            return null;
        }
        return (Container) readInputInventoryField(blockConverterRecipeContainer);
    }

    // ---- 反射基础设施 ----

    /**
     * 用擦除到 {@code Object} 的 {@link MethodHandle#invokeExact} 零分配调用：
     * 槽位渲染与 {@code mayPlace} 每帧高频触发供应商，避免 {@code invokeWithArguments} 每次分配数组。
     */
    private static Object invoke(ConcurrentHashMap<Class<?>, MethodHandle> cache, Object receiver,
            String methodName) {
        MethodHandle handle = handle(cache, receiver.getClass(), methodName, 0);
        try {
            return handle.invokeExact(receiver);
        } catch (Throwable throwable) {
            throw new IllegalStateException("调用 " + receiver.getClass().getName() + "#" + methodName + " 失败",
                    throwable);
        }
    }

    private static void invoke(ConcurrentHashMap<Class<?>, MethodHandle> cache, Object receiver,
            String methodName, Object arg0) {
        MethodHandle handle = handle(cache, receiver.getClass(), methodName, 1);
        try {
            handle.invokeExact(receiver, arg0);
        } catch (Throwable throwable) {
            throw new IllegalStateException("调用 " + receiver.getClass().getName() + "#" + methodName + " 失败",
                    throwable);
        }
    }

    private static MethodHandle handle(ConcurrentHashMap<Class<?>, MethodHandle> cache, Class<?> receiverClass,
            String methodName, int argumentCount) {
        MethodHandle cached = cache.get(receiverClass);
        if (cached != null) {
            return cached;
        }
        MethodHandle raw = resolvePublicVirtual(receiverClass, methodName, argumentCount);
        MethodHandle adapted = argumentCount == 0
                ? raw.asType(MethodType.methodType(Object.class, Object.class))
                : raw.asType(MethodType.methodType(void.class, Object.class, Object.class));
        MethodHandle previous = cache.putIfAbsent(receiverClass, adapted);
        return previous != null ? previous : adapted;
    }

    private static MethodHandle resolvePublicVirtual(Class<?> receiverClass, String methodName, int argumentCount) {
        Method method = findPublicMethod(receiverClass, methodName, argumentCount);
        if (method == null) {
            throw new IllegalStateException("在 " + receiverClass.getName() + " 上找不到公开方法 " + methodName
                    + "/" + argumentCount);
        }
        try {
            return MethodHandles.publicLookup().unreflect(method);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法公开解析 " + method.getDeclaringClass().getName() + "#"
                    + methodName, exception);
        }
    }

    private static Method findPublicMethod(Class<?> type, String methodName, int argumentCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            Method declared = declaredPublicMethod(current, methodName, argumentCount);
            if (declared != null) {
                return declared;
            }
            Method fromInterfaces = findInInterfaces(current.getInterfaces(), methodName, argumentCount);
            if (fromInterfaces != null) {
                return fromInterfaces;
            }
        }
        return null;
    }

    private static Method findInInterfaces(Class<?>[] interfaces, String methodName, int argumentCount) {
        for (Class<?> candidate : interfaces) {
            Method declared = declaredPublicMethod(candidate, methodName, argumentCount);
            if (declared != null) {
                return declared;
            }
            Method nested = findInInterfaces(candidate.getInterfaces(), methodName, argumentCount);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * 按名字 + 形参个数解析：本桥接读写的成员在目标类里都只有一种参数个数
     * （{@code getUpgradeWrapper/getInventory/getInputInventory/getRecipeId/getRecipeContainer}
     * 为 0，{@code setRecipeId} 为 1），因此不需要泛型/具体参数类型信息。
     */
    private static Method declaredPublicMethod(Class<?> type, String methodName, int argumentCount) {
        if (!Modifier.isPublic(type.getModifiers())) {
            return null;
        }
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.getName().equals(methodName)
                    && candidate.getParameterCount() == argumentCount
                    && Modifier.isPublic(candidate.getModifiers())) {
                return candidate;
            }
        }
        return null;
    }

    private static Object readInputInventoryField(Object blockConverterRecipeContainer) {
        Class<?> receiverClass = blockConverterRecipeContainer.getClass();
        Field field = INPUT_INVENTORY_FIELD.get(receiverClass);
        if (field == null) {
            field = findInputInventoryField(receiverClass);
            Field previous = INPUT_INVENTORY_FIELD.putIfAbsent(receiverClass, field);
            if (previous != null) {
                field = previous;
            }
        }
        try {
            return field.get(blockConverterRecipeContainer);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("无法读取 " + receiverClass.getName() + "#inputInventory", exception);
        }
    }

    private static Field findInputInventoryField(Class<?> receiverClass) {
        for (Class<?> current = receiverClass; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField("inputInventory");
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // 沿类层级继续找。
            }
        }
        throw new IllegalStateException("在 " + receiverClass.getName() + " 的类层级上找不到字段 inputInventory");
    }
}
