package dimblend.craft.recipe;

import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

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
 *   <li>铂系（2026-10-05 修订）：全整合包产出铂锭的烧炼/高炉配方只有
 *       Propulsion 的 4 条 smelting + 2 条 blasting（已逐 jar 枚举核实），
 *       Create 风扇批量熔炼走同一组配方；粉碎粗铂洗涤配方的输入物品
 *       {@code create:crushed_raw_platinum} 由 Create 本体注册；</li>
 *   <li>下界合金（2026-10-05 修订）：原版合成配方 ID 为 {@code minecraft:netherite_ingot}；
 *       合金炉（Eternal Starlight {@code eternal_starlight:alloy}）配方输入列表
 *       经其 Serializer 校验上限为 9 个，4 金锭 + 4 碎片共 8 个输入可行。</li>
 *   <li>黄铜超热/加热共存（2026-10-05 修订）：Create 热度校验中 heated 配方在超热火下
 *       同样可匹配；工作盆候选按材料数降序稳定排序、并列保持 RecipeManager 遍历序
 *       （Create 源码 BasinOperatingBlockEntity/RecipeTrie 核实），故经
 *       {@link RecipeEdit.MoveRecipeBefore} 把超热版排在加热版之前，保证超热火稳定产 x2。</li>
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

        // —— 3.9 Create 机器配方（2026-10-05 修订） ——
        // 黄铜锭搅拌产物 2 -> 1（超热版本由随包配方 dimblend_craft:brass_ingot_from_superheated_mixing 提供）
        edits.add(modifyFields("黄铜锭混合搅拌产量", "create:mixing/brass_ingot")
                .set("results.0.count", 1)
                .build());
        // 超热/加热黄铜同材料共存：Create 热度校验下 heated 配方在超热火同样可匹配，
        // 并列时按 RecipeManager 遍历序命中，故将超热版移到加热版之前保证超热火稳定产 x2
        edits.add(new RecipeEdit.MoveRecipeBefore("超热黄铜搅拌优先",
                ResourceLocation.parse("dimblend_craft:brass_ingot_from_superheated_mixing"),
                ResourceLocation.parse("create:mixing/brass_ingot")));
        // 安山合金搅拌 无需加热 -> 加热（铁粒版与锌粒版）
        edits.add(modifyFields("安山合金混合搅拌需加热",
                "create:mixing/andesite_alloy", "create:mixing/andesite_alloy_from_zinc")
                .set("heat_requirement", "heated")
                .build());

        // —— 3.10 Create: Propulsion 铂系（2026-10-05 修订） ——
        // 铂锭/铂粒的合成台互转删除（自动压缩、自动合成随之一并失效），
        // 改由随包配方提供 塑形（超热 9 -> 1）与 混合搅拌（加热 1 -> 8）
        edits.add(new RecipeEdit.DeleteByRecipeId("铂锭与铂粒的合成互转",
                Set.of(ResourceLocation.parse("createpropulsion:crafting/platinum_ingot_from_nugget"),
                        ResourceLocation.parse("createpropulsion:crafting/platinum_nugget_from_ingot"))));
        // 粉碎粗铂洗涤：铂粒 9 -> 1，金粒概率 50% -> 25%（经验颗粒 x4 50% 不变）
        edits.add(modifyFields("粉碎粗铂洗涤产物", "createpropulsion:splashing/crushed_raw_platinum")
                .set("results.0.count", 1)
                .set("results.2.chance", 0.25)
                .build());
        // 铂矿及其变种、粗铂、粉碎粗铂的烧炼/高炉产物 铂锭 -> 铂粒（批量熔炼走同一配方）
        edits.add(modifyFields("铂烧炼产物改铂粒",
                "createpropulsion:blasting/platinum_ingot_from_deepslate_platinum_ore",
                "createpropulsion:blasting/platinum_ingot_from_platinum_ore",
                "createpropulsion:smelting/platinum_ingot_from_crushed_raw_platinum",
                "createpropulsion:smelting/platinum_ingot_from_deepslate_platinum_ore",
                "createpropulsion:smelting/platinum_ingot_from_platinum_ore",
                "createpropulsion:smelting/platinum_ingot_from_raw_platinum")
                .set("result.id", "createpropulsion:platinum_nugget")
                .build());

        // —— 3.11 Create Diesel Generators 分馏（2026-10-05 修订） ——
        // 删除加热档（原油 100 -> 汽油 50 + 柴油 50）；超级加热档改为 柴油 75 -> 60、汽油 75 -> 40
        edits.add(new RecipeEdit.DeleteByRecipeId("原油分馏（加热）",
                Set.of(ResourceLocation.parse("createdieselgenerators:distillation/crude_oil"))));
        edits.add(modifyFields("原油分馏（超级加热）产物量",
                "createdieselgenerators:distillation/superheated_crude_oil")
                .set("results.0.amount", 60)
                .set("results.1.amount", 40)
                .build());

        // —— 3.12 下界合金获取（2026-10-05 修订） ——
        // 删除原版 碎片 x4 + 金锭 x4 合成（自动搅拌随之一并失效），
        // 改由随包配方提供 混合搅拌（超级加热）；合金炉配方碎片 2 -> 4
        edits.add(new RecipeEdit.DeleteByRecipeId("下界合金锭原版合成",
                Set.of(ResourceLocation.parse("minecraft:netherite_ingot"))));
        edits.add(modifyFields("下界合金合金炉配方", "eternal_starlight:alloy/netherite")
                .setJson("ingredients", "["
                        + "{\"tag\": \"c:ingots/gold\"},"
                        + "{\"tag\": \"c:ingots/gold\"},"
                        + "{\"tag\": \"c:ingots/gold\"},"
                        + "{\"tag\": \"c:ingots/gold\"},"
                        + "{\"item\": \"minecraft:netherite_scrap\"},"
                        + "{\"item\": \"minecraft:netherite_scrap\"},"
                        + "{\"item\": \"minecraft:netherite_scrap\"},"
                        + "{\"item\": \"minecraft:netherite_scrap\"}"
                        + "]")
                .build());

        return edits.build();
    }

    private static GridRuleBuilder modify(String label, String... resultTargets) {
        return new GridRuleBuilder(label, resultTargets);
    }

    private static FieldRuleBuilder modifyFields(String label, String... recipeIds) {
        return new FieldRuleBuilder(label, recipeIds);
    }

    /** 机器配方字段修改规则的可读构造器。 */
    private static final class FieldRuleBuilder {
        private final String label;
        private final ImmutableSet.Builder<ResourceLocation> recipeIds = ImmutableSet.builder();
        private final ImmutableMap.Builder<String, JsonElement> fieldEdits = ImmutableMap.builder();

        private FieldRuleBuilder(String label, String... recipeIds) {
            this.label = label;
            for (String recipeId : recipeIds) {
                this.recipeIds.add(ResourceLocation.parse(recipeId));
            }
        }

        private FieldRuleBuilder set(String path, int value) {
            fieldEdits.put(path, new JsonPrimitive(value));
            return this;
        }

        private FieldRuleBuilder set(String path, double value) {
            fieldEdits.put(path, new JsonPrimitive(value));
            return this;
        }

        private FieldRuleBuilder set(String path, String value) {
            fieldEdits.put(path, new JsonPrimitive(value));
            return this;
        }

        /** 写入数组/对象等复合值，以原始 JSON 文本声明（如合金炉配方整段材料表）。 */
        private FieldRuleBuilder setJson(String path, String rawJson) {
            fieldEdits.put(path, JsonParser.parseString(rawJson));
            return this;
        }

        private RecipeEdit build() {
            Map<String, JsonElement> edits = fieldEdits.build();
            if (edits.isEmpty()) {
                throw new IllegalStateException("规则[" + label + "]无任何字段编辑");
            }
            return new RecipeEdit.ModifyRecipeFields(label, recipeIds.build(), edits);
        }
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