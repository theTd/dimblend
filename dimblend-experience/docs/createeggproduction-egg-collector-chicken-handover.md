# 交接：Create:Egg Production 产蛋器内嵌鸡在 Sable 上不显示

> 写给接手人。本文记录问题现象、已证实的机制、三轮实机失败的完整证据、
> 推荐修法（按渲染层拆分模型）与全部环境操作约定。
> 原 BER 兜底代码（分支 `exp-createegg-production`，基于 master `8643b4c`）三轮实机均未通过；
> **v6 按渲染层拆分模型已于 2026-10-15 实机验收通过并合入 master**（见 §0）。

## 0. 接手后进展（v6，2026-10-15）

- 已按 §4 推荐修法实现「按渲染层拆分模型」：`compat/createeggproduction/EggCollectorModelInstaller`
  （`ModelEvent.ModifyBakingResult` 替换全部方块状态变体）+ `EggCollectorLayerSplitModel`
  （鸡 → cutout，其余 → translucent，`renderType == null` 原样）。
- §5/§7.1 的 BER 兜底整套已删除：3 个 mixin、`EggCollectorChickenRenderer`、mixin plugin 规则、
  mixins.json 条目、`createegg-production` compileOnly 依赖均已撤回（新实现不引用 CEP 类）。
- 落地前补核实：`texture4` alpha 仅 0/255（cutout 无观感差）；Sable 单块路径
  `getRenderLayers` → `model.getRenderTypes`、区块化路径走原版 `rebuildSectionSync`
  （NeoForge `SectionCompiler` 逐层取 quad）、Sodium 下 `drawChunkLayer(RenderType)` 逐层调用。
- 构建 + 单测全绿。jar 暂存为实例 `mods\dimblend_experience-1.0.0.jar.eggfix-v6-layersplit`
  （非 .jar 后缀不加载）；**未替换活动 jar**——活动 jar 是另一会话部署的含 TrainDeadman 功能的构建。
- §3 的「BE 不分发」矛盾不再阻塞本修法（新路径不经 BER），未继续追查。
- 2026-10-15 实机验收通过（spec G7 实机项）。§1-§9 保留为前三轮失败复盘与机制取证。

## 1. 任务与现象

- 用户原始需求：Sable（子层级/嵌套维度 mod）上放的产蛋器
  （Create:Egg Production 1.1.0，`createeggproduction:egg_collector_block`）
  **玻璃罩里面的鸡不显示**；**孵化器正常，只有产蛋器有问题**。要求在我们 mod
  （dimblend-experience）里用 mixin 修。
- 三轮实机结果（2026-10-15，TrainTripWorld 实例）：
  - v1（谓词翻转 + BER 兜底 + 单块重排 + `RenderSystem.getShader()`）：鸡有时全看不到、有时只有一个面。
  - v2（在 v1 基础上把 shader 实例改对，并把单块重排改成按方向分批）：同上，看不到。
  - v3-v5（v1 重排 + shader 修正 + 取证探针）：仍看不到。
- **注意**：v2 里我一度把单块重排改成「非玻璃留方向批、玻璃汇总 null 批」，
  该改动经独立复核证明是**错的**（模型全部 face 无 `cullface`，quad 全在 null 批，
  会丢光非玻璃几何），已回退，不要复活这个方案。

## 2. 已证实的机制（全部经字节码/资源文件核实）

### 2.1 鸡是烤在方块模型里的静态几何

- 模型 `createeggproduction:models/block/egg_collector_block.json`（jar 内可解）：
  - 元素 0-4 底座（`block/texture1`）、元素 5 玻璃罩（`block/textureglass`）、
    元素 6-11 鸡（`block/texture4`，Blockbench 组名 `chi`）、元素 12-28 草（`block/texture5`）。
  - 玻璃组在模型元素序里**排在鸡组前面**。
- `CreateEggProduction#lambda$clientSetup$1` 把产蛋器和空产蛋器都注册为
  `RenderType.translucent()` 渲染层 → 整块模型走半透明层。
- 模型所有 face **均无 `cullface`** → 全部 quad 走非剔除（`side == null`）批次，
  6 个方向批次全空。这是 v2 翻车的根因，也是理解后续渲染顺序的关键。
- 孵化器模型（`incubator.json`）无玻璃罩、不透明，所以不受影响。
- 空产蛋器（`empty_egg_collector_block`）是另一个模型/另一个方块，与本案无关。
- `EggCollectorVisual`（flywheel visual）只画输入轴；`EggCollectorRenderer.renderSafe`
  基本为空操作（flywheel 支持时直接 return）。**鸡只能靠方块模型出来**。

### 2.2 为什么主世界正常、Sable 子层级里鸡消失

- translucent 绘制写深度。原版区块编译会对 translucent 做 per-section 按摄像机
  距离排序（`SectionCompiler` 里 `meshdata.sortQuads`），远的先画近的后画，
  所以主世界里鸡先写深度、近处玻璃后混合，鸡可见。
- Sable 子层级渲染路径（单块路径 `renderAfterSections`、区块化路径
  `renderChunkedSubLevel`）**不做逐 quad 排序**，按模型元素序直出：
  玻璃先写深度 → 鸡被深度测试剔除 → 不显示。

### 2.3 Sable 渲染路径事实（实例实配）

- 实例装 **Sodium 0.8.13** → `SubLevelRenderer.SelectedRenderer` 选中
  **SODIUM_REACHAROUND**（`ReachAroundSubLevelRenderDispatcher`，继承
  `VanillaSubLevelRenderDispatcher`）。不是 vanilla dispatcher，也不是 Fancy。
- ReachAround 自建 vanilla `SectionRenderDispatcher` 编译子层级区块，
  单块（1×1×1 plot）用 `VanillaSingleSubLevelRenderData`，多块用
  `VanillaChunkedSubLevelRenderData`。
- 单块路径 `renderAfterSections` 的注入点在 `LevelRenderer#renderLevel` 里
  `constantAmbientLight()` 处 = **cutout 区块层之后、实体/BE 段之前**（不是帧末）。
  单块模型 `tesselateBlock` → `ModelBlockRenderer.tesselateWithoutAO(checkSides=true)`，
  逐方向批 + 最后 null 批（本模型全 null 批）。
- BE 段在 translucent 区块层**之前**（与原版一致）。Sable 的 BE 分发
  `VanillaSubLevelBlockEntityRenderer.renderSingleBE` 恒用
  `renderBuffers.bufferSource()`（immediate）。
- flywheel 对 vanilla BE 的 skip 咨询在**区块编译期**：`tryAddBlockEntity`
  先 `queueAdd` 视觉再返回 `skipVanillaRender(be)` 谓词值；
  `SectionCompilerMixin` 据此决定是否取消 `handleBlockEntity`。
  `handleBlockEntity` 只在 BE 有注册 renderer 时把 BE 放进
  `results.blockEntities`（产蛋器有注册 renderer，且
  `KineticBlockEntityRenderer` 无 `shouldRenderOffScreen` 覆写，默认 false）。

## 3. 三轮实机的探针证据（最重要的一手材料）

临时探针（已按 §7 清理，代码可照 §7.4 恢复）打出的关键事实：

| 探针 | 结果 | 含义 |
|---|---|---|
| `predicateFlip`（谓词在子层级内翻 false） | **多次触发**（Worker 编译线程，如 (20481058,129,20491272)） | 产蛋器 BE 确实在子层级内；`CreateEggProductionMixin` 已应用且生效 |
| `handleBE`（`SectionCompiler.handleBlockEntity` 遇到产蛋器） | **触发**，`before=0` | 谓词翻 false 后产蛋器**确实进入了区块编译的可渲染 BE 列表** |
| `renderSingleBE:EggCollectorBlockEntity`（Sable BE 分发到产蛋器） | **0 次** | **产蛋器从未经 vanilla BER 分发**——BER 兜底路径根本没机会跑 |
| `renderSingleBE:其它类`（床/泵/罐/箱/名牌/复制猫等） | 每帧成百上千次 | Sable 的 BE 分发本身工作正常，大量其它 BE 都渲染 |
| `berHead`（`EggCollectorRenderer.renderSafe` HEAD） | **0 次** | 同上一行：vanilla BER 从未被调用 |
| `singleWrap` / `singleAny`（单块模型重排） | **0 次** | 用户场景**没有走单块路径**，走的是区块化路径 |
| `berQuads`（手动鸡绘制） | **0 次** | 同 berHead |

**核心未解矛盾**：编译期 `handleBlockEntity` 已把产蛋器放进可渲染列表，
但 `renderSingleBE` 从未轮到它，而其它 BE 都正常分发。也就是说「BER 兜底」
这条修复路线**从未真正生效过**，v1/v2/v3 的实机失败主要是这条路线不成立，
而不是 quad 顺序改得对不对。下一任应先查清这个矛盾，再谈修复。

头号嫌疑（按优先级）：
1. 区块化路径下，产蛋器所在 section 的 `getCompiled().getRenderableBlockEntities()`
   与编译期 `results.blockEntities` 是否同一份/是否被正确提交（Sodium 在场、
   ReachAround 自建 dispatcher，提交链与主世界不同，需实锤）。
2. 产蛋器所在 section 是否进入 BE 分发用的 `allRenderSections()`（与可见模型
   渲染是否同一组 section，可能根本不是一套列表）。
3. 用户观察到的鸡缺失是否还有第二个原因（比如鸡在网格里被剔与 BE 不分发是
   两件事的叠加）。**先定位 1/2，再决定修法**。

## 4. 推荐主修法：按渲染层拆分模型（不依赖 BER 分发）

比此前所有方案都更根本、覆盖全部路径，且不需要 BER 兜底和谓词翻转（可全删）：

**思路**：给产蛋器的烘焙模型包一层「按渲染层过滤」的包装模型，
**鸡 quad 归 `cutout` 层、其余（底座/玻璃/草）留 `translucent` 层**。
`cutout` 层永远在 `translucent` 层之前画（原版、Sable 单块、Sable 区块化
三条路径一致），不透明鸡先写深度、半透明玻璃后混合——**天然不再需要任何
逐 quad 排序**，根因直接消除。

具体实现要点：

- 包装 `BakedModelWrapper`（NeoForge 自带）：
  - `getRenderTypes(state, rand, modelData)` 返回
    `ChunkRenderTypeSet.of(RenderType.cutout(), RenderType.translucent())`
    （NeoForge 的 `SectionCompiler` 逐层调 `renderBatched(..., renderType)`，
    单块路径 `getRenderLayers` 也直接用它）。
  - `getQuads(state, side, rand, data, renderType)`：
    `renderType == cutout()` → 只返回 `block/texture4`（鸡）quad；
    `renderType == translucent()` → 返回其余全部（含玻璃）quad；
    `renderType == null` → 原样返回全部 quad（物品栏/GUI/其它消费方不受影响）。
  - 鸡/玻璃的判定用 `quad.getSprite().contents().name()` 对比
    `createeggproduction:block/texture4` / `textureglass`（当前代码里就有现成常量）。
- 挂载方式二选一：
  1. **NeoForge `ModelEvent`（推荐）**：烘焙完成事件里把
     `createeggproduction:egg_collector_block` 全部 4 个 facing 变体换成包装模型，
     一次性替换、零逐帧分配，主世界/子层级/物品模型全路径一致。
  2. mixin `BlockRenderDispatcher#getBlockModel` 按 `BlockState` 包装
     （`compat.createeggproduction` 包内按注册名匹配，注意每帧会新建包装对象）。
- 用 `DimBlendMixinPlugin` 按 `createeggproduction` 在场过滤（NeoForge 事件方式则
  事件订阅者按 `ModList.isLoaded` 门控），保持「单独装本 mod 不崩」的横切约定。
- 注意事项：
  - 鸡贴图走 cutout 后按 alpha 0.5 截断而非半透明混合，若 `texture4` 有半透明
    像素会有轻微观感差异（鸡贴图通常不透明，需实机确认）。
  - 玻璃留在 translucent，主世界行为不变（主世界本来就能看）。
  - 此方案**不需要** `CreateEggProductionMixin`（谓词翻转）和
    `EggCollectorRendererMixin`（BER 手动画）——可整套删掉，代码量大幅收敛。
  - 若§3 的矛盾（BE 不分发）另有其因且与鸡缺失无关，本方案仍应独立生效；
    但若实机仍不显示，必须回到§3 的证据继续查，不要叠新方案。

## 5. 备选路线（若坚持保留 BER 兜底）

不推荐，但留档：先按§3 查清 BE 为何不分发，再谈 manual render。
当前 worktree 里的 `CreateEggProductionMixin`（谓词翻转）+ `EggCollectorRendererMixin`
+ `EggCollectorChickenRenderer` 已实现该路径，静态正确性经独立复核，
但实机从未触发，原因未明，不建议在其上继续加码。

## 6. 已确认正确、可复用的代码

- `EggCollectorChickenRenderer` 里 shader 处理（我修过并经独立复核确认）：
  Sable 的 `SableDynamicDirectionalShadingPreProcessor` 会给 `rendertype_cutout`
  等区块层 shader 注入 `SableEnableNormalLighting` 等 uniform。
  手动以 cutout 冲批时，**必须对 `GameRenderer.getRendertypeCutoutShader()`
  这个确切实例设值**（不能 `RenderSystem.getShader()`，那是任意先前 shader），
  只置 Java 侧值（upload=false），由 `endBatch` 内 `ShaderInstance.apply()`
  随全量 uniform 上传；画完 `setupDynamicEffects(shader, false, false)` 还原
  并 `shader.clear()`——与 Sable 自身 `renderSectionLayer`/`renderAfterSections`
  的口径一致。**原版半成品用 `RenderSystem.getShader()` 是错的，已改。**
- `ChickenFirstModel` 的「非玻璃在前、玻璃挪末尾」整表重排：对**全 null 批**的
  本模型是完整正确的单块路径修法（但用户场景不是单块路径，没帮上忙）。
- 按贴图名过滤 quad 的写法：`quad.getSprite().contents().name()` 与
  `ResourceLocation.fromNamespaceAndPath("createeggproduction", "block/texture4")`
  比对，已验证有效（编译期签名全部核对过）。

## 7. 当前 worktree 状态

### 7.1 文件清单（未 commit）

- 新增 `compat/createeggproduction/EggCollectorChickenRenderer.java`
  （含 `forSingleBlock`、`render`、两个包装模型 `ChickenOnlyModel`/`ChickenFirstModel`）
- 新增 `mixin/compat/createeggproduction/CreateEggProductionMixin.java`
- 新增 `mixin/compat/createeggproduction/EggCollectorRendererMixin.java`
- 新增 `mixin/compat/createeggproduction/SingleBlockEggCollectorModelMixin.java`
- `build.gradle`：`compileOnly "maven.modrinth:createegg-production:1.1.0"`
  （Modrinth 下载的 jar 与 `D:\Downloads\create_egg_production-1.1.0.jar`
  **sha256 逐字节一致**，已核对）
- `DimBlendMixinPlugin`：整包 `contains(".compat.createeggproduction.")` →
  `isLoaded("createeggproduction") && isLoaded("sable")`
- `dimblend_experience.mixins.json` client 数组 + 上述 3 个 mixin
- `docs/requirements-spec.md` G7 节、mixin 清单表、依赖接线、运行验证清单
- `docs/requirements.md` createegg-production 节

### 7.2 构建验证

- 命令（必须显式 JDK 21，本机默认 JDK 11）：
  `JAVA_HOME="C:/Program Files/Java/jdk-21" ./gradlew :dimblend-experience:build`
- 现状：编译 + 单测全绿（`:test` 26 个结果文件全过）。
- worktree 缺 `libs/`（gitignore）时需从主工作区复制 3 个 jar：
  `dimblend-experience/libs/{sable-neoforge-1.21.1-2.0.5_cui-modded,simurail-1.21.1-0.0.0-a+ecd2dd3,fluid-2.0.1}.jar`。

### 7.3 不要做的事

- **不要在游戏启动/运行中途直接覆盖 mods 里的 jar**：JVM 懒加载类时 zip 句柄
  失效会报 `NoClassDefFoundError`（我们已踩过一次，把用户启动中的游戏搞崩到
  模组错误屏）。**先 `mv 旧.jar 旧.jar.bak-xxx` 再 `cp 新.jar 旧名.jar`**：
  改名不改内容，运行中的游戏继续读旧文件零影响，下次启动读新文件。
- 不要复活「按方向分批拆玻璃」方案（§1 已注明是错的）。
- 不要在没有实机证据前继续叠方案，先复现并定位§3 的矛盾。

### 7.4 取证探针（如需重加）

曾用过并已删除的探针，可按需恢复：
- `EggCollectorChickenRenderer.debug(key, message)`：per-key 计数，
  前 5 次 + 每 300 次打一条 `[DBEGG:key] msg (#n)`（slf4j INFO）。
- 埋点：谓词翻转处（`predicateFlip`）、`SectionCompiler.handleBlockEntity`
  （`handleBE`）、`EggCollectorRenderer.renderSafe` HEAD（`berHead`）、
  `VanillaSubLevelBlockEntityRenderer.renderSingleBE`（按 BE 类名分键）、
  `forSingleBlock`（`singleWrap`/`singleAny`）、`render()` 的鸡 quad 数（`berQuads`）。
- 注意 `renderSingleBE` 的 handler 必须带全参
  `(BlockEntity, PoseStack, float, double, double, double, CallbackInfo)`，
  少参会 Invalid descriptor 崩启动（踩过）。
- 日志看 `E:\misc\TrainTripWorld\logs\latest.log`（当次）与
  `logs\2026-XX-XX-N.log.gz`（历史，按启动滚动）。

## 8. 实例与运行环境

- 实例：`E:\misc\TrainTripWorld`（PCL，MC 1.21.1 + NeoForge 21.1.249，
  Sodium 0.8.13、flywheel 1.0.6、Create 6.0.10、Sable 2.0.5 cui-modded、
  CEP 1.1.0、veil 4.3.2）。工程声明 NeoForge 21.1.251，小版本差暂未发现影响。
- 启动：`E:\misc\TrainTripWorld\tmp-yield-run.bat`（已配 JDK21+参数）。
  **不要**用 `gradlew runClient` 另起 dev 实例（环境不一致）。
- 实例 mods 目录里现有的备份（按时间）：
  `.bak-preeggcollector` / `-2` / `-3`（功能前基线）、`.bak-eggfix-v1`（v1）、
  `.bak-eggfix-v2`（v2，有回归勿用）、`.bak-eggfix-v3`（v1 重排+shader 修正）、
  `.bak-eggfix-v4`（探针版，有签名 bug）、`.bak-eggfix-v5-debug`（v5 探针版）。
- **当前活动 jar 已恢复为功能前基线**（`.bak-preeggcollector-3` 的副本，
  即原始 bug 状态；探针版会每 300 帧一条日志，长跑日志量大，故不留在活动位）。
- 崩溃/日志：`logs\latest.log`、`logs\debug.log`、`crash-reports\`、
  启动器 stdout `dbx-launch-stdout-eggcollector*.log`。

## 9. 独立复核结论摘要

首轮独立 subagent 全量复核（2026-10-15）核心结论：

- 实锤「模型全部 face 无 `cullface`」（`BlockModel.bakeVanilla` /
  `UnbakedGeometryHelper.bakeElements` 按 cullface 分桶，与几何朝向无关），
  据此判 v2 高危回归；**v1 整表重排对单块路径本就正确**。
- 确认 shader 实例修正（`getRendertypeCutoutShader` + upload=false）正确：
  `BufferSource.endBatch` → `RenderType.draw` → `drawWithShader` →
  `ShaderInstance.apply()` 全量上传 uniform。
- 确认 Sable BE 分发恒用 immediate buffer，「非 immediate 跳过即冲」为防御性死代码。
- 确认 lambda 注入 `lambda$clientSetup$0` 目标存在（恒 true），但属版本脆弱点：
  CEP 升级若 lambda 重编号，`required:true + defaultRequire:1` 会直接崩启动。
- 实机未解矛盾（§3）当时即指出「先取证再修」，现为后续排查主线。
