# 星光传送门结构研究报告

> 对应 `docs/generation-rules.md` 星光一节「阻止星光传送门结构生成」条目。
> 依据：`eternalstarlight-0.9.0+1.21.1+neoforge.jar`（jar 内数据/字节码）+
> 原版 1.21.1 客户端源码（`E:\misc\mcsrc-1.21.1`）。

## 一、结论摘要

「星光传送门结构」指 Eternal Starlight 的 5 个 **传送门遗迹**（portal ruins）jigsaw 结构：
`eternal_starlight:portal_ruins_{common, forest, desert, jungle, cold}`
（中文名：普通/森林/沙漠/丛林/寒冷传送门遗迹）。

遗迹内是一个**未点燃的星辉传送门框架**（凿制虚空石
`eternal_starlight:chiseled_voidstone`，框架合法性由
`ESPortalBlock$Validator` 校验），外加一个**守门人刷怪笼**
（`the_gatekeeper_spawner`）和对应地貌的装饰方块。NBT 模板（gzip）中
**不含** `starlight_portal` 方块本身——传送门是玩家事后用「先知球体」
（`orb_of_prophecy`，"可在传送门框架上使用以开启传送门"）点燃的。

## 二、传送门本体（机制背景）

- 方块 `eternal_starlight:starlight_portal`（中文：星辉传送门），
  类 `ESPortalBlock implements Portal`，轴属性 X/Z，方块实体负责动画/音效。
- 传送逻辑 `ESTeleporter`：星光维度 ↔ 主世界双向；目的地优先找已有的
  星辉传送门方块，找不到就**原地造一个**并打 `PLACE_PORTAL_TICKET` 区块票。
- 点燃方式：先知球体对框架使用 → `validateAndPlacePortal` → 填充
  `starlight_portal` 方块。

## 三、结构生成参数（以 portal_ruins_common 为例）

structure_set（5 个同构，仅 salt/群系 tag 不同）：

```json
{ "placement": { "type": "minecraft:random_spread",
                 "salt": 958853901, "separation": 30, "spacing": 36 },
  "structures": [{ "structure": "eternal_starlight:portal_ruins_common", "weight": 1 }] }
```

structure（jigsaw）：`step: surface_structures`，起始高度 absolute 0 后投影到
`WORLD_SURFACE_WG`，`terrain_adaptation: beard_thin`，`size: 1`，
`max_distance_from_center: 50`，模板池单个元素指向对应 NBT + 藤蔓 processor。

生物群系条件（tag → 原版 c 标签）：

| tag | 群系 |
|---|---|
| has_portal_ruins_common | `#c:is_plains`、`#c:is_savanna` |
| has_portal_ruins_forest | 森林类 |
| has_portal_ruins_desert | 沙漠类 |
| has_portal_ruins_jungle | 丛林类 |
| has_portal_ruins_cold | 寒冷类 |

## 四、为什么会生成在 dimblend 的世界里

1. `rotating.json` 的星光带使用 `settings: eternal_starlight:starlight`
   noise settings + `eternal_starlight:multi_noise` 生物群系源，即大量
   eternal_starlight 群系落在 dimblend 维度内。
2. 原版 1.21.1 中结构集是**全维度候选**：`ChunkGeneratorStructureState.createForNormal`
   拿全部 structure set，按群系匹配过滤。dimblend 的
   `RotatingChunkGenerator.createState` 走的就是这条路。
3. 因此 portal_ruins 会散布在所有星光带上，只有走廊禁生区
   （`OakTrackCorridor.dropBlockedStarts`）内的 start 会被丢弃。

## 五、阻止方案

**推荐：覆写生物群系 tag（与项目既有先例一致）。**
dimblend 已有 `data/aether/tags/worldgen/biome/has_large_aercloud.json`
用 `{"replace": true, "values": []}` 清空 Aether 结构群系 tag 的先例。
照此新增 5 个文件：

```
src/main/resources/data/eternal_starlight/tags/worldgen/biome/has_portal_ruins_common.json
src/main/resources/data/eternal_starlight/tags/worldgen/biome/has_portal_ruins_forest.json
src/main/resources/data/eternal_starlight/tags/worldgen/biome/has_portal_ruins_desert.json
src/main/resources/data/eternal_starlight/tags/worldgen/biome/has_portal_ruins_jungle.json
src/main/resources/data/eternal_starlight/tags/worldgen/biome/has_portal_ruins_cold.json
```

备选（等价、任选其一即可，不要叠加）：
- 覆写 5 个 `worldgen/structure_set/portal_ruins_*.json` 为
  `"structures": []`——原版 `StructureSet.DIRECT_CODEC` 用的是普通
  `listOf()`，**没有非空约束**，空列表可正常解码。
- Mixin 拦截（无必要，数据驱动足够）。

注意点：dimblend 作为 mod 数据包覆写 eternal_starlight 的数据文件，
依赖 mod 数据包排序中 dimblend 靠后（与 aether 先例相同条件，已验证可用）。

## 六、副作用与残留风险

1. **守门人 Boss 失去自然生成点（全局生效）**。守门人刷怪笼只存在于传送门
   遗迹模板中，清掉遗迹后不再自然生成守门人（星光维度唯一 Boss）。
   **注意 tag 覆写是数据包级的**：不只是 dimblend 拼接世界的星光带，**真正的
   eternal_starlight 维度里** portal_ruins 同样不再生成（守门人在原版星光
   维度也失去自然生成点）。这与项目 aether 先例（Aether 维度的大 aercloud
   被全局清掉）同类；若后续要保留 Boss 战或只按维度禁用，需另给生成途径或
   改代码层按维度过滤。当前条目目标就是清除，属预期副作用。
2. **玩家仍可能自行离开带世界**。只要拿到先知球体 + 凿制虚空石，
   手搭框架点燃即可传送到**真正的 eternal_starlight 维度**
   （`ESTeleporter` 找不到门还会在目的地造门）。若「禁止离开拼接世界」
   是硬需求，需要另行处理 `ESPortalBlock.getPortalDestination`/
   走廊通行守卫，不在本条目范围内。
3. **其它星光结构不受影响**。对 jar 内全部 21 个结构 NBT 模板的扫描确认：
   含刷怪笼方块的模板只有 `golem_forge`（星光傀儡/永冻/机械刷怪笼）和
   5 个 `portal_ruins`（守门人刷怪笼）；cursed_garden、stranghoul_den
   模板中没有任何刷怪笼方块。本改动只清空 portal_ruins 一族的群系 tag，
   `has_cursed_garden` / `has_golem_forge` / `has_stranghoul_den` 未被触碰。
