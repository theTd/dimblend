package dimblend.craft.recipe;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

/**
 * 配方编辑规则表：与 docs/配方修改需求.md 第 3 节修改总表逐行对应。
 *
 * <p>槽位编号见需求文档第 2 节（3×3 网格行优先 1-9）；
 * 「替换 / 添加」统一表示为写入对应槽位（空槽位即新增）。
 * 匹配基准已按整合包配方文件核实：</p>
 *
 * <ul>
 *   <li>蓄电池的可合成产物实为 {@code createaddition:modular_accumulator}
 *       （{@code createaddition:accumulator} 为已弃用方块，无任何配方），
 *       两个 ID 一并匹配以保持与文档一致；</li>
 *   <li>TACZ 弹药装配台配方产物是 {@code tacz:workbench_a} + custom_data，
 *       只能按配方 ID {@code tacz:ammo_workbench} 删除；</li>
 *   <li>植物油在全整合包范围内的获取配方只有 CDG 的 Create 压缩配方
 *       {@code createdieselgenerators:compacting/plant_oil}（决议 3），
 *       植物油桶无任何配方，仅作防御性删除；</li>
 *   <li>Steam 'n' Rails 自有铁轨即 {@code railways:track_*} 物品族，
 *       按产物前缀删除其合成台配方与序列组装配方（类型键
 *       {@code create:sequenced_assembly}，其产物即结果池首个产物）；
 *       原版 {@code minecraft:rail} 不受影响（其产物 ID 不以该前缀开头）。
 *       经整合包配方文件核实：该前缀产物的配方仅有 {@code minecraft:crafting_shaped}
 *      （3 条：track_coupler、两种 track_switch）与 {@code create:sequenced_assembly}
 *       两种类型；</li>
 *   <li>Propulsion 的固体燃烧器、液体燃烧器、斯特林引擎（2026-09-22 修订）
 *       均为 {@code minecraft:crafting_shaped}，按产物精确删除其合成台配方。</li>
 * </ul>
 */
public final class RecipeEditRules {

    private RecipeEditRules() {
    }

    public static List<RecipeEdit> edits() {
        ImmutableList.Builder<RecipeEdit> edits = ImmutableList.builder();

        // —— 3.1 Create（本体） ——
        edits.add(modify("扇叶", "create:propeller")
                .slot(5, RecipeIngredient.item("create:brass_ingot"))
                .build());
        edits.add(modify("动力冲压机", "create:mechanical_press")
                .slots(Set.of(4, 6), RecipeIngredient.item("minecraft:redstone"))
                .build());
        edits.add(modify("动力搅拌器", "create:mechanical_mixer")
                .slots(Set.of(4, 6), RecipeIngredient.item("minecraft:redstone"))
                .build());

        // —— 3.2 Create Addon（Create Crafts & Additions） ——
        edits.add(modify("蓄电池", "createaddition:accumulator", "createaddition:modular_accumulator")
                .slot(5, RecipeIngredient.tag("c:shulker_boxes"))
                .slots(Set.of(1, 3, 7, 9), RecipeIngredient.item("createaddition:electrum_ingot"))
                .build());
        edits.add(modify("电容", "createaddition:capacitor")
                .slots(Set.of(4, 6), RecipeIngredient.item("eternal_starlight:deepsilver_ingot"))
                .build());

        // —— 3.3 Create Connected ——
        edits.add(new RecipeEdit.DeleteByResultId("动力桥接器",
                ResultIdFilter.exact("create_connected:kinetic_bridge"), Set.of(), false));
        edits.add(modify("动力电池", "create_connected:kinetic_battery")
                .resultCount(1)
                .build());

        // —— 3.4 Create Steam 'n' Rails ——
        // 合成台配方 + 序列组装配方都删（2026-09-22 修订，决议 2）；
        // 原版 minecraft:rail 配方因产物前缀不同不受影响。
        edits.add(new RecipeEdit.DeleteByResultId("Steam 'n' Rails 自有铁轨",
                ResultIdFilter.prefixed("railways:track_"),
                Set.of(ResourceLocation.parse("create:sequenced_assembly")), false));

        // —— 3.5 Create Diesel Generators ——
        edits.add(modify("大型柴油引擎", "createdieselgenerators:huge_diesel_engine")
                .slots(Set.of(1, 3), RecipeIngredient.item("eternal_starlight:golem_steel_ingot"))
                .build());
        edits.add(modify("可控燃烧室", "createdieselgenerators:burner")
                .slots(Set.of(7, 9), RecipeIngredient.item("eternal_starlight:golem_steel_ingot"))
                .build());
        edits.add(new RecipeEdit.DeleteByRecipeId("植物油获取途径",
                Set.of(ResourceLocation.parse("createdieselgenerators:compacting/plant_oil"))));
        // 防御性规则：经全整合包枚举，植物油桶当前无任何配方（决议 3 要求覆盖），
        // 若上游更新后出现桶配方则被自动删除；未命中属预期，告警降级为 INFO。
        edits.add(new RecipeEdit.DeleteByResultId("植物油桶",
                ResultIdFilter.exact("createdieselgenerators:plant_oil_bucket"), Set.of(), true));

        // —— 3.6 Create: Propulsion ——
        // 2026-09-22 修订：三者均由网格修改改为删除合成台配方。
        edits.add(new RecipeEdit.DeleteByResultId("固体燃烧器",
                ResultIdFilter.exact("createpropulsion:solid_burner"), Set.of(), false));
        edits.add(new RecipeEdit.DeleteByResultId("液体燃烧器",
                ResultIdFilter.exact("createpropulsion:liquid_burner"), Set.of(), false));
        edits.add(new RecipeEdit.DeleteByResultId("斯特林引擎",
                ResultIdFilter.exact("createpropulsion:stirling_engine"), Set.of(), false));

        // —— 3.7 Create Fluid ——
        edits.add(new RecipeEdit.DeleteByResultId("铜水槽",
                ResultIdFilter.exact("fluid:copper_sink"), Set.of(), false));

        // —— 3.8 TACZ ——
        edits.add(new RecipeEdit.DeleteByRecipeId("弹药装配台",
                Set.of(ResourceLocation.parse("tacz:ammo_workbench"))));

        return edits.build();
    }

    private static GridRuleBuilder modify(String label, String... resultTargets) {
        return new GridRuleBuilder(label, resultTargets);
    }

    /** 网格修改规则的可读构造器。 */
    private static final class GridRuleBuilder {
        private final String label;
        private final ImmutableSet.Builder<ResourceLocation> targets = ImmutableSet.builder();
        private final ImmutableMap.Builder<Integer, RecipeIngredient> slotEdits = ImmutableMap.builder();
        private Integer resultCount;

        private GridRuleBuilder(String label, String... resultTargets) {
            this.label = label;
            for (String target : resultTargets) {
                targets.add(ResourceLocation.parse(target));
            }
        }

        private GridRuleBuilder slot(int slot, RecipeIngredient ingredient) {
            if (slot < 1 || slot > 9) {
                throw new IllegalArgumentException("规则[" + label + "]槽位超出 3x3 范围: " + slot);
            }
            slotEdits.put(slot, ingredient);
            return this;
        }

        private GridRuleBuilder slots(Set<Integer> slots, RecipeIngredient ingredient) {
            for (int slot : slots) {
                slot(slot, ingredient);
            }
            return this;
        }

        private GridRuleBuilder resultCount(int count) {
            this.resultCount = count;
            return this;
        }

        private RecipeEdit build() {
            Map<Integer, RecipeIngredient> edits = slotEdits.build();
            if (edits.isEmpty() && resultCount == null) {
                throw new IllegalStateException("规则[" + label + "]既无槽位编辑也无产物数量覆盖");
            }
            return new RecipeEdit.ModifyShapedGrid(label, targets.build(), edits, resultCount);
        }
    }
}