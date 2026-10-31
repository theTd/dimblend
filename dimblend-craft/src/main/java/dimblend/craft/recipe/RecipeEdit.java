package dimblend.craft.recipe;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

import com.google.gson.JsonElement;

/**
 * 单条运行时配方编辑规则。
 *
 * <p>每条规则对应 docs/配方修改需求.md 修改总表中的一行（或一个删除范围决议），
 * 规则按「产物 ID / 配方 ID」匹配，目标 mod 缺失时自然空转并产生未命中告警。</p>
 */
public sealed interface RecipeEdit {

    /** 日志与未命中告警用的规则名（与需求文档中的产物名对应）。 */
    String label();

    /**
     * 按配方 ID 删除，不限配方类型（可覆盖机器配方）。
     *
     * <p>用于产物 ID 与配方文件不一一对应的场景：TACZ 弹药装配台的配方产物是
     * tacz:workbench_a + custom_data，无法按产物匹配；CDG 植物油的获取途径是
     * Create 压缩配方（决议 3 明确包含机器配方）。</p>
     *
     * @param defensive 防御性规则：该配方当前因加载条件（如 {@code neoforge:conditions}）
     *                  不加载或上游未来才提供时，未命中属预期，告警降级为 INFO
     */
    record DeleteByRecipeId(String label, Set<ResourceLocation> recipeIds, boolean defensive)
            implements RecipeEdit {

        public DeleteByRecipeId {
            recipeIds = Set.copyOf(recipeIds);
        }

        /** 便捷构造：非防御性规则（当前必须命中），保留原两参数签名。 */
        public DeleteByRecipeId(String label, Set<ResourceLocation> recipeIds) {
            this(label, recipeIds, false);
        }
    }

    /**
     * 删除所有消耗该物品的配方（含机器配方与序列组装内部的原料，匹配走真实
     * {@code Ingredient} 判定，见 {@code RecipeInputMatcher}）。
     *
     * @param exemptRecipeIds 不参与本规则删除的配方 ID（按 ID 豁免、其余照旧）。
     *                        用于「按原料整体删除，但个别配方需要保留」的场景；
     *                        空集合表示全部删除，与旧行为一致
     */
    record DeleteByIngredientId(String label, ResourceLocation ingredientId,
            Set<ResourceLocation> exemptRecipeIds) implements RecipeEdit {

        public DeleteByIngredientId {
            exemptRecipeIds = Set.copyOf(exemptRecipeIds);
        }

        /** 便捷构造：不豁免任何配方，保留原两参数签名。 */
        public DeleteByIngredientId(String label, ResourceLocation ingredientId) {
            this(label, ingredientId, Set.of());
        }

        /** 该配方是否被本规则豁免（跳过删除，保持原样）。 */
        public boolean isExempt(ResourceLocation recipeId) {
            return exemptRecipeIds.contains(recipeId);
        }
    }

    /**
     * 按产物删除配方：合成台配方（{@code CraftingRecipe}，含 Create 机械合成等实现类）
     * 及 {@code recipeTypeIds} 额外指定的配方类型。
     *
     * <p>额外类型按配方类型注册表键匹配（字符串形式，不依赖上游 mod 类，
     * 编译期仍只有 vanilla + NeoForge 依赖）。例如 Steam 'n' Rails 自有铁轨的
     * 序列组装配方类型键为 {@code create:sequenced_assembly}，其
     * {@code getResultItem} 返回结果池首个产物，可直接按产物匹配。</p>
     *
     * @param recipeTypeIds 额外删除的配方类型注册表键（如 {@code create:sequenced_assembly}）；
     *                      空集合表示仅删除合成台配方
     * @param defensive 防御性规则：对应产物经配方文件枚举当前不存在任何配方（如植物油桶），
     *                  未命中属预期，告警降级为 INFO；用于上游更新后自动兜底删除
     */
    record DeleteByResultId(String label, ResultIdFilter resultIds, Set<ResourceLocation> recipeTypeIds,
            boolean defensive) implements RecipeEdit {
    }

    /**
     * 修改产物命中的有序合成配方（minecraft:crafting_shaped）。
     *
     * @param slotEdits      槽位 1-9（行优先，见需求文档第 2 节）→ 写入材料；
     *                       需求语义中的「替换」与「添加」统一为「写入该槽位」：
     *                       空槽位视为新增，已有材料则覆盖
     * @param newResultCount 产物数量覆盖值；null 表示沿用原数量（动力电池改产量用）
     */
    record ModifyShapedGrid(String label, Set<ResourceLocation> resultTargets,
            Map<Integer, RecipeIngredient> slotEdits, Integer newResultCount) implements RecipeEdit {
    }

    /**
     * 字段级修改规则的公共视图：精确配方 ID（{@link ModifyRecipeFields}）与
     * 配方 ID 前缀（{@link ModifyRecipeFieldsByIdPrefix}）两种匹配方式。
     */
    sealed interface FieldModify extends RecipeEdit {

        /** 字段路径 → 写入值。 */
        Map<String, JsonElement> fieldEdits();

        /** 该规则是否命中给定配方 ID。 */
        boolean matchesId(ResourceLocation recipeId);
    }

    /**
     * 按配方 ID 修改任意类型配方的 JSON 字段（机器配方：搅拌、洗涤、烧炼、分馏、合金炉等）。
     *
     * <p>实现上经配方自身序列化器的 Codec 做「编码 → 写入字段 → 解码」往返，
     * 与配方类型无关，编译期仍不依赖上游 mod 类。字段路径为点号分隔：
     * 每段是对象键或十进制数组下标（如 {@code results.0.count}）；末段为写入语义
     * （键存在则覆盖、不存在则新增，与网格规则的替换/添加一致），中间段必须已存在。
     * 编码失败、路径结构不符或解码失败时保留原配方并输出错误日志。</p>
     *
     * @param recipeIds  精确配方 ID 集合（已按整合包配方文件核实）
     * @param fieldEdits 字段路径 → 写入值
     */
    record ModifyRecipeFields(String label, Set<ResourceLocation> recipeIds,
            Map<String, JsonElement> fieldEdits) implements FieldModify {

        @Override
        public boolean matchesId(ResourceLocation recipeId) {
            return recipeIds.contains(recipeId);
        }
    }

    /**
     * 按配方 ID 前缀匹配并做字段级修改：用于上游在数据包重载之外动态注入、
     * 数量随上游数据变化而无法枚举精确 ID 的配方（如哨兵机械臂在
     * {@code ServerAboutToStartEvent} 按 TACZ 弹药表注入的 {@code ammo_cutting/*} 系列）。
     *
     * <p>匹配与字段写入语义同 {@link ModifyRecipeFields}；区别在于本规则命中的配方
     * 在重载阶段尚不存在，因此只允许放进 {@code RecipeEditRules.lateEdits()}
     * （服务器启动后阶段）使用。</p>
     */
    record ModifyRecipeFieldsByIdPrefix(String label, String recipeIdPrefix,
            Map<String, JsonElement> fieldEdits) implements FieldModify {

        @Override
        public boolean matchesId(ResourceLocation recipeId) {
            return recipeId.toString().startsWith(recipeIdPrefix);
        }
    }

    /**
     * 调整配方在 {@code RecipeManager} 最终列表中的先后：把 {@code recipeId} 移到 {@code anchorId} 之前。
     *
     * <p>用于同材料、不同热度的 Create 工作盆配方共存时决定命中优先级：
     * {@code BasinOperatingBlockEntity#getMatchingRecipes} 对候选按材料数降序<b>稳定</b>排序后取首个，
     * 材料数并列时保持 RecipeManager 遍历序（同材料集合的配方在 RecipeTrie 同一节点，
     * 其 values 按 RecipeFinder 遍历序插入）；热度较低（heated）的配方在超热火下同样通过热度校验，
     * 因此并列顺序即命中顺序。执行器 {@code replaceRecipes} 的列表顺序就是最终的
     * {@code byName}/{@code byType} 顺序，把目标配方移到锚点之前，可保证超热火下
     * 稳定命中目标版本。</p>
     *
     * <p><b>它做不到的事（2026-10-31 记录）：</b>排序只能决定「两条都通过热度校验时命中哪条」。
     * 要让低一档火完全不产出，必须删除低热度配方本身。黄铜早期曾按此思路删除加热版，
     * 但同一天用户改口为「保留两者、不改优先度」，黄铜现已改走<b>材料</b>区分两版
     * （加热版 8 铜粒 + 8 锌粒 =&gt; 1，超热版 铜锭 + 锌锭 =&gt; 2），不再需要也不允许排序
     * ——见 {@code RecipeEditRules} 3.9。</p>
     */
    record MoveRecipeBefore(String label, ResourceLocation recipeId, ResourceLocation anchorId)
            implements RecipeEdit {

        public MoveRecipeBefore {
            if (recipeId.equals(anchorId)) {
                throw new IllegalArgumentException("规则[" + label + "]排序目标与锚点相同: " + recipeId);
            }
        }
    }

    /**
     * 配方的通用内容替换（2026-10-31 R12 引入）：与配方类型无关，按配方 JSON 的字段名
     * 精确命中后改写，再经配方自身 Codec 往返解码。
     *
     * <p>与 {@link ModifyRecipeFields} 的区别：后者按固定配方 ID 列表、按点号路径写入指定字段；
     * 本类规则在<b>全部配方</b>上扫描，命中与否由 JSON 内容决定，用于「所有以某物品/标签为材料
     * 的配方」「所有产出某物品的配方」这种无法逐条枚举 ID 的范围（上游更新后自动跟进）。
     * 变换必须幂等：同一规则对已改写的 JSON 再应用一次不得产生差异。</p>
     */
    sealed interface ContentEdit extends RecipeEdit {

        /**
         * 返回改写后的配方 JSON；未命中时原样返回输入实例（不复制），调用方可用引用相等快速判断。
         * 命中时内部先深拷贝再改写，绝不允许原地修改输入。
         */
        JsonElement transform(JsonElement recipeJson);

        /** 防御性规则：当前无命中属预期，未命中告警降级为 INFO。 */
        boolean defensive();

        /** 命中的一句话描述，用于 {@code [配方]} 日志。 */
        String describe();
    }

    /**
     * 材料目标：物品 {@code {"item": id}} 或标签 {@code {"tag": id}} 的精确写法。
     *
     * <p><b>只做精确 id 匹配</b>：一个标签（如 {@code c:nuggets/copper}）即使包含目标物品，
     * 也不视为命中 —— 把标签换成单个物品会改变该配方对其余同类物品的接受范围。</p>
     */
    record IngredientTarget(boolean tag, ResourceLocation id) {

        public IngredientTarget {
            Objects.requireNonNull(id, "id");
        }

        public static IngredientTarget item(String id) {
            return new IngredientTarget(false, ResourceLocation.parse(id));
        }

        public static IngredientTarget tag(String id) {
            return new IngredientTarget(true, ResourceLocation.parse(id));
        }

        public String describe() {
            return tag ? "#" + id : id.toString();
        }
    }

    /**
     * 材料替换：把全部配方中精确写成 {@code from} 的材料对象改写成 {@code to} 的形式，
     * 其它材料与产物原样。只改材料（{@code item}/{@code tag} 字段），不碰产物字段。
     *
     * @param defensive 当前无命中属预期时置 true（上游以后新增引用会自动生效）
     */
    record ReplaceIngredient(String label, IngredientTarget from, IngredientTarget to, boolean defensive)
            implements ContentEdit {

        @Override
        public JsonElement transform(JsonElement recipeJson) {
            return RecipeContentTransform.replaceIngredient(recipeJson, from, to);
        }

        @Override
        public String describe() {
            return "材料 " + from.describe() + " -> " + to.describe();
        }
    }

    /**
     * 产物替换：把全部配方中产物 id 为 {@code from} 的条目改成 {@code to}，数量与其余字段原样。
     *
     * <p>只走产物路径（{@code result} / {@code results} / {@code transitional_item}），
     * 材料侧与 {@code custom_data} 里的同名字段不得被改写。</p>
     *
     * @param defensive 当前无命中属预期时置 true
     */
    record ReplaceResultId(String label, ResourceLocation from, ResourceLocation to, boolean defensive)
            implements ContentEdit {

        @Override
        public JsonElement transform(JsonElement recipeJson) {
            return RecipeContentTransform.replaceResultId(recipeJson, from, to);
        }

        @Override
        public String describe() {
            return "产物 " + from + " -> " + to;
        }
    }
}
