package dimblend.craft.recipe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import dimblend.craft.DimblendCraft;

/**
 * TACZ 枪械工作台配方追加 {@code create:precision_mechanism} ×1（2026-11-25 批次，决议 21）。
 *
 * <p><b>为什么只能走反射：</b>本模块编译期不依赖 tacz（见 build.gradle 依赖注释），
 * 而枪械配方、材料与产物的类型都来自 tacz。反射句柄在首次命中时解析并缓存，
 * 解析失败只降级（保留原配方 + 一条告警），不影响其它规则。</p>
 *
 * <p><b>四条必须绕开的坑（report-w5 §5f，全部已按字节码核实）：</b></p>
 * <ol>
 *   <li>{@code GunSmithTableResult.encode} 直接抛 {@code UnsupportedOperationException}
 *       ——它只为解码而生。因此本类<b>绝不</b>走 {@link RecipeJsonEditor} 的
 *       「编码 → 改字段 → 解码」往返，而是直接构造新的配方对象。</li>
 *   <li>本类跑在 {@link RecipeEditReloadListener}（数据包重载）阶段，此时 tacz 的
 *       {@code TagsUpdatedEvent(SERVER_DATA_LOAD)} 还没执行
 *       （{@code CommonAssetsManager.onReload(TagsUpdatedEvent)} 字节码核实），
 *       {@code getResult()} 是空 {@link ItemStack#EMPTY}，只有私有的
 *       {@code GunSmithTableResult.raw}（{@code RawGunTableResult#type}）能判出「是枪」。</li>
 *   <li>必须复用旧的 {@code GunSmithTableResult} 实例：产物初始化在
 *       {@code TagsUpdatedEvent} 里由 tacz 逐条调用 {@code GunSmithTableRecipe#init} 完成，
 *       而 {@code init} 是「{@code raw != null} 才处理，处理完把 {@code raw} 置 null」
 *       （字节码核实）。新构造的配方只要持有<b>同一个</b>产物对象，重载之后的初始化就会
 *       照常把产物填好；反过来自己造一个产物对象，产物会永久为空、工作台永远合成不出枪。</li>
 *   <li><b>判定材料时绝不能调 {@link Ingredient#getItems()}（2026-11-26 用户缺陷根因）：</b>
 *       本类跑在数据包重载阶段，物品标签还没绑定（{@code TagManager} 只收集定义，
 *       {@code ReloadableServerResources#updateRegistryTags} 在整轮 reload 完成后才绑标签并发
 *       {@code TagsUpdatedEvent}），而 {@code Ingredient} 把解析结果缓存在私有
 *       {@code itemStacks} 字段里永不重算；标签为空时 NeoForge 还会塞一个名为
 *       {@code "Empty Tag: <标签 id>"} 的屏障占位物品并一起缓存 —— 症状就是「枪械工作台
 *       所有材料除精密构件外全变成 empty tag」、配方再也合不出来（详细取证见
 *       {@code .work-notes/slice-7.md}）。因此判据改成结构性相等
 *       {@link GunSmithTableMechanism#acceptsOnly}，不碰任何标签解析。</li>
 * </ol>
 *
 * <p>因为 {@code init} 已经跑完的阶段（{@code ServerStartedEvent} 的晚期规则）再来一次
 * 会把数量加成 2，所以本类只在重载阶段启用，见
 * {@link RecipeEditApplicator#apply(RecipeManager, HolderLookup.Provider, List, boolean)}
 * 的 {@code appendGunCrafts} 参数。纯规则（怎么认枪、已有精密构件时合并）在
 * {@link GunSmithTableMechanism}，便于离线单测。</p>
 */
public final class TaczGunRecipeEditor {

    /** 材料类型的全名：材料列表为空时只能按名字解析它（空表拿不到元素 Class）。 */
    private static final String INGREDIENT_CLASS_NAME = "com.tacz.guns.crafting.GunSmithTableIngredient";

    /** 枪械配方与其产物的反射句柄；解析失败后置 {@link #handlesUnresolved} 不再重试。 */
    private static volatile Handles handles;
    private static volatile boolean handlesUnresolved;

    /** 材料类型的反射句柄。 */
    private static volatile Ingredients ingredients;
    private static volatile boolean ingredientsUnresolved;

    /** 精密构件材料对象（{@code Ingredient.of(item)}，单 ItemValue）；一次解析后缓存，Create 未装时为 null。 */
    private static volatile Ingredient mechanismIngredient;
    private static volatile boolean mechanismResolved;

    private static volatile boolean missingIngredientWarned;
    private static volatile boolean reflectionFailureWarned;

    private TaczGunRecipeEditor() {
    }

    /**
     * 尝试对一条配方追加精密构件。
     *
     * <p>不需要注册表查找上下文：判枪靠产物对象与私有字段，建材料靠现有材料类型，
     * 都不走 codec（{@code encode} 会抛异常）。</p>
     *
     * @return 改写后的新配方；不是枪械工作台配方、产物不是枪、或反射不可用时 empty
     *         （原配方由调用方原样保留）
     */
    static Optional<Recipe<?>> edit(RecipeHolder<?> holder) {
        Recipe<?> recipe = holder.value();
        if (!isGunSmithTableRecipe(recipe)) {
            return Optional.empty();
        }
        Ingredient mechanism = mechanismIngredient();
        if (mechanism == null) {
            warnMissingIngredientOnce();
            return Optional.empty();
        }
        Handles recipes = handles(recipe.getClass());
        if (recipes == null) {
            return Optional.empty();
        }
        try {
            Object result = recipes.getResult().invoke(recipe);
            if (result == null) {
                return Optional.empty();
            }
            ResourceLocation outputId = itemId(recipes.resultStack().invoke(result));
            if (!GunSmithTableMechanism.isGunCraft(rawResultType(recipes, result), outputId)) {
                return Optional.empty();
            }

            List<?> oldInputs = asList(recipes.getInputs().invoke(recipe));
            Ingredients ingredientAccess = ingredients(recipe.getClass(), oldInputs);
            if (ingredientAccess == null) {
                return Optional.empty();
            }
            List<GunSmithTableMechanism.Input> views = new ArrayList<>(oldInputs.size());
            for (Object input : oldInputs) {
                views.add(new GunSmithTableMechanism.Input(
                        acceptsOnlyMechanism(ingredientAccess, input, mechanism),
                        count(ingredientAccess, input)));
            }
            OptionalInt merging = GunSmithTableMechanism.mergingIndex(views);

            List<Object> newInputs = new ArrayList<>(oldInputs);
            String detail;
            if (merging.isPresent()) {
                int index = merging.getAsInt();
                int newCount = views.get(index).count() + 1;
                newInputs.set(index, incremented(ingredientAccess, oldInputs.get(index), newCount));
                detail = "材料 " + GunSmithTableMechanism.PRECISION_MECHANISM + " ×"
                        + views.get(index).count() + " -> ×" + newCount;
            } else {
                newInputs.add(newIngredient(ingredientAccess, mechanism, 1));
                detail = "追加材料 " + GunSmithTableMechanism.PRECISION_MECHANISM + " ×1";
            }
            // 复用旧的产物实例：它的初始化由 tacz 在 TagsUpdatedEvent 里完成。
            Object edited = recipes.recipeCtor().newInstance(result, newInputs);
            DimblendCraft.LOGGER.info("[配方] 修改 {}：{}（规则：{}）", holder.id(), detail,
                    GunSmithTableMechanism.LABEL);
            return Optional.of((Recipe<?>) edited);
        } catch (Throwable t) {
            warnReflectionFailureOnce(holder.id(), t);
            return Optional.empty();
        }
    }

    /** 配方类型注册表键是否为 {@code tacz:gun_smith_table_crafting}（tacz 未装时注册表里没有该类型）。 */
    private static boolean isGunSmithTableRecipe(Recipe<?> recipe) {
        RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE
                .getOptional(GunSmithTableMechanism.RECIPE_TYPE).orElse(null);
        return type != null && recipe.getType() == type;
    }

    /** 解析并缓存精密构件；Create 未装或物品未注册时返回 null。 */
    private static Ingredient mechanismIngredient() {
        if (!mechanismResolved) {
            Item item = BuiltInRegistries.ITEM.getOptional(GunSmithTableMechanism.PRECISION_MECHANISM)
                    .orElse(null);
            mechanismIngredient = item == null ? null : Ingredient.of(item);
            mechanismResolved = true;
        }
        return mechanismIngredient;
    }

    /** 读私有的 {@code GunSmithTableResult.raw.type}；反射失败退化为 null（走产物物品兜底）。 */
    private static String rawResultType(Handles recipes, Object result) throws IllegalAccessException {
        Field rawField = recipes.rawField();
        Field rawTypeField = recipes.rawTypeField();
        if (rawField == null || rawTypeField == null) {
            return null;
        }
        Object raw = rawField.get(result);
        if (raw == null) {
            // init 之后 raw 被置 null，此时产物已经填好，由 isGunCraft 走产物物品兜底。
            return null;
        }
        Object type = rawTypeField.get(raw);
        return type instanceof String value ? value : null;
    }

    /**
     * 材料是否「只接受精密构件这一种物品」。
     *
     * <p><b>只做结构性相等，绝不解析材料内容</b>：本类跑在数据包重载阶段，物品标签尚未绑定，
     * 任何 {@code getItems()} 都会把「空标签占位」永久缓存进 {@link Ingredient}，代价与原因见
     * {@link GunSmithTableMechanism#acceptsOnly}。</p>
     */
    private static boolean acceptsOnlyMechanism(Ingredients access, Object input, Ingredient mechanism)
            throws ReflectiveOperationException {
        if (mechanism == null) {
            return false;
        }
        Object value = access.getIngredient().invoke(input);
        return value instanceof Ingredient ingredient
                && GunSmithTableMechanism.acceptsOnly(mechanism, ingredient);
    }

    private static int count(Ingredients access, Object input) throws ReflectiveOperationException {
        Object value = access.getCount().invoke(input);
        return value instanceof Integer number ? number : 1;
    }

    /** 复制一条已有材料条目并改数量。 */
    private static Object incremented(Ingredients access, Object input, int count)
            throws ReflectiveOperationException {
        return access.ctor().newInstance(access.getIngredient().invoke(input), count);
    }

    /** 用给定材料与数量新建一条材料条目。 */
    private static Object newIngredient(Ingredients access, Ingredient ingredient, int count)
            throws ReflectiveOperationException {
        return access.ctor().newInstance(ingredient, count);
    }

    /** 产物 ID：空栈返回 null（未初始化或上游给了空产物）。 */
    private static ResourceLocation itemId(Object stack) {
        if (!(stack instanceof ItemStack itemStack) || itemStack.isEmpty()) {
            return null;
        }
        return BuiltInRegistries.ITEM.getKey(itemStack.getItem());
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /** 解析并缓存枪械配方反射句柄；失败只降级并告警一次。 */
    private static Handles handles(Class<?> recipeClass) {
        if (handlesUnresolved) {
            return null;
        }
        Handles cached = handles;
        if (cached != null && cached.recipeClass() == recipeClass) {
            return cached;
        }
        try {
            Method getInputs = recipeClass.getMethod("getInputs");
            Method getResult = recipeClass.getMethod("getResult");
            Class<?> resultClass = getResult.getReturnType();
            Method resultStack = resultClass.getMethod("getResult");
            Constructor<?> recipeCtor = recipeClass.getDeclaredConstructor(resultClass, List.class);
            recipeCtor.setAccessible(true);
            Field rawField = null;
            Field rawTypeField = null;
            try {
                rawField = resultClass.getDeclaredField("raw");
                rawField.setAccessible(true);
                rawTypeField = rawField.getType().getDeclaredField("type");
                rawTypeField.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                // 私有字段访问被拒时保留 null：产物物品兜底仍能识别已初始化的配方。
                rawField = null;
                rawTypeField = null;
                DimblendCraft.LOGGER.warn("[配方] 无法读取 GunSmithTableResult.raw，枪械判定退化为产物物品 ID；"
                        + "重载阶段产物尚未初始化，本轮不会改写任何枪械配方");
            }
            Handles built = new Handles(recipeClass, getInputs, getResult, resultStack, recipeCtor,
                    rawField, rawTypeField);
            handles = built;
            return built;
        } catch (Throwable t) {
            handlesUnresolved = true;
            warnReflectionFailureOnce(null, t);
            return null;
        }
    }

    /** 解析并缓存材料类型反射句柄；材料表为空时按类名解析。 */
    private static Ingredients ingredients(Class<?> recipeClass, List<?> inputs) {
        if (ingredientsUnresolved) {
            return null;
        }
        Ingredients cached = ingredients;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> type = inputs.isEmpty()
                    ? Class.forName(INGREDIENT_CLASS_NAME, false, recipeClass.getClassLoader())
                    : inputs.get(0).getClass();
            Constructor<?> ctor = type.getDeclaredConstructor(Ingredient.class, int.class);
            ctor.setAccessible(true);
            Ingredients built = new Ingredients(ctor, type.getMethod("getIngredient"), type.getMethod("getCount"));
            ingredients = built;
            return built;
        } catch (Throwable t) {
            ingredientsUnresolved = true;
            warnReflectionFailureOnce(null, t);
            return null;
        }
    }

    private static void warnMissingIngredientOnce() {
        if (missingIngredientWarned) {
            return;
        }
        missingIngredientWarned = true;
        DimblendCraft.LOGGER.warn("[配方] 规则「{}」无法应用：{} 未注册（Create 缺失？），本轮跳过全部枪械工作台配方",
                GunSmithTableMechanism.LABEL, GunSmithTableMechanism.PRECISION_MECHANISM);
    }

    private static void warnReflectionFailureOnce(ResourceLocation recipeId, Throwable cause) {
        if (reflectionFailureWarned) {
            return;
        }
        reflectionFailureWarned = true;
        DimblendCraft.LOGGER.warn("[配方] 规则「{}」反射失败，枪械工作台配方保持原样（首个失败配方：{}）",
                GunSmithTableMechanism.LABEL, recipeId, cause);
    }

    /** 枪械配方与其产物的反射句柄：只读方法 / 构造器 / 字段，不依赖上游方法签名里的类型。 */
    private record Handles(Class<?> recipeClass, Method getInputs, Method getResult, Method resultStack,
            Constructor<?> recipeCtor, Field rawField, Field rawTypeField) {
    }

    /** 材料类型（{@code GunSmithTableIngredient}）的反射句柄。 */
    private record Ingredients(Constructor<?> ctor, Method getIngredient, Method getCount) {
    }
}
