package dimblend.experience.compat.create;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Supplier;

import com.simibubi.create.content.equipment.goggles.GogglesItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.EntityCapability;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 让 Create 的「工程师护目镜」视窗（{@code GogglesItem#isWearingGoggles}）把玩家戴着
 * hardcore_nvg 的夜视仪也算进去：无论夜视开关是否开启、电量是否耗尽，只要夜视仪在头盔槽或
 * Curios 任意饰品槽中，即可全天候启用工程师护目镜全部佩戴功能。
 *
 * <p>注册方式是 Create 官方的 {@code GogglesItem.addIsWearingPredicate(Predicate)}，
 * 与护目镜本身的判定并列，不做任何替换或覆盖。</p>
 *
 * <p><b>无编译期 Curios 依赖</b>：{@code curios} 不在本模块编译 classpath 上，
 * 因此通过公开接口（{@code CuriosCapability.INVENTORY}、{@code ICuriosItemHandler#getCurios}、
 * {@code ICurioStacksHandler#getStacks}）一次性解析并缓存反射句柄，避免对包私有实现类调用
 * {@code getMethod} 触发 {@code IllegalAccessException}；槽位遍历直接用编译期可见的
 * {@link EntityCapability} 与 {@link IItemHandler}。缺少 Curios 时静默降级。</p>
 */
public final class NvgGogglesCompat {

    /** hardcore_nvg 的命名空间（全部 8 款夜视仪变体均在此命名空间下）。 */
    private static final String NVG_NAMESPACE = "hardcore_nvg";

    private static final String CURIOS_CAPABILITY_CLASS = "top.theillusivec4.curios.api.CuriosCapability";
    private static final String CURIOS_ITEM_HANDLER_CLASS =
            "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";
    private static final String CURIO_STACKS_HANDLER_CLASS =
            "top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler";

    private static EntityCapability<?, Void> inventoryCapability;
    private static Method getCuriosMethod;
    private static Method getStacksMethod;
    private static boolean resolved;

    private static boolean registered;

    /**
     * 幂等注册；只在 Create 与 hardcore_nvg 同时在场时由 {@code DimBlend#commonSetup} 调用。
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        GogglesItem.addIsWearingPredicate(NvgGogglesCompat::isWearingNvg);
    }

    /** Create 的护目镜判定谓词：头盔槽或 Curios 任意饰品槽内戴着夜视仪即返回 true。 */
    public static boolean isWearingNvg(Player player) {
        return isNvg(player.getItemBySlot(EquipmentSlot.HEAD)) || isNvgInCurios(player);
    }

    private static boolean isNvg(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return NVG_NAMESPACE.equals(id.getNamespace());
    }

    private static boolean isNvgInCurios(Player player) {
        try {
            resolve();
            if (inventoryCapability == null || getCuriosMethod == null || getStacksMethod == null) {
                return false;
            }
            Object handler = inventoryCapability.getCapability(player, null);
            if (handler == null) {
                return false;
            }
            Object curios = getCuriosMethod.invoke(handler);
            if (!(curios instanceof Map<?, ?> map)) {
                return false;
            }
            for (Object stacksHandler : map.values()) {
                if (stacksHandler == null) {
                    continue;
                }
                Object stacks = getStacksMethod.invoke(stacksHandler);
                if (stacks instanceof IItemHandler itemHandler) {
                    int slotCount = itemHandler.getSlots();
                    for (int slot = 0; slot < slotCount; slot++) {
                        if (isNvg(itemHandler.getStackInSlot(slot))) {
                            return true;
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Object holder = Class.forName(CURIOS_CAPABILITY_CLASS).getField("INVENTORY").get(null);
            Object rawCap = holder instanceof Supplier<?> supplier ? supplier.get() : holder;
            if (!(rawCap instanceof EntityCapability<?, ?> cap)) {
                return;
            }
            Class<?> handlerInterface = Class.forName(CURIOS_ITEM_HANDLER_CLASS);
            Class<?> stacksInterface = Class.forName(CURIO_STACKS_HANDLER_CLASS);
            getCuriosMethod = handlerInterface.getMethod("getCurios");
            getStacksMethod = stacksInterface.getMethod("getStacks");
            inventoryCapability = (EntityCapability<?, Void>) cap;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            inventoryCapability = null;
            getCuriosMethod = null;
            getStacksMethod = null;
        }
    }

    private NvgGogglesCompat() {
    }
}
