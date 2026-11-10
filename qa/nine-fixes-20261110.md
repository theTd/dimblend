# 九项修复 2026-11-10 · 单元1（整合/复核）记录

状态：**静态复核 + `dimblend` / `dimblend-experience` 单测与打包含通过**；实机首启的 MixinExtras `@Expression` 声明缺失 FATAL 已修并重打进 jar（jar 内实读 `minVersion=0.5.3`），**尚未再次实机复验**。GameTest 与游戏内一条都没跑。
下文“通过”一律指**代码路径静态自洽 + 取证核对**（或已注明的 Gradle 结果），不等于运行时验证。

## 0. 构建与测试实况（2026-11-11）

| 项 | 结果 |
|---|---|
| `:dimblend:test` | 退出码 **0**，`BUILD SUCCESSFUL in 1m 16s`；41 个测试 XML，`tests=242 failures=0 skipped=0` |
| `:dimblend:jar` | 退出码 **0**，`BUILD SUCCESSFUL in 14s`；`dimblend/build/libs/dimblend-0.1.2.jar`，626,439 B，SHA-256 `863CFC8E8E4187FBF01CDC0A94A2708A1ACB91DAA01D96C7F013A7E9FD06E4EE` |
| 构建日志 | `build/nine-fixes-logs/dimblend-test-20261110-234759.log`、`build/nine-fixes-logs/dimblend-jar-20261110-235038.log` |
| `dimblend-spyglass` | 174 个 `@Test`（静态计数）与 jar SHA-256 `3EDFE65A…7C9B87A6` 由 A 报告、哈希已核；**本轮未重跑** |
| `:dimblend-experience:test` | 退出码 **0**，`BUILD SUCCESSFUL in 1m 25s`（首次编译，含 render 4 新增 / 3 修改与车架新文件）；86 个测试 XML，`tests=539 failures=0 skipped=0`；日志 `build/nine-fixes-logs/experience-test-20261111-000456.log` |
| `:dimblend-experience:jar` | 首次 0（16s，`3C1773D5…982EC298`）；**修复 §9 后重打**：退出码 **0**，`BUILD SUCCESSFUL in 10s`（2 executed：processResources + jar）；`dimblend-experience/build/libs/dimblend_experience-1.0.0.jar`，1,712,481 B，SHA-256 `F599C215AFEE5D22216693B7D9F34EB36578B217C0C5B112EEB16EF8FFCF4F06`；jar 内 `dimblend_experience.mixins.json` 实读 `mixinextras.minVersion=0.5.3`，stockkeeper 3 common + 1 client，另含 2 个 helper 与 `DollStockKeeperGameTests` |
| GameTest / 实机 | **全部未跑**（无游戏 / GameTest token） |

本轮其他口径：

- 渲染本轮只做 cache defect 修复与 diagnostics 内部改动（`SableAnimationSpriteCache` / `SableAnimationDiagnostics`），**没有**新增粒子 / Loot instrumentation 的共享 mixin 注册。
- 车架阶段2 目前只有纯数据前置（`ChassisSkeleton/Registry/RegistryData/LaneRule/ClientTrackRule` 及 3 个测试），**没有生产物理锁**。
- `:dimblend:test` 的 242 tests 是模块全集，包含本轮新增的 Aether 编译依赖与 `AetherPlacementBanRulesTest`。
- 两个 `build.gradle` 的改动：`dimblend-experience` 本轮只动了 mixin 清单 / 插件 / `DimBlend` 注册；`dimblend` 只加了 `compileOnly "maven.modrinth:aether:K5X5qMwG"`。

## 1. 复核范围

### 1.1 dimblend-spyglass（A 的实现，未修改其文件）

通读：`StructureSelection` / `StructureMarkInput` / `MarkClickDecision` / `MarkScreenFrame` /
`StructureMarkLayer` / `CrosshairPicking` / `CrosshairDistanceLayer` / `EdgeArrowLayer` /
`EdgeArrowTarget` / `EdgeArrowGeometry` / `LabelPlacement` / `MarkLayout` / `MarkerGlyph` /
`MarkFrame` / `MarkFramePublisher` / `SpyglassClientState` / `SpyglassScopeGate` /
`structure/StructureKey` / `structure/StructureOutline` / `structure/SpyglassDistance`；
以及服务端 key 构造点 `server/StructurePredictor`、`server/StructureStartNbt`。
测试通读：`StructureSelectionTest` / `MarkClickDecisionTest` / `EdgeArrowTargetTest` /
`MarkScreenFrameTest` / `MarkLayoutTest`。

未覆盖（本轮不改、不判）：服务端扫描/预算/协议编解码细节、`SpyglassSourceScreen`、
`dimblend-spyglass/build.gradle`、mixin 插件、README 文案。

### 1.2 车架阶段1（已冻结，未修改其文件）

通读：`chassis/ChassisInteraction`、`chassis/ChassisPose`、`chassis/ChassisEvents`（含 diff）、
`chassis/ChassisSelection`（含 diff）、`compat/sable/ChassisWorldAnchorProbe`（687 行全部）、
`compat/sable/ChassisProbeCommand`、`gametest/ChassisInteractionGameTests`、
`gametest/ChassisWorldAnchorGameTests`、`test/**/chassis/ChassisInteractionTest`、
`ChassisPoseTest`、`docs/standard-train-chassis.md`（含 diff）、
`dimblend-experience/build.gradle` 的 withChassis 段。

未覆盖：`ChassisConstruction` / `StationDepotAssembly` 内部逻辑（只看了探针的调用点）、
`PhysicsBogeyAxleLockMixin`（阶段2）、阶段2 的正式锁。

## 2. 取证依据（本机）

| 依据 | 位置 / 结论 |
|---|---|
| NeoForge 补丁客户端输入 | `neoforge-21.1.249-sources.jar` → `MouseHandler.java:87` 先 `ClientHooks.onMouseButtonPre`，取消即 `return`；`:119-135` 的 `isLeftPressed` / `KeyMapping.set` / `KeyMapping.click` 全在取消点之后，故取消真的阻断 attack，且不会留下“按住”残留。 |
| 客户端右键取消后是否还发包 | 同一 jar → `MultiPlayerGameMode.java:306-309` 在 `startPrediction` 内调 `performUseItemOn`（`:317-320` 取消即返回），而 `startPrediction`（`:270-275`）**无论结果都发包**。所以客户端取消不会吞掉服务端回调。 |
| Sable 约束 API | `dimblend/libs/sable-neoforge-1.21.1-2.0.5_cui-modded.jar` javap：`GenericConstraintConfiguration(Vector3dc pos1, Vector3dc pos2, Quaterniondc o1, Quaterniondc o2, Set<ConstraintJointAxis>)`、`PhysicsConstraintHandle#remove/isValid/getJointImpulses`、`RigidBodyHandle.of(ServerSubLevel)`、`SubLevelContainer.getContainer(ServerLevel)→ServerSubLevelContainer`、`SubLevelPhysicsSystem#getPipeline`、`PhysicsPipeline#readPose/addConstraint`、`ServerSubLevel#getPlot/getRuntimeId/logicalPose/markRemoved` 全部存在且签名一致。 |
| null = 地面 的真实路径 | `E:\misc\sable\sable_rapier\...\RapierPhysicsPipeline.java:543-573`（`bodyA == null` 注释）→ `constraint/generic/RapierGenericConstraintHandle.java:27-57`（`bodyA == null ? -1 : getID(bodyA)`）。 |
| 锚点坐标系 | `PhysicsConstraintConfiguration.validateAnchors`（javap）：bodyA 为 null 时要求 **pos1 不在 plotgrid 内**，bodyB 为 SubLevel 时要求 **pos2 在 `bodyB.getPlot()` 内**。探针正是 `pos1=worldAnchor、pos2=plotAnchor`，坐标系配对正确。`SubLevelContainer#inBounds`（`E:\misc\sable\...SubLevelContainer.java:397-410`，origin=10000、plot=2^7 区块）与实测世界坐标（数百~数万）不相交。 |
| pose 空间 | `SubLevelPhysicsSystem#updatePose`（sable 源 :361-380）把 `readPose` 直接写进 `logicalPose`；`pipeline.add`（`RapierPhysicsPipeline:197-215`）用同一 pose。故 `readPose`/`logicalPose` 同为**世界系**，`enginePose.transformPosition(plotAnchor)` 得到世界锚正确。 |
| 装配锚点 | `SubLevelAssemblyHelper.java:69-123`：anchor 方块被搬到 `plot.getCenterBlock()`，pose.position = anchor - plotAnchor + COM。所以探针取 plot 中心＝装配锚点方块，落在车上，不是“空中随便一点”。 |
| 轮 pivot 空间 | `E:\misc\Create-Simurail\...PhysicsBogeyBlockEntity.java:388-395,557`：`pivotPose` 先设 plot 局部中心再 `containingPose.transformPosition` 且 `premul` → **世界系**；探针回退分支 `projectOutOfSubLevel(...)` 也是世界系，两种 source 同量纲。 |
| GameTest 运行条件 | `dimblend-experience/build.gradle:241-253`（withChassis 的 dimblend/craft/simurail/copycats/steam-n-rails）+ `:118`（dimblend-blocks，无条件 localRuntime）+ `:186`（sable）→ `ChassisEvents.available()` 的 7 个 mod 全部在场；`:275-278` 只放行 `dimblend_chassis` 命名空间。 |
| 轨道位置 | `CorridorRails#isGeneratedTrack`（:18-29）、`StarterDepot.removesTrack`（:55，`x∈[-256,-16]`）→ 测试用的 x=448、x=512 不会被起始站挖掉，z=0/y=64 是主线。 |
| 产物事实 | `dimblend-spyglass/build/libs/dimblend_spyglass-0.1.0.jar` SHA-256 = `3EDFE65AA203FBDC4C5BC4775D8C22BCF54B0CBF19F50659D3ABA2897C9B87A6`（与 A 报告一致，本单元只核对了哈希，未验证行为）；测试源码 `@Test` 计数 = 174，与 A 报告数字一致（**未执行**）。 |

## 3. spyglass 需求逐条

| 需求 | 结论 | 证据 |
|---|---|---|
| StableStructureKey 单选 | 通过 | 单一静态 `selected`，`StructureSelection.java:60-75`；同 key 取消、异 key 替换；`StructureKey(id,startChunkX,Z)` 两坐标有界（:21-35） |
| MouseButton.Pre 单次按下 | 通过 | `StructureMarkInput.java:41-81`：只在 `GLFW_PRESS` 判定，`leftDown` 锁存（:53-54）；RELEASE 只清锁不取消（:46-49）；`MarkClickDecision.java:27-35` 显式要求非重复、无 screen/overlay、鼠标已抓、正在举镜 |
| 真实绘制命中才选中 | 通过 | 命中只取 `drawn=true` 的框（`MarkScreenFrame.java:104-124`），帧必须新鲜且 GUI 尺寸一致（:78-87），`StructureMarkInput.java:63-64` 不新鲜则 `hit=-1` |
| 取消原 attack | 通过（源码级） | `StructureMarkInput.java:80` `setCanceled(true)`；NeoForge `MouseHandler.java:87` 在设 `isLeftPressed`(:121) 与 `KeyMapping.click`(:133) 之前返回 |
| 快照空 / FOV / 雾禁用不误清 | 通过 | 清理入口只有 `StructureSelection.pruneOutOfRange`（:101-108），仅由 `MarkFramePublisher.java:40-42` 在举镜且 `SpyglassRevealState.isEnabled()` 时调用；空快照用 `MarkFrame.withSelection`（`MarkFrame.java:76-90`）从存档锚点补画 |
| 放下保留 | 通过 | `pruneOutOfRange` 在 `isScoping` 为假时根本不会被调用（`MarkFramePublisher.java:37-39` 先 return） |
| 真距离 128–1024 越界举镜清 | 通过 | 距离基准=存档中心（`StructureSelection.java:54-57`），与显示环 `SpyglassDistance.inRange`（:13-15,31-33）同一口径；边界含端点（测试覆盖 128/1024 与 ±0.001） |
| 换维度 / 退出清 | 通过 | `SpyglassClientState.clear()`（:125-145）清选择/帧/锁存，挂 LoggingOut(:147-150)、客户端关卡卸载(:152-164)、维度不一致(:52-54,:90-93) |
| 选中 [?]/[!] 扩大碰撞 | 通过 | 宽度走 `MarkLayout.boxWidth(scaledGlyphWidth)`（`StructureMarkLayer.java:90`），绘制/重叠/点击共用同一 `halfWidth`（:151、`CrosshairPicking.java:40`）；高度固定 32 与 `HALF_BOX` 一致 |
| 选中箭头优先、仅镜片外 | 通过 | `EdgeArrowTarget.java:20-38`：有选择时只认该 key，`inLens` 即无箭头；无选择回退只取最近的 `?`，`!` 不参与 |
| 隐藏目标仍有箭头 | 通过 | 帧里保留未绘制标记的投影与视线方向（`StructureMarkLayer.java:99-106,142-155`），`EdgeArrowTarget.java:30-37` 不看 `drawn` |
| ? 变 ! 同身份 | 通过 | 服务端两条路径都按“所属区块”建 key：`StructurePredictor.java:208`（被预测的区块）与 `StructureStartNbt.java:120`（读到的区块）语义相同；客户端 `observe`（`StructureSelection.java:81-92`）只按 key 续命并刷新 predicted |

## 4. spyglass 发现（均非阻塞）

- **S1（低）帧有效期 250 ms**：`StructureMarkInput.java:34`。CPU 96% 或低帧率（>250 ms/帧）时，画面上仍显示上一帧标记，但点击被判为 stale 直接忽略，表现为“点了没反应且无反馈”。若坚持墙钟判据，建议至少在超时后保留同一 GUI 尺寸的最后一帧；否则改为“与最后渲染帧号一致”。
- **S2（低）`CrosshairDistanceLayer` 不校验帧新鲜/GUI 尺寸**：`CrosshairDistanceLayer.java:48-58`。窗口 resize/卡顿后的一帧里，文字仍按旧 `guiWidth/Height` 居中。仅视觉偏移一帧。
- **S3（记录）选择不会因结构从快照消失而清除**：`MarkFrame.withSelection`（`MarkFrame.java:76-90`）每帧从存档锚点补画，直到越环/换维度/再点一次。这是“快照空不误清”的直接后果；一个已被拆掉的结构会继续显示 `[?]`/`[!]`。需产品口径确认，不算缺陷。
- **S4（覆盖缺口）** 帧交接顺序（`withSelection` 与 `currentStructures()` 的先后）、GUI 图层注册、真实鼠标事件三条只有实机才能验证；单测只覆盖纯规则。

## 5. 车架阶段1 需求逐条

| 要求 | 结论 | 证据 |
|---|---|---|
| 只用显式世界锚探针，不接生产 tick | 通过 | 只有 `/dbx chassis probe`（`ChassisProbeCommand.java:26-42`，权限 2，:29）与两个 GameTest 能调 `start`；`ChassisEvents.onTick` 只驱动超时/清理（:72-81） |
| 真实 Sable API | 通过（静态） | 见 §2；探针用的类型/签名全部在 2.0.5_cui-modded jar 中存在；null→ground 映射有 Rapier 源码证据 |
| 物理坐标 / frames | 通过（静态） | pos1=世界锚、pos2=plot 锚；frame1=只保留偏航的世界帧、frame2=`inverse(bodyRot)*worldFrame`（`ChassisPose.java:46-55`，`ChassisWorldAnchorProbe.java:239-252`）；与 `validateAnchors` 的空间要求一致 |
| 生命周期 / 移除 / 超时失败安全 | 基本通过 | tick 检查 server 替换(:291)、目标移除(:295)、超时(:299)、句柄失效(:303)；`stop` 必走 `destroy`(:317-331)；`ServerStoppedEvent`→`shutdown`(:97-104)；tick 抛异常→`stop("tick_error")`(:74-81)；remove 后仍 valid 则进 `PENDING_REMOVAL` 每 tick 重试(:526-553) |
| 不误锁用户普通车 | 通过 | 无自动启动路径；`start` 必须显式给 UUID 或 `nearest`（32 格内，:79,:585-600）；duration 夹在 20–1200 tick(:208)，命令层 1–60 s |
| 注册到正确 withChassis 开关 | 通过（无需改 build.gradle） | 两个新类自注册（`ChassisInteractionGameTests.java:42-45`、`ChassisWorldAnchorGameTests.java:32-35`），门控与既有 `ChassisGameTests.java:35-37` 相同；命名空间 `dimblend_chassis` 命中 `build.gradle:275-278`。**不要**再加进 `DimBlend.onRegisterGameTests`（`DimBlend.java:119-187`），那是另一套注册，会重复注册 |
| 不擅开所有 GameTest | 通过 | 命名空间过滤只放行 `dimblend_chassis` |

## 6. 车架阶段1 发现

- **C1（中，需 owner 决定）探针锚点偏置**：`ChassisWorldAnchorProbe.java:239-241` 用 `getPlot().getCenterBlock().getCenter()`。装配器把 anchor 方块放在 plot 中心（sable `SubLevelAssemblyHelper.java:104-107`），所以这是**装配锚点方块**，不是质心；对 41 格长的标准底盘，相对质心仍有可达约 20 格的力臂，`hold` 的 drift/verticalError 会带上这部分杠杆效应。文档已声明“不是阶段2轨高基准”，但没写力臂偏置；阶段2 读这些数字时要注意。
- **C2（低）报告字段可能乐观**：`ChassisWorldAnchorProbe.java:508-524`，`remove()` 抛异常也先置 `jointRemoved=true`；真实状态靠 `jointValidAfterRemove` 表达。建议改名为 attempted，或仅在 `valid()==false` 时置 true。
- **C3（低）重试无上限/退避**：`ChassisWorldAnchorProbe.java:526-553`，若句柄永远 valid，会每 tick 重试、每 200 次（约 10 s）打一条 ERROR，持续到关服。诊断用途可接受，但长跑服务器会持续刷。
- **C4（低）客户端/服务端判据不完全对称**：客户端 `ChassisSelection.java:60-64` 只认 `TrackShape.XO + TRACK_Y + CORRIDOR_Z`，服务端 `CorridorRails.java:26-28` 还要 per-delegate 的轨道 id。若主线被换成别的轨道 id：客户端判 true→取消（预测 SUCCESS），服务端判 false→放行方块自身 use，出现一帧级分叉。窗口很窄。
- **C5（覆盖缺口）GameTest 未跑**：`ChassisWorldAnchorGameTests.java:37-69` 依赖 20 tick 后刚体已注册、40 tick hold 后关节可移除；`ChassisInteractionGameTests.java:58-105` 依赖 x=448 处主线存在（已核对 `StarterDepot.removesTrack` 不覆盖该 x）与假连接能收到包（`PacketDistributor.sendToPlayer` 直接 `player.connection.send`，:50-52）。**一律未执行，不得写成通过。**
- **C6（记录）仓库根残留未跟踪目录 `%SystemDrive%/`**：像是某次 PowerShell 路径写坏留下的，不属于本单元，也**未删除**；提交前请其归属代理清理。

## 7. 本单元改动

- 新增本文件 `qa/nine-fixes-20261110.md`；`qa/gaps.md` 追加本轮无法自动验证的项。
- 共享注册（单元1 所有权）：
  - `dimblend_experience.mixins.json`：stockkeeper 3 条 common + 1 条 client；§9 的 `mixinextras.minVersion` 声明。
  - `DimBlendMixinPlugin.java`：`compat.create.stockkeeper.*` 整包门控 `create && kaleidoscope_doll`。
  - `DimBlend.java`：Create 门控内注册一次 `DollStockKeeperGameTests`。
- 编译依赖：`dimblend/build.gradle` 增 `compileOnly "maven.modrinth:aether:K5X5qMwG"`（`dimblend-experience/build.gradle` 本轮未改）。
- **未改**任何 spyglass / 车架 / compat / 渲染源码；未 commit/stage/push。

## 8. 后续入口（不扩大范围）

1. 等失败客户端 PID 74732 退出后，用唯一 jar token 重打 `:dimblend-experience:jar`（含 §9 的声明修复），再装进整合包实机启动复验。
2. 实机路径：stpkeeper（玩偶坐售货员椅开单）、望远镜选中/放下/越界/换维度/?→!、车架右键归属；GameTest 仍无 token。
3. `-PwithChassis` 的两个新 GameTest 与 `DollStockKeeperGameTests`（缺 Doll 时 SKIP）均未执行。

## 9. 实机首启 FATAL：MixinExtras `@Expression` 声明缺失（2026-11-11）

- 现象：装上新 jar 后首次启动在**模组构造期** FATAL，未进世界。
  - `E:\misc\TrainTripWorld\logs\latest.log:932` / `:988`：`java.lang.UnsupportedOperationException: In order to use @Expression, Mixin Config 'dimblend_experience.mixins.json' needs to declare a reliance on MixinExtras >=0.5.0-beta.1! E.g. "mixinextras": {"minVersion": "0.5.3"}`
  - `:942`：`Failed to apply mixin. Mixin Class: dimblend.experience.mixin.compat.create.stockkeeper.DollStockTickerInteractionMixin`
  - `:998`：`Failed to wait for future Mod Construction, 1 errors found`（FATAL）
- 运行库：NeoForge 21.1.249 的 universal jarJar 内是 MixinExtras **0.5.3**（栈帧 `mixinextras.neoforge@0.5.3`、`MixinConfigUtils.requireMinVersion`）。
- 根因：MixinExtras 要求**每个使用 `@Expression` 的 mixin 配置**在 JSON 顶层显式声明所依赖的最低版本；缺失时在注入点初始化阶段抛异常，编译与单测均不报错。
- 范围核查：全仓 grep `@Expression` 只命中 `dimblend-experience`（该 mixin 一处 + 文档一处）；其他模块只用 `@WrapOperation`/`@ModifyExpressionValue` 等，不要求该声明，因此**只改本配置**。
- 修复：`dimblend-experience/src/main/resources/dimblend_experience.mixins.json` 顶层增加
  `"mixinextras": { "minVersion": "0.5.3" }`；`required/package/compatibilityLevel/plugin/mixins/client` 其余字段未动（mixins=80 / client=20）。
- 影响面：纯声明，不改任何 mixin 类，不升级 MixinExtras / NeoForge，不动他人 jar。
- 验证状态：声明修复已重打进 jar（`:dimblend-experience:jar` 退出码 0，10s，SHA-256 `F599C215…FFCF4F06`，jar 内实读 `minVersion=0.5.3`）；**未重跑 539 测试、未再次实机**，等真实启动验收。此类问题编译与单测都抓不到，与 AGENTS「mixin 结构问题必须实机走对应路径」一致。

## 10. 脚本验收准备状态（2026-11-11）

- 第二次启动已进入专用测试存档 `dimblend-nine-fixes-20261110`，实际物理 provider 为 Rapier；尚未执行车架 `read/free/hold` 验证，正式高度锁仍未实施。
- 三模块正式单测共 955 项通过（spyglass 174、dimblend 242、experience 539）；不代表九项实机验收通过。
- 按用户要求改用 Minescript 桥与已查证的游戏 API 控制客户端。固定 worktree 诊断入口及状态／恢复暂停脚本已完成，14 项静态检查通过；符号链接测试因 Windows 权限跳过。脚本尚未在新桥进程中实测，入口见 `dimblend-experience/docs/script-client-harness.md`。
- NeoForge 21.1.249 的 `GameTestHooks.java:32-33` 在生产环境禁用 GameTest；生产客户端增加 `neoforge.enableGameTest` 也不会启用。相关 GameTest 需另在开发测试服务器串行执行，当前均未执行。
- 客户端于 01:00:29 正常保存退出；已确认并清理其孤儿 Python 桥进程，仅保留锁文件。细节见 `qa/live-nine-fixes-20261110.md`。
- 后续采样 CPU 约 94%，可用内存一度降至 2683 MiB；遵循用户选择，客户端保持关闭，等待负载下降。未绕过资源门启动游戏。
- Loot Journal 本地配置包含 `ICON`；之前无 HUD 的截图不算复现证据。三项显示故障仍待有效复现与修复前后对照。
- **整批未交付；未 stage、未 commit、未 push。**


## 11. 玩偶请求界面构造器修复重新打包与部署（2026-11-11 01:58）

- 本轮仅构建 `:dimblend-experience:jar`，打入已冻结的 `DollStockKeeperRequestScreenMixin` 构造器 `@At("RETURN"), require=2, allow=2` 修复；本轮未修改其它 Java、产品逻辑或配置，未重跑此前 955 项单测，未运行 GameTest、未启动客户端。
- 构建脚本为 `build/nine-fixes-logs/RunExperienceJarAndDeploy.ps1`。修正 `GradleDaemon` 必须先于 `org.gradle.launcher` 分类的守卫错误，并识别 `@launch.args.txt` / `--quickPlaySingleplayer` 游戏入口；只允许已知 PID 71688 且连续两段 2 秒 CPU 增量均小于等于 0.5 秒，其它 Java 或 CIM 失败保守停止。实际 prebuild/deploy 的两段增量均为 0，不打印进程命令行、不终止其它进程。
- 资源：授权构建前可用内存约 22.2 GiB，CPU 采样 96%；用户已允许高负载时低优先级、低并发编译，客户端仍等待负载下降。实际使用 JDK 21、`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`、`--max-workers=1 --no-parallel --no-daemon`。
- 首次真实构建日志 `build/nine-fixes-logs/experience-jar-dollctor-20261111-015452.log`：`BUILD SUCCESSFUL in 1m 17s`，15 项任务中 2 executed / 13 up-to-date。Windows PowerShell 5.1 的 `Start-Process` 退出后未保留进程 Handle 导致 `ExitCode` 为空，脚本停止在部署之前，实例未改。随后用立即 `exit 37` 的轻量进程验证持有 Handle 的退出码读取，脚本修复后复跑同一 jar 任务，不重跑测试。
- 成功构建与部署日志：`build/nine-fixes-logs/experience-jar-dollctor-native-20261111-015749.run.log`，脚本退出码 **0**、`BUILD ExitCode=0`、`DEPLOY_OK`；构建日志 `experience-jar-dollctor-20261111-015750.log` 为 `BUILD SUCCESSFUL in 30s`，15 项全部 up-to-date，复用了上一轮真实编译的同一产物。
- 优先级证据：`experience-jar-dollctor-20261111-015452.priority.log` 中实际 Java PID 63532 / 47552 均为 `BelowNormal`；复跑的 `experience-jar-dollctor-20261111-015750.priority.log` 中实际 Java PID 65152 / 76556 均为 `BelowNormal`，根 cmd PID 80492 也为 `BelowNormal`。console host PID 64928 初次观测为 Normal，立即降为 BelowNormal；未出现 Java 的 Normal 观测或优先级设置失败。Watcher 仅作用本次构建根进程及其子孙，均已正常结束。
- 新构建/部署 jar 大小 **1,712,490 字节**；SHA-256：`4B179CFBD077A5115D31B4F248BC48486A803431008C61F1CBC7D3DF9F688E0A`。已独立复核 worktree 产物与 `E:/misc/TrainTripWorld/mods/dimblend_experience-1.0.0.jar` 哈希一致。jar 内 `mixinextras.minVersion=0.5.3`、common mixins=80、client=20；`javap -p -v` 实读打包类的构造器注解确为 RETURN / require=2 / allow=2。
- 本次新增哈希名备份：`E:/misc/TrainTripWorld/jar-backups/nine-fixes-20261111/dimblend_experience-1.0.0.F599C215AFEE5D22216693B7D9F34EB36578B217C0C5B112EEB16EF8FFCF4F06.jar`，大小 **1,712,481 字节**，SHA-256 与原实例 `F599C215AFEE5D22216693B7D9F34EB36578B217C0C5B112EEB16EF8FFCF4F06` 一致。未覆盖最初备份或其它 jar。
- 本轮只确认构建与部署；**客户端玩偶与燃烧室共存后移除燃烧室、界面继续保持并正常刷新，随后玩偶离座/删除关闭**仍待实际验收。服务器单测和 GameTest 不创建该客户端屏幕，不能据此宣布此路径通过。
- 构建 heavy token 已释放给后续 live 单元；本单元未 stage、未 commit、未 push。
## 12. COM 世界锚回归红绿验证与 experience 部署（2026-11-11 02:27）

- 本轮只验收 chassis owner 已冻结的 COM 投影修复；未新增产品代码。唯一临时源码改动是 `ChassisPose.anchor` 把 `.transform(new Vector3d(plotPoint).sub(centerOfMass), dest)` 改回无 COM 减法；新方法签名保留，使指定回归能真实运行。证据目录：`build/nine-fixes-logs/com-probe-20261111-022312/`；完整流水：`build/nine-fixes-logs/com-probe-native-20261111-022309.run.log`。
- 原源码按原字节备份为该证据目录下 `ChassisPose.java.original.bin`。原文件 / 备份 / finally 恢复后源码 SHA-256 均为 `ADFDC0FB56F462FC32B0BCE025265014906643CCFD7F8B741FA90A0E406A6983`；临时红测源码 SHA-256 为 `50CB2BA8A7FB348300C012A26F9A027F9094684CA171872A11A1918DB45EFB9A`。恢复记录为 `source-restore.json`；部署完成后独立逐字节比较仍为 true，不只是声明已恢复。
- **有效红测**：仅运行 `:dimblend-experience:test --tests dimblend.experience.chassis.ChassisPoseTest.anchorSubtractsLargeNonZeroPlotCenterOfMassLikeNativeJoint`，Gradle 退出码 **1**。保存的 `red-ChassisPoseTest.xml` 为 tests=1 / failures=1 / errors=0 / skipped=0；失败类型 `org.opentest4j.AssertionFailedError`，坐标断言 `expected: <0.04351814941564162> but was: <11.184164399819895>`。因此证明旧的无 COM 投影确会使新回归失败，不能归因于编译/启动故障。源码已在退出测试的 finally 立即恢复，随后才解析红测结果并运行绿测。
- **绿测**：恢复源码后运行 `:dimblend-experience:test --tests "dimblend.experience.chassis.*"`，Gradle 退出码 **0**、`BUILD SUCCESSFUL in 1m 35s`。独立核对 `green-xml/` 的 **11 个 suite、64 项测试，failures=0 / errors=0 / skipped=0**；其中 `ChassisPoseTest` 6 项均通过（含新增非零 COM 回归）。汇总为 `green-summary.json`，未重复此前 955 项全量测试。
- 之后仅运行 `:dimblend-experience:jar`，退出码 **0**、`BUILD SUCCESSFUL in 38s`，15 项任务中 1 executed / 14 up-to-date。构建日志 `build/nine-fixes-logs/experience-jar-dollctor-20261111-022704.log`；部署日志在本轮证据目录 `jar-deploy.run.log`。wrapper 最终退出码 **0**。
- 打包字节码核验：`jar-ChassisPose.javap.txt` 显示 helper 中恰好一次 `Vector3d.sub(Vector3dc)`；`jar-DollStockKeeperRequestScreenMixin.javap.txt` 确认构造器仍为 `RETURN / require=2 / allow=2`。jar JSON 实读 `mixinextras.minVersion=0.5.3`、common=80/client=20。
- 新 experience jar 已安装至 `E:/misc/TrainTripWorld/mods/dimblend_experience-1.0.0.jar`，大小 **1,713,109 字节**；构建产物与实例独立复核 SHA-256 均为 `BB2347248821A6AF02DAC95CC70090A45312D3FFD86D0F3A95ED740600F9DD76`。
- 部署前实例旧 SHA-256 为 `4B179CFBD077A5115D31B4F248BC48486A803431008C61F1CBC7D3DF9F688E0A`。新增备份为 `E:/misc/TrainTripWorld/jar-backups/nine-fixes-20261111/dimblend_experience-1.0.0.4B179CFBD077A5115D31B4F248BC48486A803431008C61F1CBC7D3DF9F688E0A.jar`，大小 **1,712,490 字节**、哈希与旧实例一致；原始与 F599 哈希名备份均未覆盖。
- 资源与优先级：开始时可用 17.75 GiB / CPU 97%，每阶段守卫再次确认至少 6 GiB 与无 Java；用户已授权高负载下低优先级编译。全部 Gradle 使用 JDK 21、`ActiveProcessorCount=2`、`--max-workers=1 --no-parallel --no-daemon`。红测实际 Java PID 75700/45788/62312/20420、绿测 20360/22620/79260、jar 71340/60948，在 watcher 采样中均为 `BelowNormal`；仅监控各次构建 PID 树，未修改其它进程优先级或终止其它进程。日志为证据目录的 `red.priority.log` / `green.priority.log`，以及 `experience-jar-dollctor-20261111-022704.priority.log`；500 ms 轮询记录不是连续性能采样。
- `GuiFrameCapture.java` 在每次编译前均确认不存在，capture owner 本轮只写 Python、不参与 Java；未编译其它未完成的 Java 改动。未执行 craft 任务、未启动客户端、未运行 GameTest，未 stage/commit/push。
- 此结果只证明 COM helper 回归与正确 jar 已部署；实际 Sable `read/free/hold` 机制验收仍待父代理放行，**正式生产高度锁尚未实施**。按父代理最新调度，heavy token 暂不移交 live，保留等待下一项串行构建指令；客户端继续关闭。
## 13. craft EnderStorage 单测、打包与部署（2026-11-11 02:36）

- 本批当前共 **11 项需求**；本轮只验证并部署 recipe owner 已冻结的 craft 改动。`RecipeEditRules.java`、`RecipeEditRulesTest.java`、`docs/ender-storage-recipes.md` 在构建前记录 SHA-256，构建与部署后独立复核三者均未变；本单元没有修改产品源码、测试或文档内容。
- 只执行一次 `:dimblend-craft:test :dimblend-craft:jar --max-workers=1 --no-parallel --no-daemon --console=plain`，JDK 21，`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2`。Gradle 与外层 wrapper 退出码均 **0**，`BUILD SUCCESSFUL in 1m 44s`，6 项任务全部 executed；未运行其他模块测试、GameTest 或客户端。
- 部署前已解析全部测试 XML：**4 个 suite、44 项测试，failures=0 / errors=0 / skipped=0**。分项为 `DuplicateNameRulesTest` 13、`FilterFrameStonecuttingTest` 2、`RecipeContentTransformTest` 11、`RecipeEditRulesTest` 18。新增水缸珍珠位→虚空核心、箱子四角→烈焰粉及 recolour 不被规则命中三项均通过，既有末影袋等回归也通过。
- 证据目录 `build/nine-fixes-logs/craft-test-jar-20261111-023441/`：`build.log`、`build.err.log`、`build.priority.log`、`test-xml/`、`test-summary.json`、`frozen-source-hashes.json`、`deployment.json`、`independent-hashes.json`。入口 `build/nine-fixes-logs/RunCraftTestJarAndDeploy.ps1`；完整流水 `build/nine-fixes-logs/craft-test-jar-native-20261111-023440.run.log`。
- 构建前资源为可用 **16 GiB / CPU 85% / Java 0 个**；部署前再检查为 **16.99 GiB / CPU 99% / Java 0 个**。用户已授权高负载下受限编译；本轮客户端一直关闭。Watcher 仅跟随本次 cmd PID 39676 的子孙，每 500 ms 采样；实际 Java PID **23448、59816、79672、59708** 均观察为 `BelowNormal`，无优先级设置失败。此为进程优先级采样证据，不是连续性能证明；未更改或终止其他 Java/游戏进程。
- 实际旧安装包为 `E:/misc/TrainTripWorld/mods/dimblend_craft-0.1.0.jar`，**88,958 字节**，SHA-256 `255BB27071AB503DC7D53B3DE9CB0F9311C9226FDDA4911BF61A2ECE21F2401F`。通过 XML 后才创建哈希名备份 `E:/misc/TrainTripWorld/jar-backups/nine-fixes-20261111/dimblend_craft-0.1.0.255BB27071AB503DC7D53B3DE9CB0F9311C9226FDDA4911BF61A2ECE21F2401F.jar`；备份仍为 88,958 字节，独立复核 SHA 与旧安装包一致。原有备份未覆盖。
- 新 jar 为 **89,091 字节**，SHA-256 **`CFA14438E3B8EA0BE89AE7B1FF13714E6FC0D2A839C644D3B9EC034A16DB6FDD`**。worktree 产物 `dimblend-craft/build/libs/dimblend_craft-0.1.0.jar` 和实例安装包在部署后独立重算，哈希与大小一致；jar 内确认存在 `dimblend/craft/recipe/RecipeEditRules.class`。
- **验收边界：单测和部署通过不等于实机 Recipe Codec 或颜色 Frequency 通过。** 单测证明真实 JSON 的 key 路径与改写布局，未执行完整 serializer Codec 往返。端侧是否保留原 `enderstorage:create_recipe` serializer / `CreateRecipe` 子类、红色羊毛 Frequency、`recolour_ender_tank` / `recolour_ender_chest` 实际染色，以及旧材料（tank 珍珠、chest 烈焰棒）被拒绝，均待 live 进入世界后按 `dimblend-craft/docs/ender-storage-recipes.md` 第 6 节验证；本批 11 项尚未整体交付。
- 本单元未 stage、未 commit、未 push。完成独立校验后 heavy token 交回父代理，由父代理决定何时交 live；本单元不启动客户端。

## 14. 用户豁免后续验收 · 按当前实现提交（2026-11-11）

- **用户明确授权（本轮原话口径）**：不再继续验收，直接 commit 并 push 当前 `codex/nine-fixes-20261110` 分支，随后构建受影响 jar；提交内容是**当前实现现状**。因此本文档此前的"待实机"结论一律保留，不改为通过。
- **本批 11 项不声明完成，也不声明"验收未通过"**：验收被用户豁免、未执行，不是被测出失败。已实现的代码/测试/文档按原样提交，未实现的部分照实留在下面。
- **实机状态：新版本一次都没进过游戏。** 资源门（连续三次 CPU < 60%）未满足，客户端全程关闭；最终 experience jar `BB234724…DD76`、craft jar `CFA14438…6FDD` 只做到"构建 + 部署 + 哈希核对"，没有任何游戏内路径被执行。GameTest 在 NeoForge 21.1.249 生产客户端不可用（`GameTestHooks` 要求非 production），同样未执行。

### 14.1 已实现并提交的项（= 代码在树内、有单测/编译证据，不等于实机通过）

| 项 | 状态 | 证据边界 |
|---|---|---|
| 望远镜选中/箭头/标签/框宽 | 已实现（本批 diff 含选中、箭头、标签与点击命中） | `dimblend-spyglass` 174 项单测通过（23:14 XML）；README 与"去掉两套 3D 渲染"后的实现对齐 |
| 玩偶售货员（Doll × Create StockTicker） | 已实现 | 4 个 mixin + `DollStockKeeperCompat` + GameTest 在树内；GameTest 未执行，实机路径未走 |
| Aether 区域限制（放置禁令按 lane 放行） | 已实现 | `AetherPlacementBanRules` 纯规则单测；`dimblend` 242 项通过（含该测试） |
| 车架交互（右键归属/选择页判据） | 已实现 | `ChassisInteraction` 纯规则 + 两端共享 `applicable`；实机点击未验 |
| 动画资源缓存（重载/失败/残缺结果自愈） | 已实现 | `SableAnimationSpriteCache` 单测；缓存只在本批 539 全量跑中覆盖过，未实机验证重载 |
| 2 条 EnderStorage 配方（水缸珍珠位→虚空核心、箱四角→烈焰粉） | 已实现 | craft 44 项通过；配方只在进档时生效，**未在游戏内验证** |

### 14.2 尚未实现 / 仍未证实（逐条保留，提交不改变这些事实）

- **车架正式固定高度：未实现。** 目前只有阶段1世界锚探针（`/dbx chassis probe`）+ 身份登记纯数据（`ChassisRegistry/RegistryData/LaneRule/ClientTrackRule/Skeleton`）+ 文档。没有生产 tick 接锁、没有每 tick teleport、没有脱轨/拆解释放；`docs/standard-train-chassis.md` 的"阶段2"仍是设计。
- **Loot Journal 显示故障：未修复、未复现。** 本批无对应产品改动，截图证据也不成立（`screenshot` 在 `gui.render` 之前抓主渲染目标，HUD 不在其中）。
- **石磨（millstone）故障：未证实修复。** 本批无对应改动。
- **望远镜/玩偶/车架/配方/Aether 的实机路径：全部未执行**，含 mixin 结构类改动（AGENTS 要求实机走对应路径才算验证完）。

### 14.3 提交范围复核（本单元，只读产品源 + git 状态）

- 待暂存 **73** 个路径：27 个已跟踪修改 + 46 个未跟踪新增（39 个 `.java`、7 个 `.md`）。精确清单见 `build/nine-fixes-logs/commit-scope-20261111.json`（该文件在 `build/` 下，被 gitignore，不入库）。
- **排除**：仓根未跟踪目录 `%SystemDrive%/` 下 4 个 Windows Caches `.db`（某次 PowerShell 路径未展开的残留，与本批无关，**保留不删、不暂存**）。
- **不强行入库**：`build/`、`.gradle/`、各模块 `build/`、`dimblend-craft/logs/` 均被忽略；`build/nine-fixes-logs/` 里的构建/部署脚本与日志是运行期证据，留在本地。
- **秘钥/临时物检查**：diff 与未跟踪新增中无 token / 口令 / 私钥 / 临时脚本 / 崩溃转储；文档里的 `E:\misc\...` 绝对路径是本仓既有取证惯例，非机器垃圾。
- **测试数字（以磁盘 XML 为准）**：spyglass 28 个 suite / 174 项；dimblend 41 / 242；craft 4 / 44；experience 当前只剩 11 个 suite / 64 项（COM 回归的 chassis 子集）。experience 的 539 全量结果来自 00:04 那次运行，之后 01:39 的 mixin 构造器修复**没有**重跑全量（该文件是 mixin，单测不覆盖；02:27 的 jar 已把修复编入，javap 已核）。
- **合流风险（不影响本次分支提交）**：本 worktree HEAD 落后 `origin/master` 2 个提交（`92370f5` 合入的 supplement 分支已带一套玩偶管理员实现）。本批新增 `dimblend/experience/gametest/DollStockKeeperGameTests.java` 与 `origin/master` 上**同路径同 FQN**，且 `mixin/client/DollStockKeeperRequestScreenMixin` 与本批 `mixin/compat/create/stockkeeper/` 版本并存。将来把本分支合回 master 会出现 add/add 冲突与两套玩偶 keep 逻辑并存，合并前需人工裁定保留哪套。

- 本单元未 stage / 未 commit / 未 push；只写了本文件与 `build/nine-fixes-logs/commit-scope-20261111.json`。
