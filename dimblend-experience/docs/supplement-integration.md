# 2026-11-10 补充需求：串行集成

## 当前状态与范围

2026-11-10 后续验证已完成：两个目标模块编译及 758 项 JUnit 通过；完整补充 GameTest 37/37、独立死亡正向 12/12、缺少粉末模组负向 1/1 通过。独立正向的 12 项是重复环境覆盖，不累计为新的独立用例。构建发现的两处 AttachmentType 重载歧义及死亡测试初生无敌夹具问题均已修正并复测。完整日志、失败记录和测试边界见 [验证报告](supplement-validation-2026-11-10.md)。尚未部署或进行完整整合包客户端实机验收。

下文“本单元未执行”“等待用户通知”等文字保留共享集成单元交接时的历史状态；后续自动化结果以验证报告为准。

已接入指南针、牵引效率显示源、Ponder、浮空材质加载顺序及指南针 GameTest 门控。玩偶单元 codex-a8125083、死亡/车钩单元 codex-045d8f0e 与 core 岩浆单元均已由主编排确认 native final，按各自交接文档串行接入。原第一组图腾/车钩/提示注册及旧功能保留。本批共享入口接线完成，交付后冻结，交主编排串行构建与验收。

仅编辑本单元授权的共享文件；上一单元独占实现已冻结。未执行 Gradle、编译、测试或启动游戏，未 stage / commit / push。本记录中的依赖与配置检查均为静态检查，不能代替织入和实机验证。

## 后续新增资源路径登记（先登记、再写入）

主编排已逐项授权：

- `src/main/resources/data/minecraft/tags/block/mineable/axe.json`：新增 16 种管理员坐垫的斧头采掘 tag；值使用 optional ID，适配 Create 缺席时不注册方块。
- `src/main/resources/data/dimblend_supplement_death/structure/empty.nbt`：死亡单元唯一所需新模板，从已有 `data/dimblend_sleep/structure/empty.nbt` 逐字节复制，不构造其它模板。
- `build/generated/supplement-test-preset/data/minecraft/worldgen/world_preset/flat.json`：后续 Gradle 任务生成的开发 GameTest 世界预设。
- `build/resources/supplementFixture/data/minecraft/worldgen/world_preset/flat.json`：后续 Gradle processSupplementFixtureResources 生成的开发资源副本。

以上均相对 `dimblend-experience/`。两个 build 下 flat.json 已获明确授权，但本单元不运行 Gradle，所以目前没有生成，也不清理任何已有工件；生成配置保留供审阅。这些开发资源不进入 main 发布 jar。

追加 core 资源（相对仓库根，主编排已授权，先登记再创建）：`dimblend/src/main/resources/data/dimblend/structure/corridor_lava_empty.nbt`。唯一用途为 core 岩浆的 3×3×3 空 GameTest 模板，对应交接 SNBT `{size:[3,3,3],palette:[{Name:"minecraft:air"}],blocks:[],entities:[]}`；不依赖 Experience 模板。

## 已完成的共享接入

- Experience `DimBlend` 构造阶段在 Create + Simurail 门控内调用 `TractionDisplaySources.register(modEventBus)`。
- 客户端 setup 在同样门控内 enqueue `PonderIndex.addPlugin(new SupplementPonderPlugin())`。
- common mixin 列表追加 `OriginCompassMixin`、`compat.simulated.NavigationCompassMixin`；插件对 `.compat.simulated.` 按 `simulated` 在场性过滤。
- 中英文加入 `dimblend_experience.display_source.traction_efficiency`，值分别为“牵引效率”和“Traction Efficiency”。原图腾/扳手/浮空桶/玩偶文案保留。
- metadata 增加可选 `aeronautics [1.3.0,)`、`ordering=AFTER`、`side=BOTH`。同 ID 的 `floating_materials/levitite.json` 因此覆盖上游；取证链路见 `supplement-navigation-physics-implementation.md`。
- `SupplementCompassGameTests` 仅在 `dimblend.supplementTests=true` 且 `dimblend`、`simulated` 在场时显式注册。测试内部缺真实维度/导航台时会失败，不直接 succeed。
- 玩偶：Create 在场时构造阶段注册 `DollKeeperSeats`（包含游戏总线监听），客户端 RegisterRenderers 事件注册 `DollKeeperSeatRenderer`。client mixin 列表只加 `DollStockKeeperRequestScreenMixin`，plugin 用既有 early-loading `isLoaded("create")` 门控。metadata 新增可选 Create `[6.0.10,) AFTER`，确保原坐垫先注册。
- 玩偶 GameTest：`dimblend.supplementTests=true` 且 Create、Sable、`kaleidoscope_doll` 同时在场时注册 8 项测试，使用既有 `dimblend_compat_batch:empty` 模板。新增斧头 tag 的 16 项均 optional，并与 16 个现成 blockstate 名逐项对齐；没有重复添加玩偶 tooltip 或其它产品文案。
- 死亡测试：`dimblend.test.supplementDeath=true` 且 `solcarrot` 存在时，有 Grilling 注册主类 12 项（含创造满包 P2 的两项回归）；缺 Grilling 注册 `BackpackTotemGameTests.WithoutPowder` 1 项。原 `ServerPlayerTotemDeathMixin`、`compat.solcarrot.FoodRecordDeathMixin` 均保留且只出现一次。
- 死亡模板已逐字节复制为登记路径，204 字节，源/目标 SHA-256 同为 `4EA8DB3E8CD72F2E69C6A5551BE3A3B9857586AEA145AD8E980B74E6BC39AA5B`。
- 岩浆：core 构造阶段一次注册 `CorridorLavaOriginals.ATTACHMENTS`，mod bus 的 `RegisterGameTestsEvent` 显式注册 `CorridorLavaSourceGameTests`（定向修正后 12 项）；common mixins 一次加入 `FlowingFluidSpreadInvoker`、`CorridorLavaChunkPyramidMixin`、`CorridorLavaChunkStatusTasksMixin`。不加自动 holder，不引入 Experience 依赖，不修改岩浆实现。`DimBlendRegistries` 无需改动，保留原注册。
- 岩浆模板已创建并独立解码核对为交接 SNBT 的结构，gzip NBT 94 字节，SHA-256 `BE0A74D613EC67C09DBE9FCBB227E4FAF2F3FED96DC7BE5AEFE0F5E4D569AA8C`。

## 显式开发测试开关

`dimblend-experience/build.gradle` 新增：

```text
-PwithSupplement -PsupplementModsDir=E:/misc/TrainTripWorld/mods
```

没有 `withSupplement` / `withSupplementDeath` 时不读取该目录，不增加本开关的运行依赖、sourceSet 或世界预设。指定开关但目录缺失、某模式匹配不到或匹配多个 jar 时配置即报错；不静默漏装。不与 `withChassis` 混用（独立 fixture）；`withSupplement` 与 `withSupplementDeath` 也互斥，前者已包含正向死亡测试。

来源目录只匹配明确白名单中的**顶层 `.jar`**，不递归，不包含 `.jar.bak-*`，不把整包约 187 个模组加入 dev run。

### 已核实的 16 个外部 jar

| modId | 安装版本 / 用途 |
|---|---|
| solcarrot | 1.16.6，图腾食物记录路径 |
| kaleidoscope_grilling | 1.1.1，图腾粉末 |
| kaleidoscope_cookery | 1.4.1，烟火的 required 依赖 |
| kaleidoscope_doll | 1.4.1，玩偶 |
| aeronautics_bundled | 1.3.0，原安装 bundled jar |
| simurail | 安装文件 `simurail-1.21.1-0.0.0-a+ecd2dd3.jar` |
| twilightforest | 4.8.3345，core required |
| railways | 0.2.0-beta.2，core required |
| deeperdarker | 1.4.1，core required |
| aether | 1.5.10，core required |
| eternal_starlight | 0.9.0，core required |
| voidscape | 1.9.588，core required |
| biomesoplenty | 21.1.0.13，core required |
| glitchcore | 2.1.0.0，BoP required |
| terrablender | 4.1.0.8，BoP required |
| owo | 0.12.15.5-beta.1+1.21，Aether 内嵌 Accessories 的 required 依赖 |

递归读取所选 jar 的 `META-INF/neoforge.mods.toml` 及 `META-INF/jarjar/metadata.json`：

- bundled 内含 `simulated`、`aeronautics`、`offroad`，没有重复选取三个独立 jar。
- Aether 内含 `cumulus_menus`、`nitrogen_internals`、`accessories`；owo 内含 `fabric_api_base`，均不再添加独立副本。
- 选中 root/nested 共 23 个 modId，无重复；在既有 Minecraft、NeoForge、Create、Flywheel、Sable 基线之外没有未满足的 required modId。metadata 的 optional 依赖（如 KubeJS）不加入。
- core 本身通过 `localRuntime(project(':dimblend')) { transitive = false }` 提供。关闭传递引入，避免 core 旧有机器路径、维度 jar 与当前白名单重叠，或重复拉入 Create。
- Create、Ponder、Flywheel、Registrate、Architectury、Sable 与 companion 复用 Experience 的既有依赖；没有再从 mods 目录选择它们。Sable 的 Veil/companion 保留上游 nested 解析。
- `dimblend-blocks` 继续沿用已有项目 localRuntime；白名单不包含任何已安装的 `dimblend*.jar`，所以不会加载旧发布副本。
- SHA-256 比对确认仓库 `dimblend/libs/` 中的 Sable、Simurail 与本次已安装副本逐字节一致。Simurail 内部 metadata 的 version 字符串为 `0.0.0-a+e28e2c9`，与文件名后缀不同；此处按实际同字节 jar 取证，不修改版本声明。

白名单是针对当前安装版本查证的闭包，不是通用模组发现器；以后升级仍由 FML 检查版本范围，升级后需重新审计 metadata。

### 开发世界与发布隔离

完整开关设置 `dimblend.supplementTests=true`、`dimblend.test.supplementDeath=true`，并允许用 `-PsupplementTestNamespaces=<逗号分隔值>` 定向选择 namespace。当前默认 `dimblend_chassis,dimblend_compat_batch,dimblend_supplement_death,dimblend`，分别对应指南针、玩偶、死亡正向、core 岩浆测试。

开发目录各自独立：

- GameTestServer：`dimblend-experience/build/supplement-gametest-run`
- 开发客户端：`dimblend-experience/build/supplement-client-run`
- 开发服务器：`dimblend-experience/build/supplement-server-run`
- 独立死亡正向：`dimblend-experience/build/supplement-death-gametest-run`
- 独立死亡无粉末负向：`dimblend-experience/build/supplement-death-no-powder-gametest-run`

没有删除或重置任何世界的新增任务。

`generateSupplementTestPreset` 只生成开发 `minecraft:flat` world preset：原版 flat 主世界、原版下界/末地、真实 `dimblend:rotating` 类型和生成器（只保留前四个原版 delegate）。这是沿用已存在 chassis fixture 的做法：`GameTestServer.java:98` 起明确 bake `WorldPresets.FLAT`，单有普通 dimension JSON 不足以给测试服务器加入旋转维度；Twilight 的晚注册 terrain 数据也不能嵌入这份预设。

生成资源经独立 `supplementFixture` sourceSet 加入开发 mod roots，**不加入 main resources 或主 jar**。`ModModel.sourceSet(SourceSet)` 已由本机 MDG 2.0.147 API 核实。该四 delegate fixture 只用于特定方法/织入路径验证，不是完整九区域地形验收。

独立 `-PwithSupplementDeath` 仅选取 S.O.L、Grilling、Cookery 三个安装 jar，不加载 core 或完整名单，不生成新世界预设；`-PsupplementNoPowder` 真正只加载 S.O.L，省略 Grilling 和 Cookery。两次均使用 `dimblend_supplement_death` namespace，并复用默认 Sable 开发基线。单独指定 `supplementNoPowder` 而没有 `withSupplementDeath` 会报错。

## 精确运行指令（交给主编排串行执行，本单元未执行）

PowerShell 先指定 JDK 21：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
```

纯数学测试不需要补充运行栈：

```powershell
.\gradlew.bat :dimblend-experience:test --tests dimblend.experience.compat.simurail.TractionEfficiencyTest
```

当前指南针所在 namespace：

```powershell
.\gradlew.bat :dimblend-experience:runGameTestServer -PwithSupplement -PsupplementModsDir=E:/misc/TrainTripWorld/mods -PsupplementTestNamespaces=dimblend_chassis
```

`dimblend_chassis` 是现有共享 namespace，因此其中其他已注册的测试也会被选择；此指令不声称只运行一个方法。

玩偶的 8 个测试（使用完整兼容 runtime，选择其已有 namespace）：

```powershell
.\gradlew.bat :dimblend-experience:runGameTestServer -PwithSupplement -PsupplementModsDir=E:/misc/TrainTripWorld/mods -PsupplementTestNamespaces=dimblend_compat_batch
```

死亡正向 / 无粉末负向分别独立运行，真正切换模组在场性：

```powershell
.\gradlew.bat :dimblend-experience:runGameTestServer -PwithSupplementDeath -PsupplementModsDir=E:/misc/TrainTripWorld/mods
.\gradlew.bat :dimblend-experience:runGameTestServer -PwithSupplementDeath -PsupplementNoPowder -PsupplementModsDir=E:/misc/TrainTripWorld/mods
```

车钩区域数学测试：

```powershell
.\gradlew.bat :dimblend:test --tests dimblend.worldgen.SupplementSurfaceLaneMathTest
```

世界侧车钩投影、跨区域持续、无近邻取消与缺 core 的类加载隔离仍按死亡/车钩单元交接文档在整合包实际验证；本次不把数学测试称为这些路径的验收。

岩浆数学测试及 core 岩浆 GameTest（`corridor_lava` batch，12 项）：

```powershell
.\gradlew.bat :dimblend:test --tests dimblend.fluid.NetherLavaFlowTest --tests dimblend.worldgen.CorridorLavaSourcesTest
.\gradlew.bat :dimblend-experience:runGameTestServer -PwithSupplement -PsupplementModsDir=E:/misc/TrainTripWorld/mods -PsupplementTestNamespaces=dimblend
```

第二条由 Experience 开发运行配置提供已审计的 core 运行依赖；测试类、attachment、模板全部归 core，不依赖 Experience 游戏逻辑。选取 namespace 会选择其中所有已注册测试，不声称只运行一个方法。完整入口可省略 `supplementTestNamespaces` 参数，使用上述四个 namespace。

开发客户端供主编排做特定交互调试（仍不能替代 TrainTripWorld 实机验收）：

```powershell
.\gradlew.bat :dimblend-experience:runClient -PwithSupplement -PsupplementModsDir=E:/misc/TrainTripWorld/mods -PsupplementTestNamespaces=dimblend_chassis
```

NeoForge 21.1.249 `GameTestHooks.isGametestEnabled()` 第一项为 `!FMLLoader.isProduction()`；`isGametestServer()` 同样要求非 production。**给 production 客户端加 enableGameTest JVM 参数不是可用测试方案**。开发任务验证织入路径，最终仍要把正式 jar 放进 TrainTripWorld 走对应功能路径。

## 岩浆阶段成本与后续验收

按 `dimblend/docs/supplement-lava-implementation.md` 接入全部精确共享入口。阶段 mixin 对所有维度的新生成扩大 FEATURES 依赖范围，具体扫描/写入仍限定 rotating；独立静态复核已确认该全局代价，尚无实际成本测量。本单元没有改变或背书其实现。需后续验证阶段织入、attachment 序列化、跨 chunk 晚到源与真实新地形；静态检查不能确认这些行为，也不能确认性能可接受。

## 当前验证证据

用户已明确要求先完成静态工作，构建和实机都等其通知；以下运行命令尚未执行。后续 P2 修正未改变共享注册接口：死亡粉末改用真实容量插入，岩浆删除早期 FEATURES 调用、统一由稳定最终阶段处理。两项修正的精确证据与验收状态见各实现说明。

两项修正均已完成独立定向静态复核，原 P2 在静态层面关闭；未发现新确定缺陷。岩浆用例没有执行完整 FEATURES→最终阶段链或真实 BasaltPillarFeature，12 项 GameTest 尚未运行。共享接入静态复核未发现确定缺陷，实际任务图、织入及整合包行为均待用户通知后验收。

已静态核对 16 个安装模式均恰好匹配一个文件；递归 metadata required 闭包完整；Sable/Simurail 哈希与已安装副本一致。待主编排运行 Gradle 配置/编译、JUnit、开发 GameTest 和完整整合包实机；本单元没有运行结果可引用。

全批入口接完后的静态核对通过：两个 mixin JSON 和中英文 JSON 无重复键；common/client mixin 无重复且对应源码文件均存在；相对 HEAD 的全部旧 mixin 仍各出现一次，旧语言键和值全部保留。Create/Aeronautics dependency 各一次且均 optional AFTER BOTH；16 个 optional axe ID 与 blockstate 逐项对应。core attachment、测试 listener、测试类均只注册一次；岩浆模板解码得到 size/palette/blocks/entities 精确内容，死亡模板与来源逐字节相同。授权跟踪文件 `git diff --check` 无报错；新增文档/tag 做 UTF-8 与尾随空白核对。两个授权生成路径仍不存在，没有执行生成任务。

本串行单元共交付 14 个有改动的共享/追加路径：Experience 的 `build.gradle`、`DimBlend.java`、`client/DimBlendClient.java`、`mixin/DimBlendMixinPlugin.java`、mixin JSON、两份语言 JSON、metadata 模板、本说明、axe tag、死亡 empty 模板；core 的 `DimBlend.java`、mixin JSON、岩浆 empty 模板。原独占实现、其它实现者文件及 core `DimBlendRegistries.java` 本单元未改动。全部改动保持未 stage / commit / push，交付即冻结，等待主编排串行构建。
