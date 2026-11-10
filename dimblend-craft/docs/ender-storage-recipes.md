# EnderStorage 标准合成改动（2026-11-11 批次）

本文件记录 `dimblend_craft` 对 **EnderStorage 标准合成**的两条运行时规则：证据、实现边界、
单测覆盖，以及尚未执行的实机验证流程。规则实现在 `RecipeEditRules#edits()` 的
「3.20 EnderStorage 标准合成」段（`RecipeEditRules.java:455` 与 `:461`）。

> 状态：**代码已实现并冻结，配方尚未在游戏内验证。** 单测（反射调用生产
> `RecipeJsonEditor.writePath`）只证明规则路径命中真实 JSON 的哪个 key 符号、展开后的布局是什么；
> `RecipeJsonEditor` 的完整 serializer Codec 往返与游戏内实际合成必须在进档后确认，
> 详见第 6 节。不要把「单测绿」当成「配方已生效」或 serializer 保真证明。

## 1. 需求

只改 EnderStorage 的两个标准合成，其余一律不动：

| 配方 ID | 改动 | 保持不变 |
|---|---|---|
| `enderstorage:ender_tank` | 末影珍珠位（`key.P`）→ `voidscape:machine_core`（虚空核心） | 四角烈焰棒、`C` 炼药锅、`O` 黑曜石、`W` 羊毛、pattern、产物与数量 |
| `enderstorage:ender_chest` | 四角烈焰棒（`key.B`，共 4 格）→ `minecraft:blaze_powder` | 末影珍珠位、`C` 木箱标签、`O` 黑曜石、`W` 羊毛、pattern、产物与数量 |

明确不做：水缸四角烈焰棒不动；箱子末影珍珠不动；不改其它材料、颜色行为、serializer、
产物；`enderstorage:recolour_ender_tank` / `recolour_ender_chest` 两条染色配方必须保留。

## 2. 取证

### 2.1 实际 jar 与条目

- jar：`E:\misc\TrainTripWorld\mods\EnderStorage-1.21.1-2.13.0.191.jar`
- 配方条目（6 条）：
  `data/enderstorage/recipe/ender_tank.json`、`ender_chest.json`、`ender_pouch.json`、
  `recolour_ender_tank.json`、`recolour_ender_chest.json`、`recolour_ender_pouch.json`
- 目标物品：`E:\misc\TrainTripWorld\mods\Voidscape-1.21.1-1.9.588.jar` 的
  `assets/voidscape/lang/zh_cn.json` 中 `"block.voidscape.machine_core": "虚空核心"`。

两个标准合成的原始 JSON（字段与值照抄，仅缩进整理）：

```json
{
  "type": "enderstorage:create_recipe",
  "key": {
    "B": { "item": "minecraft:blaze_rod" },
    "C": { "item": "minecraft:cauldron" },
    "O": { "tag": "c:obsidians" },
    "P": { "tag": "c:ender_pearls" },
    "W": { "tag": "c:wools" }
  },
  "pattern": [ "BWB", "OCO", "BPB" ],
  "result": { "count": 1, "id": "enderstorage:ender_tank" }
}
```

```json
{
  "type": "enderstorage:create_recipe",
  "key": {
    "B": { "item": "minecraft:blaze_rod" },
    "C": { "tag": "c:chests/wooden" },
    "O": { "tag": "c:obsidians" },
    "P": { "tag": "c:ender_pearls" },
    "W": { "tag": "c:wools" }
  },
  "pattern": [ "BWB", "OCO", "BPB" ],
  "result": { "count": 1, "id": "enderstorage:ender_chest" }
}
```

染色配方只有 `type` 与 `result`，与标准合成共享同一个输出物品：

```json
{ "type": "enderstorage:recolour_recipe", "result": { "count": 1, "id": "enderstorage:ender_tank" } }
{ "type": "enderstorage:recolour_recipe", "result": { "count": 1, "id": "enderstorage:ender_chest" } }
```

### 2.2 类与 Codec（CFR 反编译核实）

`codechicken.enderstorage.recipe.CreateRecipe`：

- `extends ShapedRecipe`，构造函数是 `CreateRecipe(String group, ShapedRecipePattern pattern, ItemStack result)`。
- `assemble` 扫描合成输入里的羊毛，取颜色生成 `Frequency(colour, colour, colour)` 并写进产物
  （所以同一个配方能做出不同颜色的水缸/箱子）。
- `getSerializer()` 返回专用 `EnderStorageModContent.CREATE_RECIPE_SERIALIZER`。
- 内部 `Serializer.CODEC` 由 `RecordCodecBuilder` 组合：
  `Codec.STRING.optionalFieldOf("group","")` + `ShapedRecipePattern.MAP_CODEC` + `ItemStack.STRICT_CODEC`。
  即编码/解码的 JSON 形状是 `group? / key / pattern / result`，材料在 `key` 符号表里。

原版 `ShapedRecipePattern`（`E:\misc\mcsrc-1.21.1\net\minecraft\world\item\crafting\ShapedRecipePattern.java`）：

- `Data.MAP_CODEC` = `strictUnboundedMap(SYMBOL_CODEC, Ingredient.CODEC_NONEMPTY).fieldOf("key")`
  + `PATTERN_CODEC.fieldOf("pattern")`（L244-250）。
- 符号键是单字符字符串（L237-243），所以 `key.P` / `key.B` 是合法 JSON 路径。
- `MAP_CODEC` 的编码走 `data`（L24-28）；从 JSON 解码出来的 pattern 一定带 `Optional.of(Data)`
  （L89）。这是「编码 → 改 key → 解码可保留 `CreateRecipe` 子类」的代码路径推论；
  实际往返是否成立只由实机日志确认，见第 6 节。

### 2.3 布局展开（行优先，3×3）

`pattern = BWB / OCO / BPB`，展开后两个配方共用同一张符号表位置：

| 槽位 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
|---|---|---|---|---|---|---|---|---|---|
| 符号 | B | W | B | O | C | O | B | P | B |
| tank 原始 | 烈焰棒 | 羊毛标签 | 烈焰棒 | 黑曜石标签 | 炼药锅 | 黑曜石标签 | 烈焰棒 | 末影珍珠标签 | 烈焰棒 |
| tank 改后 | 烈焰棒 | 羊毛标签 | 烈焰棒 | 黑曜石标签 | 炼药锅 | 黑曜石标签 | 烈焰棒 | **虚空核心** | 烈焰棒 |
| chest 原始 | 烈焰棒 | 羊毛标签 | 烈焰棒 | 黑曜石标签 | 木箱标签 | 黑曜石标签 | 烈焰棒 | 末影珍珠标签 | 烈焰棒 |
| chest 改后 | **烈焰粉** | 羊毛标签 | **烈焰粉** | 黑曜石标签 | 木箱标签 | 黑曜石标签 | **烈焰粉** | 末影珍珠标签 | **烈焰粉** |

因此改 `key.B` 一次就会覆盖箱子的 4 个角；改 `key.P` 只覆盖水缸的 1 个末影珍珠位。

## 3. 实现

两条规则都是既有的 `RecipeEdit.ModifyRecipeFields`（精确配方 ID + 点号字段路径），
执行器在重载阶段调用 `RecipeJsonEditor.edit`：用配方**自己的** serializer Codec 编码成 JSON，
按路径写入，再用同一 Codec 解码。它不会重建普通 `ShapedRecipe`，所以：

- 配方仍是 `CreateRecipe`，羊毛颜色 → Frequency 的 `assemble` 行为保留；
- 专用 serializer（`enderstorage:create_recipe`）保留；
- 只改 `key` 下的一个符号，`pattern`、`result`、其它 key 与所有未列出的字段原样。

规则名与字段（`RecipeEditRules.java` 3.20 段）：

| 规则名 | 配方 ID | 字段 | 值 |
|---|---|---|---|
| 末影水缸珍珠改虚空核心 | `enderstorage:ender_tank` | `key.P` | `{"item":"voidscape:machine_core"}` |
| 末影箱四角烈焰棒改烈焰粉 | `enderstorage:ender_chest` | `key.B` | `{"item":"minecraft:blaze_powder"}` |

实现时被排除的三种做法，以及原因：

1. **`ModifyShapedGrid`（网格编辑）**：`ShapedGridEditor` 用
   `new ShapedRecipe(...)` 重建配方，会把 `CreateRecipe` 降级成普通 `ShapedRecipe`，
   丢掉颜色 → Frequency 的 `assemble` 行为与专用 serializer。**明确禁止**。
2. **`ReplaceIngredient`（全局材料替换）**：它扫描全部配方，末影珍珠与烈焰棒是全包常见材料，
   会波及大量无关配方。**明确禁止**。
3. **按产物 `DeleteByResultId` / `ModifyShapedGrid`**：染色配方与标准合成共享同一个输出物品
   `enderstorage:ender_tank` / `enderstorage:ender_chest`，按产物匹配会连带删除或改写染色配方。
   `ReColourRecipe` 没有 `key`/`pattern`，一旦误命中，`key.P`/`key.B` 路径写入会失败并保留原配方，
   但正确做法是根本不要命中。

## 4. 单测钉住什么（以及不钉什么）

`RecipeEditRulesTest` 新增三组用例。fixture 逐字取自上述 jar 条目；测试通过**反射调用生产
`RecipeJsonEditor.writePath`**（不复制路径算法、不扩大产品 API、不改共享 editor）把规则字段编辑
写入 fixture 的深拷贝，再按 `pattern + key` 展开成 9 个槽位：

1. `enderTankPearlSlotIsRewrittenToVoidCoreKeepingBlazeRodCorners`
   - fixture 原布局：四角烈焰棒、槽位 8 是 `c:ender_pearls`、`C/O/W` 正确；
   - 应用规则后：只有槽位 8 变成 `voidscape:machine_core`，四角仍是烈焰棒，
     `C/O/W` 与 `result` 不变；
   - `type` 仍是 `enderstorage:create_recipe`，key 符号集合不变（不新增未使用的符号）。
2. `enderChestBlazeRodCornersAreRewrittenToBlazePowderKeepingPearl`
   - fixture 原布局：四角烈焰棒、槽位 8 是 `c:ender_pearls`、`C` 是 `c:chests/wooden`；
   - 应用规则后：槽位 1/3/7/9 全部变烈焰粉，槽位 8 仍是末影珍珠，`C/O/W` 与 `result` 不变；
     key 符号集合不变。
3. `enderStorageRecolourRecipesAreNeverTargeted`
   - 两条新规则都不命中 `recolour_ender_tank` / `recolour_ender_chest`；
   - 两条染色 JSON 仍是 `enderstorage:recolour_recipe`，输出与标准合成相同；
   - 在 `edits()` / `ingredientEdits()` / `lateEdits()` 三个阶段里，
     没有任何按输出物品或配方 ID 命中末影水缸/末影箱（及其染色配方）的删除、网格、产物替换或排序规则。

既有的两条不变量（`noRuleTouchesARecipeAnotherRuleDeletes`、黄铜/全局替换扫描）自动覆盖新规则：
新规则是 `ModifyRecipeFields`，不会被别的删除规则命中，也没有使用 `ReplaceIngredient`。

**测试边界**：以上只证明「规则路径命中真实 JSON 的 key 符号」与「改写后的 9 格布局」。
`RecipeJsonEditor.edit` 的完整 serializer Codec 往返（`encode → 改 → decode`）需要游戏注册表与
真实配方实例，离线 JVM 跑不了，因此单测**不是** serializer 保真证明；`CreateRecipe` 子类、
颜色 `assemble` 行为与专用 serializer 是否真的保住，只由第 6 节的实机 `[配方]` 日志与合成台验证。

## 5. 已知边界与风险

- **Voidscape 缺失时**：`voidscape:machine_core` 无法解析，`RecipeJsonEditor` 解码失败，
  该配方保留原样并输出
  `[配方] 规则「末影水缸珍珠改虚空核心」无法应用于配方 enderstorage:ender_tank ...`。
  这是 fail-closed，不会产出坏配方；本整合包装了 Voidscape 1.9.588，正常路径不触发。
- **上游改配方 ID / pattern**：规则按精确 ID 命中，未命中会在重载日志里输出
  `[配方] 规则「...」未命中任何配方`（非防御性 WARN）。若上游把材料从 `key` 改成别的形状，
  字段路径会失败并保留原配方 + ERROR，需要重新取证。
- **语义变化**：水缸的珍珠位原来是 `c:ender_pearls` 标签（任何末影珍珠），改后只接受
  `voidscape:machine_core`；这是需求本身的要求，不是副作用。
- 规则只改数据包重载后的内存配方表，不改上游 jar；重启或 `/reload` 后重新应用，幂等。

## 6. 实机验证流程（待执行）

前置：构建 `dimblend_craft-0.1.0.jar`，按 `dimblend-dev` skill §5 只替换
`TrainTripWorld\mods\` 里本 mod 的旧 jar（先备份、记录 SHA-256），启动
`E:\misc\TrainTripWorld\tmp-yield-run.bat` 并**真正进入世界**（配方规则只在进档/重载时应用，
停在标题画面看不到执行日志）。

1. **重载日志**：进档后查 `logs\latest.log`，必须出现两条
   `[配方] 修改 enderstorage:ender_tank：key.P -> {"item":"voidscape:machine_core"}（规则：末影水缸珍珠改虚空核心）`
   与 `[配方] 修改 enderstorage:ender_chest：key.B -> {"item":"minecraft:blaze_powder"}（规则：末影箱四角烈焰棒改烈焰粉）`。
   同轮应有 `[配方] 本轮编辑完成：删除 … 条，修改 … 条，排序 … 条`，且修改计数包含这两条。
2. **失败判据**：若出现
   `[配方] 规则「末影水缸珍珠改虚空核心」无法应用于配方 enderstorage:ender_tank（JSON 往返编辑失败…）`
   或同类 ERROR，说明 Codec 往返/路径假设不成立，配方仍是旧材料；记录日志并回到本文件第 2 节重新取证，
   不要用「JEI 看起来没变」猜。
3. **JEI / 配方书**：水缸显示 4 烈焰棒 + 1 羊毛 + 2 黑曜石 + 1 炼药锅 + 1 虚空核心；
   箱子显示 4 烈焰粉 + 1 羊毛 + 2 黑曜石 + 1 木箱 + 1 末影珍珠。
   同时确认染色配方仍在（JEI 可能不显示 special 配方，以实际合成台为准）。
4. **实际合成台**：
   - 水缸：按上述材料摆出，产出 1 个末影水缸；把羊毛换成红色羊毛，产物频率颜色应为红色
     （证明 `CreateRecipe#assemble` 未被网格编辑破坏）。
   - 箱子：四角烈焰粉，产出 1 个末影箱；红色羊毛同样应得到红色频率。
   - 反例：水缸珍珠位放末影珍珠、箱子四角放烈焰棒，都应**不能**合成（旧材料路径已关闭）。
5. **染色配方**：用任意染料 + 已合成的末影水缸/末影箱在 3×3 合成台里染色，仍能改变颜色、
   产物数量为 1；确认 `recolour_ender_tank` / `recolour_ender_chest` 没有被误删。
6. **回归**：确认 `enderstorage:ender_pouch`（3.18.4，`key.P` 改虚空核心）行为不变；
   同一套规则表下的其它配方没有新增未命中告警。

只有第 1、4、5 步都通过，才能把本项标记为「已实机验证」。单测数字与日志原文应写进交付说明。
