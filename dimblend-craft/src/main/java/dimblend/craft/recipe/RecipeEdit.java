package dimblend.craft.recipe;

import java.util.Map;
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
     */
    record DeleteByRecipeId(String label, Set<ResourceLocation> recipeIds) implements RecipeEdit {
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
            Map<String, JsonElement> fieldEdits) implements RecipeEdit {
    }

    /**
     * 调整配方在 {@code RecipeManager} 最终列表中的先后：把 {@code recipeId} 移到 {@code anchorId} 之前。
     *
     * <p>用于同材料、不同热度的 Create 工作盆配方共存时决定命中优先级：
     * {@code BasinOperatingBlockEntity#getMatchingRecipes} 对候选按材料数降序<b>稳定</b>排序后取首个，
     * 材料数并列时保持 RecipeManager 遍历序（同材料集合的配方在 RecipeTrie 同一节点，
     * 其 values 按 RecipeFinder 遍历序插入）；热度较低（heated）的配方在超热火下同样通过热度校验，
     * 因此并列顺序即命中顺序。执行器 {@code replaceRecipes} 的列表顺序就是最终的
     * {@code byName}/{@code byType} 顺序，把超热配方移到加热配方之前，可保证超热火下
     * 稳定命中超热版本（2026-10-05 修订，黄铜锭超热/加热共存）。</p>
     */
    record MoveRecipeBefore(String label, ResourceLocation recipeId, ResourceLocation anchorId)
            implements RecipeEdit {

        public MoveRecipeBefore {
            if (recipeId.equals(anchorId)) {
                throw new IllegalArgumentException("规则[" + label + "]排序目标与锚点相同: " + recipeId);
            }
        }
    }
}