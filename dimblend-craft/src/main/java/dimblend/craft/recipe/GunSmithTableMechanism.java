package dimblend.craft.recipe;

import java.util.List;
import java.util.OptionalInt;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 枪械工作台配方「追加精密构件」的纯规则（2026-11-25 批次，决议 21 {@code guncraft_table_only}）。
 *
 * <p>需求：所有<b>枪械</b>的 TACZ 枪械工作台配方（{@code tacz:gun_smith_table_crafting}，
 * {@code 53} 条 tacz_default_gun + {@code 13} 条 Create Armorer）额外要求
 * {@code create:precision_mechanism} ×1；弹药 / 配件 / custom 产物的同名配方类型一条不动，
 * {@code createimmersivetacz} 的机械合成配方也不动。</p>
 *
 * <p>（2026-11-26 核实：Create Armorer 那 13 条写在 legacy 包内的
 * {@code data/create_armorer/recipes/} <b>复数</b>目录，而 1.21.1 的 {@code RecipeManager} 只认单数
 * {@code recipe/}，其 {@code PackConvertor} 也不做路径翻译 —— 运行期根本不加载，实机只命中 53 条。
 * 详见 {@code .work-notes/slice-7.md}。）</p>
 *
 * <p>本类只放与游戏注册表无关的判定与规划，便于离线单测；真正的反射改写见
 * {@link TaczGunRecipeEditor}。之所以要单独判别「枪」：这个配方类型同时承载
 * 弹药（{@code tacz:ammo}）与配件（{@code tacz:attachment}），只看配方类型会把它们
 * 一起加料。</p>
 */
public final class GunSmithTableMechanism {

    /** 配方类型注册表键（TACZ，注册名见 {@code ModRecipe.GUN_SMITH_TABLE_CRAFTING}）。 */
    public static final ResourceLocation RECIPE_TYPE = ResourceLocation.parse("tacz:gun_smith_table_crafting");

    /**
     * 已初始化后的枪械产物物品 ID。
     *
     * <p>{@code GunSmithTableResult#init} 之前 {@code getResult()} 是空栈（重载阶段必然如此），
     * 所以这条只作为 {@code raw} 反射失败时的兜底判据，见 {@link #isGunCraft}。</p>
     */
    public static final ResourceLocation GUN_OUTPUT_ITEM = ResourceLocation.parse("tacz:modern_kinetic_gun");

    /** 追加的材料：Create 精密构件。 */
    public static final ResourceLocation PRECISION_MECHANISM = ResourceLocation.parse("create:precision_mechanism");

    /** {@code RawGunTableResult#type} 里「枪械」的取值。 */
    public static final String GUN_RESULT_TYPE = "gun";

    /** 日志与规则说明用的名字。 */
    public static final String LABEL = "枪械工作台配方追加精密构件";

    private GunSmithTableMechanism() {
    }

    /**
     * 是否枪械配方。
     *
     * @param resultType   {@code RawGunTableResult.type}，读不到时为 {@code null}
     * @param outputItemId 已初始化时的产物物品 ID，未初始化或空栈时为 {@code null}
     */
    public static boolean isGunCraft(String resultType, ResourceLocation outputItemId) {
        if (GUN_RESULT_TYPE.equals(resultType)) {
            return true;
        }
        // 上游明确给出别的类型（ammo / attachment / custom）时以它为准，不再看产物物品。
        if (resultType != null) {
            return false;
        }
        return GUN_OUTPUT_ITEM.equals(outputItemId);
    }

    /**
     * 现有的材料条目视图：是否「只接受精密构件这一种物品」，以及当前数量。
     *
     * @param precisionMechanismOnly 材料只接受一个物品且就是精密构件（写死物品，不是标签）
     */
    public record Input(boolean precisionMechanismOnly, int count) {
    }

    /**
     * 材料条目是否「只接受 {@code expected} 描述的那一种材料」（单一物品或单个标签，不是多值/空材料）。
     *
     * <p><b>这里绝不能用 {@link Ingredient#getItems()} 判断（2026-11-26 用户缺陷的根因）：</b></p>
     * <ol>
     *   <li>1.21.1 的 {@code Ingredient} 把解析结果缓存在私有 {@code itemStacks} 字段里，只在
     *       {@code itemStacks == null} 时算一次，之后<b>永不重算</b>
     *       （NeoForge 21.1.249 源码 {@code net/minecraft/world/item/crafting/Ingredient.java}
     *       :57 字段、:94-105 {@code getItems()}）。</li>
     *   <li>调用本方法的地方是 {@link RecipeEditReloadListener}（数据包重载监听器）。此时
     *       {@code TagManager} 只收集了标签定义、{@code ReloadableServerResources#updateRegistryTags()}
     *       还没执行（{@code MinecraftServer#reloadResources} 在整轮 reload 完成<b>之后</b>才调它，
     *       且它才是 {@code TagsUpdatedEvent} 的发出点），所以标签型材料在这一刻必然是「未绑定」状态。</li>
     *   <li>标签为空时 NeoForge 并不返回空列表，而是塞一个名为
     *       <b>{@code "Empty Tag: <标签 id>"}</b> 的屏障占位物品（同行 :289-301 的
     *       {@code list.isEmpty()} 分支），它随即被缓存 —— 玩家在枪械工作台 / JEI 里看到的就是
     *       「所有材料都变成 empty tag」，服务端 {@code Ingredient#test} 也永远判不中，
     *       整张表彻底合不出枪。</li>
     *   <li>{@link Ingredient#equals} 只比较 {@code values} 数组（{@code ItemValue} 比物品+数量、
     *       {@code TagValue} 比标签 id，同行 :164-167），完全不触发标签解析，是重载阶段唯一安全的判据。
     *       语义也与原判据一致：精密构件是 {@code Ingredient.of(item)}（单 {@code ItemValue}、count 1）。</li>
     * </ol>
     *
     * @param expected 期望的唯一材料，即 {@code Ingredient.of(create:precision_mechanism)}；
     *                 {@code null} 表示上游未解析出精密构件，一律判否
     * @param actual   配方里现有的材料条目
     */
    public static boolean acceptsOnly(Ingredient expected, Ingredient actual) {
        return expected != null && expected.equals(actual);
    }

    /**
     * 决定「合并进已有条目」还是「新增条目」。
     *
     * @return 已有一条精密构件材料时返回其下标（改数量 +1 即可）；否则 empty 表示追加新条目。
     *         遍历整张表而不是只看首条：枪械配方的材料是任意顺序的，且同一材料可能出现在
     *         多个条目里（如 4 个铁锭写成 4 条），命中第一条即可，多余的条目属于上游写法。
     */
    public static OptionalInt mergingIndex(List<Input> inputs) {
        for (int i = 0; i < inputs.size(); i++) {
            if (inputs.get(i).precisionMechanismOnly()) {
                return OptionalInt.of(i);
            }
        }
        return OptionalInt.empty();
    }
}
