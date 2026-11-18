package dimblend.experience.compat.toolbelt;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 背包识别（纯注册名 / 类名判断，编译期零 Sophisticated Backpacks 依赖）。
 *
 * <p>ToolBelt 的 {@code ConfigData#isItemStackAllowed} 对<b>所有不可堆叠物品</b>默认放行
 * （{@code allowAllNonStackableItems = true}），而背包的 {@code getMaxStackSize()} 恒为 1。
 * 于是手持背包按 R 打开径向菜单会把背包合法换进腰带，随后腰带层用 ToolBelt 自定义的
 * {@code TOOLBELT_LEFTSIDE/RIGHTSIDE} {@code ItemDisplayContext} 把背包送进
 * Sophisticated Backpacks 的 BEWLR，造成闪退。</p>
 *
 * <p>判据只有两条，都不需要加载背包模组的类：</p>
 * <ol>
 *   <li>注册名在 {@code sophisticatedbackpacks} 命名空间且 path 以 {@code backpack} 结尾
 *       （{@code backpack}、{@code iron_backpack} 等）；</li>
 *   <li>物品类继承链上出现含 {@code BackpackItem} 的类名（覆盖附加模组自建的背包物品）。</li>
 * </ol>
 */
public final class ToolBeltBackpackGuard {

    private static final String BACKPACK_MOD_NAMESPACE = "sophisticatedbackpacks";
    private static final String BACKPACK_CLASS_MARKER = "BackpackItem";

    /**
     * 该物品栈是否为背包。
     */
    public static boolean isBackpackItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null && isBackpackRegistryName(id.getNamespace(), id.getPath())) {
            return true;
        }
        return isBackpackClassChain(stack.getItem().getClass());
    }

    /**
     * 注册名判据（纯函数，供单测钉死）。
     *
     * @param namespace 物品注册命名空间
     * @param path      物品注册路径
     * @return {@code sophisticatedbackpacks} 下 path 为 {@code backpack} 或以 {@code _backpack} 结尾
     */
    public static boolean isBackpackRegistryName(String namespace, String path) {
        if (namespace == null || path == null) {
            return false;
        }
        if (!BACKPACK_MOD_NAMESPACE.equals(namespace)) {
            return false;
        }
        return "backpack".equals(path) || path.endsWith("_backpack");
    }

    /**
     * 类名判据（纯函数，供单测钉死）：继承链上任一类简单名包含 {@code BackpackItem}。
     */
    public static boolean isBackpackClassChain(Class<?> itemClass) {
        for (Class<?> current = itemClass; current != null && current != Object.class;
                current = current.getSuperclass()) {
            if (current.getSimpleName().contains(BACKPACK_CLASS_MARKER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主手或副手是否拿着背包。
     */
    public static boolean isHoldingBackpack(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        return isBackpackItem(entity.getMainHandItem()) || isBackpackItem(entity.getOffhandItem());
    }

    private ToolBeltBackpackGuard() {
    }
}
