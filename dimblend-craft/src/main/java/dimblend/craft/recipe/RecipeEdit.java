package dimblend.craft.recipe;

import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

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
}