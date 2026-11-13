# DimBlend Spyglass

Minecraft 1.21.1 / NeoForge 的望远镜远景建筑提示模块（mod id `dimblend_spyglass`），依赖 `dimblend` 主模块（旋转维度的结构状态与世界生成）。联机时服务端和客户端都需要安装本 mod 与 `dimblend` 主模块：结构数据由服务端读取并下发，只装客户端无法使用。

## 使用

在**第一人称**视角下使用望远镜，可以看到远景结构标记：

- **显示范围固定为总包围盒中心水平距离 64–1024 格（含两端）**。内沿不随渲染距离或雾变化；64 格以内不标，避免挡住眼前的东西。
- 每个在范围内、且通过来源开关的结构，在包围盒顶面中心上方显示一个标记：存档里读到它的 start 时是 `!`，只有种子重放算出来时是 `?`。
- 准星对准一个**实际画出**的标记时，屏幕顶部显示 `~Nm`（四舍五入到 10 格）；移开即消失。
- 边缘箭头见「边缘箭头」：有选中时只指向选中的结构，没有选中时沿用最近的 `?`。
- 不显示结构名，也没有「（未生成）」后缀。
- 相机在水下、岩浆、细雪里，或有失明/黑暗效果时，雾类型不是 `NONE`，本模组整体关闭显示（标记、距离、箭头都不画）。
- 本模组**不画结构的方块**，也不做模板预览：画面上的内容只有标记、顶部距离与边缘箭头。

## 选中（左键）

左键点击一个**本帧实际画出**的标记即可选中它；选中的标记画成 `[?]` 或 `[!]`：

- **单选**。再点同一个结构取消；点另一个结构替换。
- **身份是 `StructureKey`**（结构 id + start 所在区块），不是字形或快照条目。因此一个预测结构（`?`）后来在存档里生成为真实结构（`!`）时，键不变，选中也随之保留。
- **输入路径**：`InputEvent.MouseButton.Pre`（原版在处理之前，`MouseHandler.java:87`），只用 GLFW 左键 **PRESS**。不使用 `InteractionKeyMappingTriggered`：举镜时原版会吃掉 attack 键映射，那条路根本收不到点击。按下时还要求：`screen == null`、加载 overlay 为空、鼠标被抓取（`isMouseGrabbed`）、`isScoping`。
- **命中**：只认最近一帧 `MarkScreenFrame` 里 `drawn` 为真的标记（被防重叠规则隐藏的不算），使用其**实际画出的框**加 4 像素容差，取离屏幕中心最近的一个；空白处、过期帧（帧龄 > 250 ms、GUI 尺寸已变、从未发布）不命中。
- 只有命中并真的切换了选中时才 `setCanceled(true)`，原版不会再把它变成攻击。**RELEASE 不取消**，也不触发选择；按住不放不会重复切换（按下锁存，直到收到释放）。
- **放下望远镜保留选中**。
- **举镜时按现有 64–1024 距离环解除**：只有选中结构保存的中心距离真正越出 `[64, 1024]`（含边界）才清除。转身、FOV/变焦、被防重叠规则隐藏、快照暂时为空、雾被效果/流体禁用，都不会清除选中。客户端换维度、退出登录、关卡卸载与 `SpyglassClientState#clear` 会复位选中。
- **不用当前快照列表做清理**：选中保存的是结构总包围盒的**稳定世界位置**（顶面中心 `(centerX, maxY + 1, centerZ)`）与水平中心（`SpyglassDistance` 的距离依据）。即使快照里暂时没有它，标记仍按保存的锚点投影与绘制，直到真的越距。`StructureSelection.Entry` 还记录预测标志，`?` 转 `!` 时只更新标志，键与选中都保留。
- 选中标记的框按字形加宽（至少 32×32 GUI 像素，`[?]`/`[!]` 三字形时更宽），绘制、防重叠与点击用的是同一个框；并且**优先摆放**，不会因为与其他标记重叠而被跳过。
- 选中是纯客户端状态，**没有新增网络包**。

## 结构标记

- **锚点**：总包围盒（`bounds()`，不是部件列表）顶面中心的正上方：X/Z 取 `(min + max + 1) / 2`（`OutlineBox#centerX/Z`），Y 取 `maxY + 1`。每帧用**本帧**的相机位置、投影矩阵（含望远镜 FOV 缩放与视角摇晃）和 model-view 投影到屏幕；相机后方或屏幕外时不画。`RenderLevelStageEvent` AFTER_PARTICLES 记录，同一帧的 GUI 层消费（`MarkFrame` → `StructureMarkLayer`）。
- **字形**：`?`（预测，未生成）或 `!`（存档读到），ASCII、白色带阴影、无底板；选中时画成 `[?]`/`[!]`。字形与框宽都是纯函数（`MarkerGlyph`、`MarkLayout`）。
- **哪些结构有标记**：`SpyglassScopeGate#isScoping` 为真、雾可用（`SpyglassRevealState#isEnabled`）、且结构在 `SpyglassClientState#currentStructures()` 的 64–1024 环内。被重叠规则跳过只是这一帧不画它的字，不影响它参与距离与箭头；已被选中的结构即使不在当前快照里也按保存的锚点画（见「选中」）。
- **望远镜遮罩**：标记图层注册在 `VanillaGuiLayers.CAMERA_OVERLAYS` **之下**，原版镜片贴图与四块黑边在它之后绘制，自然裁掉镜片外的标记；不复制任何遮罩几何。
- **重叠**：被选中的标记最先摆放；其余按中心距离由近到远贪心放置，至少 32×32 的框（选中标记更宽，含 2 像素间隔）与已放置标记重叠的跳过本帧。按距离而不是离准星远近排序，转动视角时优先级稳定。

## 顶部距离

本帧被画出的标记里，准星落在其框 + 4 像素容差内时，屏幕顶部居中显示 `~Nm`（`CrosshairPicking` 取离屏幕中心最近的命中）。`?` 与 `!` 都参与，选中标记的宽框同样参与。`DistanceText` 负责舍入与格式。

## 边缘箭头

- **有选中**：只指向选中的结构，且只有它不在镜片内缩矩形（24 像素）里时才画。选中标记即使被防重叠规则隐藏（`drawn = false`），箭头仍用它的投影方向定位，不受隐藏影响；快照里完全没有它（例如雾不可用导致本帧无标记）时不画，而不是改指别的结构。
- **没有选中**：从 `?` 里取中心距离最近、且不在镜片内的一个；`!` 不参与。
- 目标在相机前方用透视方向，在身后按 `camX` 的符号取左/右（正后方固定指右）。箭头画在屏幕四边内侧、遮罩之上。

## 只读边界

结构查询只读已有 region 文件：结构 start（`structures.starts` 及其 `Children` 部件 BB）。查询路径严格遵循以下限制：

- 只用 `StandardOpenOption.READ` 打开 region 文件；不经过原版 `RegionFile`（它可能写回或修复表头），不创建目录；
- 不调用 `getChunk` 补取区块，不加载新区块，不触发区块生成；
- 不读取**实时世界**的方块；
- 只读取由**当前游戏版本**保存的区块：区块的 `DataVersion` 必须等于当前世界数据版本，其它版本（更旧或更新）一律跳过并计入 WARN 的 `unsupportedDataVersion`，不做数据升级。

region 表头在打开文件时快照、扇区稍后才读，服务端并发写盘理论上可能让扇区被回收复用。因此生产读取路径同时读取区块自身的 `xPos`/`zPos` 并与请求区块比对，不一致的区块按读取失败丢弃，不会把别的区块的 start 当成这个区块的结构。

## 未生成结构的标注（种子重放）

玩家从未去过的地方，磁盘上没有区块数据，原版也就没有 start 可读。这类位置本模组会按种子**重放世界生成**，把结构的位置与尺寸算出来并画标记（`?`）。它只给出**位置与形状轮廓，不给方块**。

- **能算出来的是位置，不是建筑**。结构放置是种子与数据包的纯函数：`Structure.generate` 由结构的高度提供器选起点、拼装拼图模板，返回部件 BB（`Structure.java:91-100`），完全不需要读世界——`/locate` 走的就是这条路（`StructureCheck.java:97-110`）。但**方块**只有在 `StructureStart.placeInChunk` 把方块写进 `WorldGenLevel` 之后才存在（`StructureStart.java:80-96`）。因此本模组不会为这类结构伪造方块或模板预览。
- **不会伪造数据**。重放只对“扫描确认没有已存盘 start”的候选区块执行：调用维度自己的 `ChunkGenerator.createStructures`，在一个**一次性的 `ProtoChunk`** 上跑一遍（`PredictionContext`）。因此分带路由、结构集加权选择、轨道走廊与区域边界两个禁建区都由原代码负责，不在这里复刻一遍。不调用 `getChunk`，不加载区块，不写世界，不在实时世界触发结构生成。
- **绝不与真实存档冲突**。只有三种情况算“未生成”：区域文件不存在、区块槽位为空、区块已存盘但没有该结构的 start。区块存在却**读不出来**（解析失败、`DataVersion` 不符、坐标身份校验失败）一律不算——那种情况是文件和我们的理解不一致，重算可能和真实数据矛盾，宁可这次不显示。
- **预算与缓存**。拼图拼装比读 region 表头贵一到两个数量级。每次扫描只在**最近的**未缓存区块上花固定预算（`StructurePredictor.MAX_CHUNKS_PER_SCAN`，24 个区块），结果按区块缓存（上限 2048 个），新世界随每次刷新逐步补全，去过的地方不再花代价。
- **只重放有意义的距离**：起点区块离玩家超过“可见环外沿 + 128 格”的不生成。
- 已知边界：重放的结论依赖“当前数据包的 placement 参数”。若数据包改动过 `spacing`/`salt` 或结构集成员，已存盘结构可能对不上，未生成区域也会按**当前**数据包计算。

## 协议与刷新

协议版本为 `8`（`SpyglassNetwork`）。共四个包，**没有**结构方块/形状包：

- S2C `SpyglassSnapshotPayload`：维度、序号、结构数，每个结构带结构键（id + start 区块坐标）、总包围盒（6 个 int）、部件数、各部件 BB，以及一个标志字节（bit 0 = 预测；未识别的位直接拒绝解码）。序号用于丢弃乱序/重复快照。
- S2C `SpyglassBusyPayload`：一个布尔，“服务端仍在扫描”。客户端在举镜时显示 `测算中 / Measuring...`。
- S2C `SpyglassSourcesPayload` / C2S `SpyglassSourceEditPayload`：来源开关面板的目录与编辑请求。

服务端每 10 tick 轮询一次使用望远镜的玩家（`SpyglassRefreshPolicy.POLL_INTERVAL_TICKS`）。只在**服务端主线程**做：取玩家位置/维度、把白名单 tag 解析成结构 holder 与 id、向生成器要本次扫描窗口的结构状态、发送快照。快照在下述时机重建：开始使用望远镜、切换维度、相比上次构建快照的位置移动 ≥ **32 格**、距上次构建 ≥ **30 秒**（用于补上这段时间新存盘的结构）；扫描失败后 5 秒重试。候选区块枚举、region 读取与快照限量计算都在名为 `dimblend-spyglass-scan` 的单线程 executor 上执行（队列上限 64）。

快照下发的是**补边后的环**：`[64 − 48, 1024 + 48]`（`PAD = 48`）。因为两次重建之间玩家最多移动 32 格，任何在玩家**当前**位置下应显示的结构相对构建时的状态一定落在补边环内；客户端每帧用实时位置再过滤一次精确的 `[64, 1024]`。`PAD` 必须不小于移动阈值这一约束由 `SpyglassRefreshPolicy` 持有并被单测校验。

## 快照上限

一个快照最多 **256** 个结构、每个结构最多 **4096** 个部件、总计最多 **16384** 个部件（`SpyglassSnapshotPayload`）：

- 结构部件超过单结构上限时，保留**体积最大的前 4096 个**部件而不是丢弃整个结构；
- 总部件数达到上限时，丢弃**更远**的结构：按到玩家的水平距离升序装入，第一个装不下的结构及其后所有更远的结构都丢弃；
- 两种情况都在 DEBUG 记录数量，不刷屏。

快照条目的**总包围盒由服务端在截断之前对全部部件计算并随包下发**，客户端只使用它计算中心与距离，不在每帧重新求并集。因此即使部件被截断，中心与距离仍然精确；解码也会校验包围盒包含所有部件。

## 读取失败上报

region 读取的 IO、解析、坐标身份校验失败逐次计数，只跳过出问题的区块；`DataVersion` 不是当前世界游戏世界数据版本的区块同样计入 `unsupportedDataVersion` 跳过计数。每 60 秒每个维度最多输出一条 WARN（含各类计数与首个异常）。“没有 start”（正常情况）不算读取失败。

## 来源开关

白名单位于 `src/main/resources/data/dimblend_spyglass/tags/worldgen/structure/visible.json`，只列具体建筑 ID 或已核验的建筑 tag，不按整个 namespace 放行；模组 ID 与嵌套 tag 均使用 `{"id":"...","required":false}`，缺少对应模组时不会因可选条目报错。

`SpyglassServerSettings` 是 SERVER 配置（`disabledSources`，存的是**被关闭**的 namespace，新模组默认开启；`minecraft` 永远开启且不会写入该表）。游戏内 `/dbx tune` → Spyglass 面板（需权限等级 2）通过 `SpyglassSourceService` 读写；手改配置文件由 reloading 监听器接住并让下一次扫描重建。客户端只拿到目录数据，编辑在服务端校验权限与 namespace。

**不收录**：完全地下的内容（远古城市、要塞、试炼密室、所有 ID 带 `underground` 的条目、`#betterdungeons:better_dungeons`、`dungeons_arise:mining_system`）与废弃矿井（`minecraft:mineshaft` 系列、`#bettermineshafts:better_mineshafts`）：远景没有可看的外观，矿井还是每区块候选、部件海量的开销大户。仅由 Feature 生成的内容（如暮色森林的 `druid_hut`、`well`、`grove_ruins` 等）不伪造结构 ID。

## 客户端结构

- `SpyglassRevealState`：只认 `FOG_TERRAIN` 且 `FogType.NONE`、相机实体没有失明/黑暗效果时才启用；否则整体关闭（标记、距离、箭头都不画）。内沿固定 64，这个状态不再参与筛选。切换世界、切换维度与退出登录由 `SpyglassClientState#clear` 一起复位。
- `SpyglassClientState#currentStructures()`：当前帧、玩家实时位置下、中心距离 ∈ `[64, 1024]` 的结构列表（只是数据，不含门控）；雾不可用时返回空列表。
- `SpyglassScopeGate#isScoping`：第一人称、无 screen、未隐藏 GUI、`player.isScoping()`。所有客户端层共用这一个门控。
- `MarkFramePublisher`（AFTER_PARTICLES）→ `MarkFrame`：本帧 64–1024 环内的全部结构、选中结构保存的锚点、`projection × modelView`、`modelView` 与相机位置；并在这里按实时位置与雾可用性对选中做“真越距”检查。
- `StructureMarkLayer`（`CAMERA_OVERLAYS` 之下）→ `MarkScreenFrame`：投影、重叠、画 `?`/`!` 与 `[?]`/`[!]`，发布本帧的屏幕数据（含 `StructureKey`、框宽、是否画出、是否在镜片内、相机空间方向、发布时间）。
- `CrosshairDistanceLayer` 与 `EdgeArrowLayer`（`CAMERA_OVERLAYS` 之上）读 `MarkScreenFrame` 画顶部距离与箭头；`MeasuringHud` 画 `测算中`。
- `StructureSelection`：单选状态与纯决策（同键取消/异键替换、按键刷新、越距清除、clear）；`StructureMarkInput` + `MarkClickDecision`：左键 PRESS 的纯决策与取消；`MarkerGlyph`、`MarkLayout`、`LabelPlacement`、`CrosshairPicking`、`WorldToGuiProjection`、`EdgeArrowGeometry` 都是可单测的纯逻辑。
- 服务端：`SpyglassServerService`（轮询、扫描线程、快照下发）、`StructureCandidates`/`StructurePredictor`/`PredictionContext`（候选、重放）、`ReadOnlyRegionScanner`/`ReadOnlyRegionFile`（只读 region）、`SpyglassSnapshotLimiter`（上限）。

## 构建与测试

在仓库根目录执行，确保 `JAVA_HOME` 指向 JDK 21：

```sh
./gradlew :dimblend-spyglass:test :dimblend-spyglass:jar
```

JAR 输出到 `dimblend-spyglass/build/libs/dimblend_spyglass-0.1.0.jar`。

单测覆盖：标记身份与选中切换（同键取消/异键替换/`?`→`!` 保持键）、距离依据与 64/1024 边界清除、雾禁用不误清、`clear` 复位、输入纯决策（PRESS/RELEASE/长按锁存/各门控/空白与过期帧）、屏幕框命中（只认已画出的、选中宽框、最近优先）、箭头目标（选中优先、镜片内无箭头、被隐藏仍可定位）、字形与框宽、投影与快照编解码。

## 已知局限

- **没有方块、没有模板预览**：只有标记、顶部距离与箭头。未生成结构只有位置与轮廓，不画建筑本体，也不会为了“看起来像”而伪造方块。
- **只能看到镜片里的标记**：标记图层在原版镜片遮罩之下，镜片外的标记被自然裁掉；箭头与距离画在遮罩之上。
- **选中可能比快照活得久**：选中保存的是稳定世界位置，快照上限丢弃它、或扫描间隔内没刷新它时，标记仍按保存的锚点画，直到中心距离真正越出 64–1024。若结构在服务端被删除，选中也会在下一次越距时消失。
- **选中是每个客户端各自的**：不同玩家看到的结构相同，选中的是各自屏幕上的标记。
- **快照有时间窗**：两次重建之间（最多 30 秒）新存盘的结构不会立刻出现；玩家移动超过 32 格会触发重建。
- **超大结构可能漏检**：候选区块窗口为玩家周围 ±99 区块（可见环外沿 1072 格 + 512 格起点余量）。结构起点区块离其总包围盒中心超过约 512 格、且中心在远侧时，起点不在窗口内。
- **雾/流体/失明时什么都不显示**：水下、岩浆、细雪、失明/黑暗效果下整块功能关闭，这是刻意保留的旧行为。
