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
 *   <li>黄铜两版并存（2026-10-31 用户新口径，取代同一天早些时候的「仅超级加热」方案）：
 *       口径原话「保留两者 不更改优先度 普通加热配方改为 8铜粒+8锌粒=1黄铜锭」。
 *       加热版 {@code create:mixing/brass_ingot} 保留，材料整段改写为 8 个铜粒 + 8 个锌粒标签、
 *       产物数量改为 1；超热版 {@code dimblend_craft:brass_ingot_from_superheated_mixing}
 *       （铜锭 + 锌锭 =&gt; x2）原样不动；两版之间不加任何排序规则。
 *       两版靠<b>材料</b>自然区分，不靠顺序：{@code BasinOperatingBlockEntity#getMatchingRecipes}
 *       按材料数降序稳定排序（16 条 vs 2 条），且 8+8 铜/锌粒的配方在只有 2 个锭时根本
 *       不满足材料条件，2 个锭的配方在只有粒时同样不满足 —— 任一条都不会被另一条遮住。
 *       注意 Create 的 {@code ProcessingRecipeParams} 用 {@code Codec.either(SIZED_FLUID_INGREDIENT,
 *       Ingredient.CODEC).listOf()} 表示 material 列表，物品 {@code Ingredient} 本身不带数量，
 *       所以「8 个」只能靠标签条目重复 8 次表达（Create 自带 {@code mixing/cardboard_pulp}
 *       就是同一标签写 4 次 = 消耗 4 个）。</li>
 *   <li>细雪熔水与火药（2026-10-10 修订）：细雪熔水配方实为 Create Fluid 的
 *       {@code fluid:mixing/powder_snow_melt}（细雪流体 {@code fluid:powder_snow}
 *       1000mB 加热 =&gt; 水 1000mB，整合包配方文件核实，Create 本体无此配方）；
 *       TACZ 火药无序合成配方 ID 为 {@code tacz:gunpowder}。新搅拌配方的硝石粉来自
 *       Eternal Starlight（{@code eternal_starlight:saltpeter_powder}）、硫磺石来自
 *       Biomes O' Plenty（{@code biomesoplenty:brimstone}，中文名「硫磺石」）；
 *       Create 搅拌配方的材料/产物列表经其 Codec 均支持物品与流体混排
 *       （ProcessingRecipeParams 核实）。</li>
 *   <li>森罗厨房石磨与哨兵机械臂切削（2026-10-14 修订）：石磨燧石磨火药为普通数据包
 *       配方 {@code kaleidoscope_cookery:millstone/gunpowder_from_flint}，按配方 ID 删除；
 *       哨兵机械臂的弹药切削配方（{@code create:cutting}，铜板 =&gt; 待加工弹壳）不在
 *       数据包中，由上游在 {@code ServerAboutToStartEvent} 按 TACZ 弹药表动态注入
 *       （反射改 {@code RecipeManager.byType}，配方 ID 形如
 *       {@code sentrymechanicalarm:ammo_cutting/<弹药路径>}，数量随弹药表变化，
 *       2026-10-14 实例日志核实当前 30 条），故经 {@link RecipeEdit.ModifyRecipeFieldsByIdPrefix}
 *       前缀匹配放进 {@link #lateEdits()}，在 {@code ServerStartedEvent}（晚于上游注入点）
 *       统一把材料 {@code ingredients.0.item} 改写为黄铜板 {@code create:brass_sheet}。
 *       /reload 后上游动态配方本身即被原版重载清掉且不再注入（上游行为），
 *       晚期规则无需也不应在重载阶段重放。</li>
 *   <li>列车机壳原料删除（2026-10-31 修订）：删除所有以 {@code create:railway_casing}
 *       为原料的配方，但按配方 ID 豁免列车门 {@code create:crafting/kinetics/train_door}
 *       与列车活板门 {@code create:crafting/kinetics/train_trapdoor}。
 *       经整合包配方文件逐 jar 枚举，当前以机壳为原料的配方共 9 条
 *       （Create 6.0.10 七条 + Steam 'n' Rails 0.2.0-beta.2 两条），豁免后仍删除 7 条；
 *       上游以后新增的机壳原料配方不在豁免集合内，会被自动删除，规则未命中时仍告警。</li>
 *   <li>重名物品断源与替换（2026-10-31 R12 用户逐条确认）：暮色 3 条铜粒产出配方保留、产物改成
 *       Create 铜粒（暮色铜粒不再有产出），直接引用暮色铜粒的材料改成 Create 铜粒；次元酒窖 pepsi
 *       删除唯一产出配方（带加载条件，属防御性）；森罗面粉的
 *       材料（物品与 c:flour 标签两种写法）改成 c:flours/wheat、唯一产出改成 create:wheat_flour。
 *       规则实现为「全配方 JSON 内容替换」原语（{@link RecipeEdit.ReplaceIngredient} /
 *       {@link RecipeEdit.ReplaceResultId}），与配方类型无关。</li>
 * </ul>
 */
public final class RecipeEditRules {

    /**
     * 「列车机壳参与的配方」规则的豁免集合（2026-10-31 需求 R10）：列车门与列车活板门
     * 的合成台配方以机壳为原料，需要恢复；其余以机壳为原料的配方照旧删除。
     */
    private static final ImmutableSet<ResourceLocation> CASING_EXEMPT_RECIPE_IDS = ImmutableSet.of(
            ResourceLocation.parse("create:crafting/kinetics/train_door"),
            ResourceLocation.parse("create:crafting/kinetics/train_trapdoor"));

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
        // 铜水槽中央水桶改为空桶；无限水仍由 experience 的 /dbx 开关控制。
        edits.add(modify("铜水槽", "fluid:copper_sink")
                .slot(5, RecipeIngredient.item("minecraft:bucket"))
                .build());

        // —— 3.8 TACZ ——
        edits.add(new RecipeEdit.DeleteByRecipeId("弹药装配台",
                Set.of(ResourceLocation.parse("tacz:ammo_workbench"))));

        // —— 3.9 Create 机器配方（2026-10-31 用户新口径） ——
        // 「保留两者 不更改优先度 普通加热配方改为 8铜粒+8锌粒=1黄铜锭」：
        // 加热版 create:mixing/brass_ingot 保留，材料与产物数量按新口径改写；
        // 超热版 dimblend_craft:brass_ingot_from_superheated_mixing（铜锭 + 锌锭 => x2）原样不动；
        // 两版之间不加排序规则。两版靠材料自然区分，顺序对命中没有影响：
        // 16 条材料的加热版在只有 2 个锭时不满足条件，2 条材料的超热版在只有粒时也不满足。
        //
        // 材料用标签而不是写死物品 ID：c:nuggets/copper 与 c:nuggets/zinc 由 Create 6.0.10
        // 随包定义（data/c/tags/item/nuggets/copper.json、nuggets/zinc.json），
        // 暮色森林的 twilightforest:copper_nugget 与 Steam 'n' Rails 的 create:zinc_nugget
        // 各自追加进这两个标签；写死 create:copper_nugget 会把暮色森林的铜粒排除在外。
        //
        // 为什么是 16 条而不是 2 条带 count 的条目：Create 的 ProcessingRecipeParams
        // 用 Codec.either(SIZED_FLUID_INGREDIENT, Ingredient.CODEC).listOf() 表示材料列表
        //（Create 6.0.10 ProcessingRecipeParams.java:57），带 count 的只有流体那一侧，
        // 物品 Ingredient 不携带数量；NeoForge 也没有带数量的物品 ingredient 类型
        //（Ingredient 只认 item / tag / compound / difference / intersection / block_tag /
        // data_component）。Create 自带的 mixing/cardboard_pulp.json 同样是把
        // create:pulpifiable 写 4 次表示消耗 4 个，与此一致。
        edits.add(modifyFields("黄铜锭加热搅拌（8 铜粒 + 8 锌粒 => 1）", "create:mixing/brass_ingot")
                .setJson("ingredients", "["
                        + repeatedTagEntries("c:nuggets/copper", 8) + ","
                        + repeatedTagEntries("c:nuggets/zinc", 8)
                        + "]")
                .set("results.0.count", 1)
                .build());
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
        // 粉碎粗铂洗涤：铂粒 9 -> 2，金粒概率 50% -> 25%（经验颗粒 x4 50% 不变）
        edits.add(modifyFields("粉碎粗铂洗涤产物", "createpropulsion:splashing/crushed_raw_platinum")
                .set("results.0.count", 2)
                .set("results.2.chance", 0.25)
                .build());
        // 铂矿及其变种、粗铂、粉碎粗铂的烧炼/高炉产物 铂锭 x1 -> 铂粒 x2（批量熔炼走同一配方）
        edits.add(modifyFields("铂烧炼产物改铂粒",
                "createpropulsion:blasting/platinum_ingot_from_deepslate_platinum_ore",
                "createpropulsion:blasting/platinum_ingot_from_platinum_ore",
                "createpropulsion:smelting/platinum_ingot_from_crushed_raw_platinum",
                "createpropulsion:smelting/platinum_ingot_from_deepslate_platinum_ore",
                "createpropulsion:smelting/platinum_ingot_from_platinum_ore",
                "createpropulsion:smelting/platinum_ingot_from_raw_platinum")
                .set("result.id", "createpropulsion:platinum_nugget")
                .set("result.count", 2)
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

        // —— 3.13 细雪熔水与 TACZ 火药（2026-10-10 修订） ——
        // 删除 Create Fluid 的加热细雪熔水，改由随包配方提供 混合搅拌（无需加热）：
        // 细雪 1000mB + 烈焰粉 x1 => 水 1000mB + 烈焰粉 x1（烈焰粉作催化剂返还）
        edits.add(new RecipeEdit.DeleteByRecipeId("细雪熔水（加热）",
                Set.of(ResourceLocation.parse("fluid:mixing/powder_snow_melt"))));
        // 删除 TACZ 火药无序合成（合成台 / 自动搅拌一并失效），
        // 改由随包配方提供 混合搅拌（加热）：木炭 x3 + 硝石粉 x1 + 硫磺石 x2 => 火药 x3
        edits.add(new RecipeEdit.DeleteByRecipeId("TACZ 火药合成",
                Set.of(ResourceLocation.parse("tacz:gunpowder"))));

        // —— 3.14 森罗物语：厨房（2026-10-14 修订） ——
        // 删除石磨 燧石 => 火药（类型 kaleidoscope_cookery:millstone 的数据包配方）
        edits.add(new RecipeEdit.DeleteByRecipeId("石磨燧石磨火药",
                Set.of(ResourceLocation.parse("kaleidoscope_cookery:millstone/gunpowder_from_flint"))));

        // —— 3.16 YAT 净水链重做（2026-10-16 确认） ——
        // 删除旧瓶/桶/碗净化及堵塞滤芯再生，保留滤芯制造、过滤器功能与新配方。
        edits.add(new RecipeEdit.DeleteByRecipeId("旧净水与堵塞滤芯清洗配方",
                Set.of(
                        ResourceLocation.parse("yet_another_thirst:carbon_filter_core_from_campfire_cooking"),
                        ResourceLocation.parse("yet_another_thirst:carbon_filter_core_from_smelting"),
                        ResourceLocation.parse("yet_another_thirst:clean_fabric_filter"),
                        ResourceLocation.parse("yet_another_thirst:terracotta_water_bowl_from_campfire_cooking_acceptable_purity_1"),
                        ResourceLocation.parse("yet_another_thirst:terracotta_water_bowl_from_campfire_cooking_purified_purity_2"),
                        ResourceLocation.parse("yet_another_thirst:terracotta_water_bowl_from_campfire_cooking_slightly_dirty_purity_-1"),
                        ResourceLocation.parse("yet_another_thirst:terracotta_water_bowl_from_campfire_cooking_slightly_dirty_purity_0"),
                        ResourceLocation.parse("yet_another_thirst:terracotta_water_bowl_from_smelting_acceptable_purity_-1"),
                        ResourceLocation.parse("yet_another_thirst:terracotta_water_bowl_from_smelting_acceptable_purity_0"),
                        ResourceLocation.parse("yet_another_thirst:terracotta_water_bowl_from_smelting_purified_purity_1"),
                        ResourceLocation.parse("yet_another_thirst:terracotta_water_bowl_from_smelting_purified_purity_2"),
                        ResourceLocation.parse("yet_another_thirst:water_bottle_from_campfire_cooking_acceptable_purity_1"),
                        ResourceLocation.parse("yet_another_thirst:water_bottle_from_campfire_cooking_purified_purity_2"),
                        ResourceLocation.parse("yet_another_thirst:water_bottle_from_campfire_cooking_slightly_dirty_purity_-1"),
                        ResourceLocation.parse("yet_another_thirst:water_bottle_from_campfire_cooking_slightly_dirty_purity_0"),
                        ResourceLocation.parse("yet_another_thirst:water_bottle_from_smelting_acceptable_purity_-1"),
                        ResourceLocation.parse("yet_another_thirst:water_bottle_from_smelting_acceptable_purity_0"),
                        ResourceLocation.parse("yet_another_thirst:water_bottle_from_smelting_purified_purity_1"),
                        ResourceLocation.parse("yet_another_thirst:water_bottle_from_smelting_purified_purity_2"),
                        ResourceLocation.parse("yet_another_thirst:water_bucket_from_campfire_cooking_acceptable_purity_1"),
                        ResourceLocation.parse("yet_another_thirst:water_bucket_from_campfire_cooking_slight_dirty_purity_-1"),
                        ResourceLocation.parse("yet_another_thirst:water_bucket_from_campfire_cooking_slight_dirty_purity_0"),
                        ResourceLocation.parse("yet_another_thirst:water_bucket_from_campfire_purified_purity_2"),
                        ResourceLocation.parse("yet_another_thirst:water_bucket_from_smelting_acceptable_purity_-1"),
                        ResourceLocation.parse("yet_another_thirst:water_bucket_from_smelting_acceptable_purity_0"),
                        ResourceLocation.parse("yet_another_thirst:water_bucket_from_smelting_purfied_purity_1"),
                        ResourceLocation.parse("yet_another_thirst:water_bucket_from_smelting_purfied_purity_2"))));

        // —— 3.17 重名物品的断源与替换（2026-10-31 用户逐条确认 R12） ——
        // dup-04 铜粒（2026-10-31 改口径：「断掉一方，但是将原本 tf 铜粒参与的配方换成 create 的」）：
        // 暮色 3 条产出配方保留，只把产物 id 换成 create:copper_nugget，数量原样
        // （熔炼/高炉 铜浆果 -> 1 铜粒、无序合成 1 铜锭 -> 9 铜粒）；全包枚举里只有这 3 条产出
        // twilightforest:copper_nugget，替换后该物品不再有任何产出（注册与贴图保留，成为遗留物）。
        // 铜浆果 twilightforest:copper_berry 仍有用途（熔炼成 Create 铜粒），不会变成孤儿；
        // Create 一侧 6 条产出配方原样。
        edits.add(new RecipeEdit.ReplaceResultId("暮色铜粒产出改 Create 铜粒",
                ResourceLocation.parse("twilightforest:copper_nugget"),
                ResourceLocation.parse("create:copper_nugget"), false));
        // 材料替换方向：按「只改名 = 改非主 mod 一侧」的约定推断为保留 Create（任务书标注为推断）。
        // 只替换直接写 twilightforest:copper_nugget 的材料对象；c:nuggets/copper 标签虽然内含
        // 该物品也不替换（换成单个物品会改变配方对其余铜粒的接受范围）。
        // 全整合包 11734 条配方（188 主 jar + 82 嵌套 jar）枚举结果：当前没有任何配方直接以
        // 该物品为材料，规则标防御性，上游以后新增直接引用时自动生效。
        edits.add(new RecipeEdit.ReplaceIngredient("暮色森林铜粒材料改 Create 铜粒",
                RecipeEdit.IngredientTarget.item("twilightforest:copper_nugget"),
                RecipeEdit.IngredientTarget.item("create:copper_nugget"), true));

        // dup-06 可乐：kaleidoscope_dim_wine:pepsi 与 kaleidoscope_world_liquor:cola 同名。
        // 方向（按同一约定推断：保留主 mod「世界名酒」）：只删次元酒窖产出 pepsi 的唯一配方
        // （kaleidoscope_tavern:barrel，产物 pepsi；带 the_bumblezone 加载条件，本包未装该 mod，
        // 该配方当前不加载 -> 防御性规则）。物品保留，世界名酒 cola 的配方与材料一条不碰。
        edits.add(new RecipeEdit.DeleteByRecipeId("次元酒窖可乐产出",
                Set.of(ResourceLocation.parse("kaleidoscope_dim_wine:barrel/pepsi")), true));

        // dup-11 / dup-11b 面粉（2026-10-31 追加）：森罗 kaleidoscope_cookery:flour 与
        // Create create:wheat_flour 分属互不相认的 c:flour / c:flours 标签，用户要求不合并标签、
        // 把森罗那边所有涉及面粉的配方改用 Create 小麦粉。
        // 全整合包枚举：材料侧 23 条配方共 59 处（物品形式 38 处 + c:flour 标签形式 21 处），
        // 集中在森罗厨房/国味/末地/暮色/下界五个 jar（含其代其它 mod 命名空间出的配方）；
        // 产物侧只有 1 条（kaleidoscope_cookery:millstone/flour_from_wheat，小麦 -> 面粉）。
        // 两条材料规则分别处理「直接写物品」与「写 c:flour 标签」两种写法，产物规则只改产物 id，
        // 数量原样；三者都幂等。执行后森罗面粉既无产出也无消费者（用户接受，物品注册与贴图保留）。
        edits.add(new RecipeEdit.ReplaceIngredient("森罗面粉材料改 Create 小麦面粉（物品形式）",
                RecipeEdit.IngredientTarget.item("kaleidoscope_cookery:flour"),
                RecipeEdit.IngredientTarget.tag("c:flours/wheat"), false));
        edits.add(new RecipeEdit.ReplaceIngredient("森罗面粉材料改 Create 小麦面粉（c:flour 标签）",
                RecipeEdit.IngredientTarget.tag("c:flour"),
                RecipeEdit.IngredientTarget.tag("c:flours/wheat"), false));
        edits.add(new RecipeEdit.ReplaceResultId("森罗面粉产物改 Create 小麦面粉",
                ResourceLocation.parse("kaleidoscope_cookery:flour"),
                ResourceLocation.parse("create:wheat_flour"), false));

        return edits.build();
    }

    /** 原料删除依赖本轮标签，统一在服务端标签绑定后执行。 */
    public static List<RecipeEdit> ingredientEdits() {
        return List.of(trainCasingRule());
    }

    /**
     * 服务器启动后阶段（{@code ServerStartedEvent}）应用的规则：作用于上游在数据包
     * 重载之外动态注入的配方。本阶段规则在初始加载与 /reload 的重载阶段一律不参与，
     * 未命中告警也只针对启动后的最终配方状态。
     */
    public static List<RecipeEdit> lateEdits() {
        // —— 3.15 哨兵机械臂弹药切削（2026-10-14 修订） ——
        // 切削材料 铜板 -> 黄铜板：上游按 TACZ 弹药表动态注入 sentrymechanicalarm:ammo_cutting/*
        //（create:cutting，铜板 => 待加工弹壳），前缀匹配覆盖全部弹药种类
        return List.of(trainCasingRule(),
                new RecipeEdit.ModifyRecipeFieldsByIdPrefix("弹药切削铜板改黄铜板",
                "sentrymechanicalarm:ammo_cutting/",
                Map.of("ingredients.0.item", new JsonPrimitive("create:brass_sheet"))));
    }

    /**
     * 列车机壳原料删除规则（豁免列车门 / 列车活板门）。重载阶段与服务器启动后阶段
     * 共用同一份规则，避免两处豁免集合漂移：启动后阶段若退化为不豁免，
     * 会把重载阶段刚保留的两条配方删掉。
     */
    private static RecipeEdit trainCasingRule() {
        return new RecipeEdit.DeleteByIngredientId("列车机壳参与的配方",
                ResourceLocation.parse("create:railway_casing"), CASING_EXEMPT_RECIPE_IDS);
    }

    private static GridRuleBuilder modify(String label, String... resultTargets) {
        return new GridRuleBuilder(label, resultTargets);
    }

    private static FieldRuleBuilder modifyFields(String label, String... recipeIds) {
        return new FieldRuleBuilder(label, recipeIds);
    }

    /**
     * 生成 {@code count} 个相同标签材料条目并拼成 JSON 数组片段（不含方括号）。
     *
     * <p>Create 的物品材料 {@code Ingredient} 不携带数量（见 3.9 段注释），
     * 表达「消耗 N 个」的唯一约定写法就是把同一标签写 N 次。</p>
     */
    private static String repeatedTagEntries(String tag, int count) {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"tag\": \"").append(tag).append("\"}");
        }
        return json.toString();
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
