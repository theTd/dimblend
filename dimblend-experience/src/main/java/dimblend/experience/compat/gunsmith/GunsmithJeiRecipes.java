package dimblend.experience.compat.gunsmith;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * gundb 的 JEI 铁砧修复配方改写：
 * <ul>
 *   <li>剔除全部 {@code IAttachment}/{@code AttachmentItem} 铁砧修复配方（配件不可修）；</li>
 *   <li>将全部枪械的右侧维修材料替换为对应枪型满修所需数量的 {@code create:precision_mechanism}
 *       （手枪 1、冲锋枪 2、霰弹枪 1、步枪 3、狙击枪 4、机枪 3、重武器 1）。</li>
 * </ul>
 *
 * <p>JEI 槽位展示单组堆叠数量时应传 {@code List.of(new ItemStack(item, count))}
 * （而非 N 个 count=1 的列表，后者会被 JEI 当成 N 种备选材料轮播）。</p>
 */
public final class GunsmithJeiRecipes {

    private static final String ANVIL_RECIPE_INTERFACE = "mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe";

    private static final ResourceLocation PRECISION_MECHANISM =
            ResourceLocation.fromNamespaceAndPath("create", "precision_mechanism");

    /**
     * 过滤 + 改写一批铁砧配方。
     *
     * @param recipes gundb {@code JeiRepairPlugin#getGunRecipes} 的返回值（必须可转成 {@link List}）
     * @return 过滤后的新列表；入参不可识别时原样返回
     */
    public static Object filter(Object recipes) {
        if (!(recipes instanceof List<?> list)) {
            return recipes;
        }
        Item precisionMechanism = BuiltInRegistries.ITEM.get(PRECISION_MECHANISM);
        boolean hasMechanism = precisionMechanism != Items.AIR;

        List<Object> filtered = new ArrayList<>(list.size());
        for (Object recipe : list) {
            if (recipe == null) {
                continue;
            }
            Class<?> recipeInterface = findAnvilRecipeInterface(recipe.getClass());
            ItemStack left = firstInput(recipe, recipeInterface, "getLeftInputs");
            if (left.isEmpty()) {
                filtered.add(recipe);
                continue;
            }
            if (GunsmithAccess.isAttachmentItem(left)) {
                // 配件在铁砧上不可维修：整条剔除。
                continue;
            }
            if (!GunsmithAccess.isGun(left) || !hasMechanism) {
                filtered.add(recipe);
                continue;
            }
            int count = GunsmithMath.mechanismsForFullRepair(GunsmithAccess.gunType(left));
            List<ItemStack> replacement = List.of(new ItemStack(precisionMechanism, count));
            Object rewritten = rewriteRightInputs(recipe, recipeInterface, replacement);
            filtered.add(rewritten != null ? rewritten : recipe);
        }
        return filtered;
    }

    private static ItemStack firstInput(Object recipe, Class<?> recipeInterface, String getter) {
        Object value = invoke(recipe, recipeInterface, getter);
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof ItemStack stack) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static Object invoke(Object target, Class<?> recipeInterface, String getter) {
        try {
            Class<?> owner = recipeInterface != null ? recipeInterface : target.getClass();
            Method method = owner.getMethod(getter);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object rewriteRightInputs(Object recipe, Class<?> recipeInterface, List<ItemStack> rightInputs) {
        try {
            if (recipeInterface == null) {
                return null;
            }
            ClassLoader loader = recipeInterface.getClassLoader();
            return Proxy.newProxyInstance(loader, new Class<?>[] {recipeInterface},
                    (proxy, method, args) -> {
                        if ("getRightInputs".equals(method.getName())) {
                            return rightInputs;
                        }
                        try {
                            return method.invoke(recipe, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Class<?> findAnvilRecipeInterface(Class<?> type) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (ANVIL_RECIPE_INTERFACE.equals(iface.getName())) {
                    return iface;
                }
            }
        }
        return null;
    }

    private GunsmithJeiRecipes() {
    }
}
