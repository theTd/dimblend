# DimBlend Spyglass

Minecraft 1.21.1 / NeoForge 的望远镜远景建筑提示模块，依赖 `dimblend` 主模块。

## 使用

在**第一人称**视角下使用望远镜，可以看到两类远景结构标记：

- **显示范围固定为总包围盒中心水平距离 128–1024 格（含边界）**。内沿 128 格不再随渲染距离或雾变化；128 格以内不标，避免挡住眼前的东西。
- 每个在范围内、且通过来源开关的结构，在包围盒顶面中心上方显示一个标记：存档里读到它的 start 时是 `!`，只有种子重放算出来时是 `?`（见「结构标记」）。
- 模板可解析的 `?` 结构会显示结构本体的真实方块（模板预览）；模板不可用（Feature-only、矿井/要塞等程序化结构）时仍只画线框。
- 已存盘的结构画成**带贴图的真实方块**（见「真实方块渲染」）；未生成区块里的结构只画**线框**（见「未生成结构的标注」），不画方块虚影。没有形状的结构仍然有 `!` 标记，只是没有方块。
- 准星对准某个标记时，屏幕顶部显示 `~Nm`（四舍五入到 10 格）；移开即消失。
- 最近的 `?` 不在镜片内时，屏幕边缘会出现一个指向它的箭头；`!` 不参与箭头选择。
- 不再显示结构名，也没有「（未生成）」后缀。
- 相机在水下、岩浆、细雪里，或有失明/黑暗效果时，雾类型不是 `NONE`，本模组整体关闭显示。

联机时服务端和客户端都需要安装本 mod 与 `dimblend` 主模块。结构数据由服务端读取并下发，仅安装客户端无法使用。

## 结构标记

- **锚点**：总包围盒（`bounds()`，不是部件列表）顶面中心的正上方：X/Z 取 `(min + max + 1) / 2`（`OutlineBox#centerX/Z`），Y 取 `maxY + 1`。每帧用**本帧**的相机位置、投影矩阵（含望远镜 FOV 缩放与视角摇晃）和 model-view 投影到屏幕；相机后方或屏幕外时不画。`RenderLevelStageEvent` AFTER_PARTICLES 记录，同一帧的 GUI 层消费（`MarkFrame` → `StructureMarkLayer`）。
- **字形**：`?`（预测，未生成）或 `!`（存档读到），ASCII、白色带阴影、无底板；字形选择是纯函数 `MarkerGlyph.of(boolean)`。
- **哪些结构有标记**：`SpyglassScopeGate#isScoping` 为真、雾可用（`SpyglassRevealState#isEnabled`）、且结构在 `SpyglassClientState#currentStructures()` 的 128–1024 环内。**不要求本帧画出了网格**：形状还没流式到达、超预算或为空的结构也有 `!`；`?` 在模板可用时有预览网格，没有模板时只有线框。
- **望远镜遮罩**：标记图层注册在 `VanillaGuiLayers.CAMERA_OVERLAYS` **之下**，原版镜片贴图与四块黑边在它之后绘制，自然裁掉镜片外的标记；不复制任何遮罩几何。
- **重叠**：按中心距离由近到远贪心放置，16×16 GUI 像素框与已放置标记（含 2 像素间隔）重叠的跳过本帧；按距离而不是离准星远近排序，转动视角时优先级稳定。
- **顶部距离**：本帧被画出的标记里，准星落在 16×16 框 + 4 像素容差内时，屏幕顶部居中显示 `~Nm`（`CrosshairPicking` 取离屏幕中心最近的命中）。`?` 与 `!` 都参与。
- **边缘箭头**：只从 `?` 里取中心距离最近、且不在镜片内缩矩形里的一个；`!` 不参与。目标在相机前方用透视方向，在身后按 `camX` 的符号取左/右（正后方固定指右）。箭头画在屏幕四边内侧、遮罩之上。

### 结构名与语言键（保留数据）

结构名已经不再显示（标记层只画 `?`/`!`），但 `assets/dimblend_spyglass/lang/en_us.json` 与 `zh_cn.json` 里的 `dimblend_spyglass.structure.<namespace>.<path>` 键仍然保留：这份表覆盖白名单里全部 `minecraft:` 直列条目，也保留了已从白名单移除的远古城市/要塞/试炼密室键，以后要恢复名字时无需补翻译。`StructureLabelLangTest` 只校验两份文件的键集一致与这些名字的存在，**没有生产代码读取它们**。


## 只读边界

结构查询只读已有 region 文件：结构 start（`structures.starts` 及其 `Children` 部件 BB），以及这些结构**覆盖区块**的 `DataVersion`、`xPos`、`zPos`、`Status` 和 `sections`（每段的 `Y`、`block_states`、`biomes`，用于取真实方块形状）。查询路径严格遵循以下限制：

- 只用 `StandardOpenOption.READ` 打开 region / `.mcc` 文件；不经过原版 `RegionFile`（它可能写回或修复表头），不创建目录；
- 不调用 `getChunk` 补取区块；
- 不加载新区块，不触发区块生成；
- 不读取**实时世界**的方块，也不按实时方块重建形状；方块数据只来自已存盘 chunk 的 `sections`；
- 已有 region 中没有保存 start 的结构**不补算方块**，但会按种子**标注位置**（见下节「未生成结构的标注」）。

region 表头在打开文件时快照、扇区稍后才读，服务端并发写盘理论上可能让扇区被回收复用。因此生产读取路径同时读取区块自身的 `xPos`/`zPos` 并与请求区块比对，不一致的区块按读取失败丢弃，不会把别的区块的 start 当成这个区块的结构。区域表头的第二张 4 KiB 表（时间戳，`RegionFile.java:60-63,281,312`，索引与偏移表相同，`RegionFile.java:352-354`）也在同一快照中读取，用于形状修订号。

只读取由**当前游戏版本**保存的区块：区块的 `DataVersion` 必须等于当前世界数据版本，其它版本（更旧或更新）一律跳过并计入下方 WARN 的 `unsupportedDataVersion`，不做数据升级。这条规则对结构 start 与形状读取同样适用。

## 未生成结构的标注（种子重放）

玩家从未去过的地方，磁盘上没有区块数据，原版也就没有 start 可读。这类位置本模组会按种子**重放世界生成**，把结构的位置与尺寸算出来，默认画**线框标记**；M2 起，模板可解析的拼图结构还会画**本体方块预览**（见下）。

- **能算出来的是位置，不是建筑**。结构放置是种子与数据包的纯函数：`Structure.generate` 由结构的高度提供器选起点、拼装拼图模板，返回部件 BB（`Structure.java:91-100`），完全不需要读世界——`/locate` 走的就是这条路（`StructureCheck.java:97-110`）。但**方块**只有在 `StructureStart.placeInChunk` 把方块写进 `WorldGenLevel` 之后才存在（`StructureStart.java:80-96`），生物群系装饰还要读周围区块的真实生物群系。所以这里能确定的是“这座建筑在哪、多大”。
- **模板预览（M2，CRD 0001）**：拼图结构的所有部件都是 `PoolElementStructurePiece`、且每个部件引用的 `SinglePoolElement` 有数据包模板 id 时，服务端从 `StructureTemplate` 取**第一套调色板**的方块，按部件位置与旋转把它们变换到世界坐标，过滤掉结构方块/拼图方块/结构空位/空气，然后编码成普通形状包下发。客户端把它当真实方块画；`?` 标记保留，用来声明“这是预测，不是存档里的建筑”。
- **为什么不做完整虚影**：结构模板只给出建筑本体；处理器（重力、随机替换）、水logged、战利品、方块实体、第二套调色板都不还原，周围地形也还不存在，所以预览会浮空，和真实生成后的样子可能有差异。Feature-only、矿井/要塞等程序化结构没有模板，继续画线框。把它画成“完整建筑”反而会让玩家误判。
- **预览预算**：每次快照最多给最近的 **8** 个 `?` 做预览（`PreviewBudget`），其余仍是线框；模板提取在扫描线程上只做模板 NBT 的读取与变换，不读区块、不生成地形。
- **不会伪造数据**。重放只对"扫描确认没有已存盘 start"的候选区块执行：调用维度自己的 `ChunkGenerator.createStructures`，在一个**一次性的 `ProtoChunk`** 上跑一遍（`PredictionContext`）。因此分带路由、结构集加权选择、轨道走廊与区域边界两个禁建区都由原代码负责，不在这里复刻一遍。不调用 `getChunk`，不加载区块，不写世界，不在实时世界触发结构生成。

- **绝不与真实存档冲突**。只有三种情况算"未生成"：区域文件不存在、区块槽位为空、区块已存盘但没有该结构的 start。区块存在却**读不出来**（解析失败、`DataVersion` 不符、坐标身份校验失败）一律不算——那种情况是文件和我们的理解不一致，重算可能和真实数据矛盾，宁可这次不显示。
- **预算与缓存**。拼图拼装比读 region 表头贵一到两个数量级，一次扫描窗口约 200×200 区块，全部重放会卡住扫描线程。所以每次扫描只在**最近的**未缓存区块上花固定预算（`StructurePredictor.MAX_CHUNKS_PER_SCAN`，24 个区块），结果按区块缓存（上限 2048 个），新世界随每次刷新逐步补全，去过的地方不再花代价。缓存不需要失效逻辑：区块一旦有了已存盘 start 就不再算"未生成"，也就不会再被查。
- **只重放有意义的距离**：起点区块离玩家超过"可见环外沿 + 补边 + 128 格"的不生成——它的部件盒再怎么延伸也到不了可见范围。
- **标记**：这类结构显示 `?`（存档里没有 start、种子重放算出来）；模板可解析时同时有本体方块预览，没有模板/预览未就绪时只有线框。它和其他结构一样受 `[128, 1024]` 环与标记防重叠规则约束。
- **协议版本 7**：快照里每个结构带一个标志字节（bit 0 = 预测），未识别的位一律拒绝解码；协议 6 把内沿固定为 128 并删掉了 reveal 距离包；协议 7 让服务端开始为 `?` 下发模板预览形状（复用 `SpyglassShapePayload`，没有新包类型）。

已知边界：重放的结论依赖"当前数据包的 placement 参数"。若数据包改动过 `spacing`/`salt` 或结构集成员，已存盘结构可能对不上（同「已知局限」里那条），未生成区域也会按**当前**数据包计算。

## 结构形状（阶段一：数据通路）

望远镜还会把白名单结构**真实生成出来的方块**从服务端流式发给客户端缓存，阶段二用这些方块做网格化渲染（见「真实方块渲染」）。数据规则：

- **覆盖区块**：与该结构**任一**部件（`Children` 的 BB）相交的所有区块，不是总包围盒覆盖的区块；形状判定与提取都用扫描得到的完整部件表，不受快照的部件截断影响。
- **只算已生成**：每个覆盖区块都必须存在于 region 文件中，且 `Status` 达到或晚于 `minecraft:features`（`ChunkStatus.byName(...).isOrAfter(ChunkStatus.FEATURES)`）。structure start 最多提前 8 个区块计算（`ChunkPyramid.java:14-37`），方块只在 FEATURES 阶段放置（`ChunkStatusTasks.java:154` → `ChunkGenerator.applyBiomeDecoration` → `ChunkGenerator.placeInChunk`），因此很多已保存的 start 还没有方块：这类结构没有形状，客户端不显示它，服务端在 DEBUG 计数。
- **逐方块过滤**：方块必须位于至少一个部件盒**内部**（不是总包围盒内部）、不是空气、不是纯流体方块（跳过 `LiquidBlock` 状态；含水方块 `waterlogged=true` 保留，因为它是真实生成的方块状态）。
- **可见性过滤（埋在土里的方块不下发）**：保留方块之后、编码之前，用覆盖区块（加**一圈邻区块**，只读它们的 `Heightmaps.WORLD_SURFACE`，缺失时回退 `WORLD_SURFACE_WG`）的存档地表高度 H(x,z) 做一次可见性搜索。`H` 是每列最高非空气方块**上方**的第一格 Y（与 `Heightmap.java:111-116` 一致；水也算非空气，所以水下结构的 H 在水面）。规则：
  - y ≥ H(x,z) 的格子是露天种子；H 以下、位于任一部件盒内部的格子在没有保留方块、或保留方块 `canOcclude()` 为假（树叶、玻璃等）时是可通过的；部件盒外一律视为地形、不可通过。
  - 从种子出发，按每个 y 层做最多 **8 步**的水平 4 邻接搜索；方块自身的格子（含露天）或它的 6 个邻居被搜索到才保留。
  - 因此整座埋在地下的结构变成空形状（客户端不显示）；地表建筑的墙、屋顶与屋檐下外墙保留，封闭房间内部的地板/空气搜不到；贴着露天竖井/峡谷的结构只露出紧邻竖井的那一层方块；水下结构整座不可见。
  - 体积（保留方块包围盒六向各扩 1 格）超过访问格子预算时该结构不生成形状，计入 `visibilityVolumeTooLarge`，**绝不**下发未过滤的方块。邻区块高度图缺失、未生成或读不出时那些列按“封闭”（H = +∞）处理，宁可少显示也不把地形当成空气。
- **上限**：单个结构最多保留 262,144 个方块；超过则该结构没有形状并 DEBUG 记录。覆盖区块数还有 1024 的上限（高度图邻环同样受它约束），防止损坏或异常巨大的 start 让一次扫描变成上千次文件读取。形状提取另有以下上限与中止规则：
  - 单个结构最多 **16,384** 个部件（完整 `Children` 表，不是快照截断后的部件）；超过则该结构没有形状，按 `unencodable` 计入 DEBUG 汇总。
  - 单个结构最多访问 **16,777,216** 个格子，按每个部件与 section 的交集体积累加，**空气也计入**；超过即中止该结构，同样没有形状。它挡住的是"部件盖住巨大空体积"的损坏 start——这种结构虽然留下的方块很少，遍历代价却极高。可见性搜索的体积（保留方块包围盒六向各扩 1 格）也受同一个预算约束。
  - 一次扫描最多产出 **32 MiB** 编码形状字节（缓存命中的形状也计入）；按快照顺序由近到远保留，超出的结构本次扫描没有形状并 DEBUG 记录。
  - 扫描线程在每个结构、每个区块、每个 section 检查中断，服务端停止时正在进行的形状提取会尽快结束（不等待整轮 region 读取完成）。
- **生物群系**：取保留方块所在 section 的多数生物群系，按**用到的 section** 加权（每个贡献过方块的 section 计入整整 64 个生物群系单元），以注册表键下发，客户端用本地注册表解析成 `Holder<Biome>` 供着色。
- **修订号（revision）**：覆盖区块在 region 表头时间戳中的最大值（epoch 秒）。区块重新存盘会推进修订号，因此形状缓存与新包能识别"这个结构变过了"。
- **服务端缓存**：编码后的形状按 `(维度, 结构键, 修订号)` 放进所有玩家共享的 LRU，上限 64 MiB 编码字节；修订号变化后旧条目自然不再命中。
- **方块状态 id**：调色板用 `Block.getId`/`Block.stateById` 的数字 id（比注册名小得多）。两端 id 一致由 NeoForge 保证：方块注册表在 `NeoForgeRegistriesSetup.VANILLA_SYNC_REGISTRIES` 中（`NeoForgeRegistriesSetup.java:34`，注释即"chunk BlockState paletted containers syncing"），`modifyRegistries` 对它 `setSync(true)`（`:76-77`），同步后的 bake 回调重建方块状态 id 表（`NeoForgeRegistryCallbacks.java:48-60`）。原版区块包的调色板依赖的就是同一张 id 表，因此形状体不再另带校验。
- **客户端缓存**：重组后的形状按结构键保存最新修订号，LRU 上限 4,000,000 个缓存方块；维度切换、断开连接、切换世界时与快照缓存一起清空（`SpyglassClientState#clear`）。形状对象不可变，可以交给工作线程做网格化。超预算时先淘汰**不在当前快照**（补边环内全部结构，不只是显示环）里的形状，按最近最少使用顺序；快照内的形状**永远不会**为给另一个形状腾地方而被淘汰——若快照内的形状自己就超过预算，则**拒绝新到的形状**（不缓存、DEBUG 记录），该结构不显示。同一结构键与修订号再次到达时只刷新它的 LRU 位置，不改变缓存内容。

## 协议

协议版本为 `7`（`SpyglassNetwork`）。版本 7 让服务端开始为 `?` 下发模板预览形状（复用 `SpyglassShapePayload`，没有新包类型）；6 的客户端会存下但不会画，所以协议必须一起升。版本 5 给快照里每个结构加一个**标志字节**（bit 0 = 该结构是种子重放的预测结果，不是从存档读到的），未识别的位直接拒绝解码。版本 6 把显示环内沿固定为 128 格，删除了版本 4 引入的客户端 → 服务端 `SpyglassRevealPayload`（揭示距离）——内沿不再由雾决定，服务端也就不再需要这个值。快照 `SpyglassSnapshotPayload` 每个结构除总包围盒与部件 BB 外还带**结构键**（结构 id + start 所在区块）；形状用 S2C `SpyglassShapePayload` 传输，分包字段为维度、结构键、修订号、`partIndex`/`partCount` 与一段形状体。形状体包含 origin（最小角）、size、生物群系键、方块状态 id 调色板和按位置排序、delta 编码的方块列表。预测结构在模板预览可用时**有**形状包（M2）；没有模板的预测结构没有形状包，客户端也不会为它请求网格。

- 分包大小与每玩家每 tick 的 64 KiB 流式预算对齐：一包（含包头）正好是一 tick 的预算，因此一个包一 tick，形状按到玩家的水平距离由近到远发送；服务端记录每个玩家已有的 (结构键, 修订号)，只补发缺失或过期的形状。每次新快照都会**丢弃不在该快照内的已发送记录**（待发队列与在途分包同时重建），所以结构离开补边环后再进入会重新发送——客户端可能已经为守住方块预算把它从 LRU 淘汰了，只靠"已发送"记录会让它永远停在老修订号上。维度切换、退出登录、服务端停止时重置。重生会替换客户端的等级（缓存随之清空）但维度 id 不变，因此检测到玩家的 `ServerPlayer` 实例变化时也会忘记已发送记录。
- 解码有界：调色板大小、方块数（≤ 262,144）、分包数、位置是否落在 size 内、是否有重复/越界位置全部校验，违反即抛 `DecoderException`；客户端对不完整或过期的分包只做有界重组（最多 64 个在途形状体、8 MiB 缓冲）。

### 已知局限（形状）

- **形状只代表磁盘上的版本**：读取的是已存盘 chunk。玩家附近的区块可能比磁盘新（尚未保存），显示的仍是上次存盘时的方块。
- **没有"遗忘"包**：如果覆盖区块被删除或在两次刷新之间变得不再满足生成条件，服务端只是不再发送形状，客户端缓存里的旧修订号仍然存在；正常的重新保存会推进修订号并替换。区块从磁盘删除属于极端情况。
- **修订号粒度是秒**：region 时间戳是 epoch 秒。同一秒内重新保存、且覆盖区块中其它时间戳更大时，修订号可能不变，客户端会继续用上一份形状，直到下一次存盘跨秒。
- **覆盖区块上限 1024、部件上限 16,384、访问格预算 16,777,216、单次扫描编码字节 32 MiB**：超过任一上限的异常结构不做形状（不显示），避免一次扫描读取上千个区块、遍历上千万个空气格子或持有超过 32 MiB 的编码形状。
- **可见性过滤后可能整座都不显示**：整座埋在地下的结构、完全被水淹没的结构，以及只有封闭房间内部才有的方块都会被过滤掉；能显示的只有从露天（或贴脸的距离不超过 8 步的开放空间）看得见的表面方块。这是有意的：宁可看不到，也不把建筑画成埋在土里的实心方块。高度图缺失/损坏的区块按“封闭”处理，也会让紧邻它的那部分结构少显示。

### 已知局限（结构检测）

- **超大结构可能漏检**：候选区块窗口为玩家周围 ±99 区块（可见环外沿 1072 格 + 512 格起点余量）。结构起点区块离其总包围盒中心超过约 512 格、且中心在远侧时，起点不在窗口内，结构不会显示。原版结构远小于此；个别超大拼图结构模组可能受影响。

## 真实方块渲染（阶段二）

### 画什么、什么时候画

- 只在 `SpyglassScopeGate#isScoping` 为真、且雾可用（`SpyglassRevealState#isEnabled`）时绘制。当前显示的结构（128–1024 环内）若客户端已有形状（`SpyglassShapeCache#shapeFor` 非空）且网格已上传，就用原版方块模型画成带贴图的真实方块（`SpyglassShapeRenderer`）。
- 没有形状（未生成、超上限、尚未流式到达）；有形状但网格还在构建或等待上传；网格为空（结构只剩方块实体渲染的方块）；构建失败或超过单结构顶点上限；本帧整体在视野外——这些都**什么都不画**（标记层仍会画出 `!`，因为它不依赖网格）。修订号变化时旧网格继续显示，新网格上传后旧缓冲立即关闭。
- 选中的网格每帧按 `ShapePassSelector` 分成两组，选择、视锥剔除、`ShapeMeshStore` 标记录制与网格构建请求都只在 `AFTER_SKY` 这一处做：
  - **OVERLAY**：最近水平距离 < 已加载半径（`options.getEffectiveRenderDistance() * 16`）+ 16 格，且最远角距离 < `0.95 × gameRenderer.getDepthFar()`，即建筑已经处在已加载地形里（含雾中地形），改在 `AFTER_PARTICLES` 以近处雾浓度叠画在地形之后；
  - **FAR**：其余结构，保持原来的 `AFTER_SKY` 行为。

### 网格化

- 专用单线程 daemon 执行器 `dimblend-spyglass-mesh`；每个 (结构键, 修订号) 至多构建一次（`ShapeBuildTracker`），失败或超限的修订号在下次清空前不重试。
- 假 `BlockAndTintGetter`（`StructureShapeBlockGetter`），只读不可变数据，不碰客户端世界：
  - 方块来自形状，其它位置是空气，因此两个保留的整方块之间的面照原版 `Block.shouldRenderFace` 剔除，朝外的面保留；
  - 不报告流体，含水方块只画方块模型；
  - 天空光 15、方块光 0；`LevelRenderer.getLightColor` 仍把方块光抬到方块自身亮度，发光方块照常发亮。天空光 15 采样光照贴图的昼夜列，建筑和室外地形一样随昼夜变化；
  - 着色用形状的主生物群系，缺失时回退客户端注册表里的平原；
  - 面明暗（`getShade`）与高度取客户端世界的值，在渲染线程捕获后交给工作线程。
- 逐方块（只处理 `RenderShape.MODEL`）照原版区块编译器（`SectionCompiler`）的做法：按位置设随机种子，对 `getRenderTypes(state, random, ModelData.EMPTY)` 报告的每个区块层调用 NeoForge 的 `BlockRenderDispatcher.renderBatched(..., ModelData.EMPTY, renderType)`；每层一个 `ByteBufferBuilder` + `BufferBuilder`（`DefaultVertexFormat.BLOCK`，四边形），顶点相对形状原点。半透明层在构建时按请求时的相机位置从远到近排序一次。不跑方块实体渲染器。单个方块的模型抛异常时跳过该方块，WARN 一次。
- 上传在渲染线程：每个非空层一个静态 `VertexBuffer`，上传后释放原生内存。

### 深度与遮挡

- **FAR 组**的绘制时机是 `RenderLevelStageEvent.Stage.AFTER_SKY`：天空之后、地形雾与地形 pass 之前，画进本帧绑定的主帧缓冲。Fabulous 下 translucent / tripwire 层原本输出到稍后才清空的额外目标，绘制时改绑回主目标。
- FAR 组的专用投影：保留本帧投影的 FOV（含望远镜缩放）、宽高比与视角摇晃，只替换深度范围（`SpyglassProjection#withDepthRange`，对摇晃矩阵精确）：near = clamp(最近可见网格包围盒距离 × 0.5, 0.05, 128)，far = max(4096, 最远角距离 + 64)（`ShapeDepthRange`）。24 位深度缓冲在 near = 0.05 时 1000 格处一级深度约 1.2 格，真实网格会严重 z-fighting；near = 128 时不到 1 毫米。model-view 用本帧的（相机旋转），结构原点相对相机的平移走区块着色器的 `ChunkOffset` 统一量，与原版逐区块绘制相同。
- **OVERLAY 组**在 `RenderLevelStageEvent.Stage.AFTER_PARTICLES` 绘制，此时地形、实体和粒子都已经写进深度缓冲：使用事件自带的、**未修改**的投影与 model-view，深度值与缓冲里的真实地形一致；深度测试 `LEQUAL`、开启深度写入，并照原版 `POLYGON_OFFSET_LAYERING`（`RenderStateShard.java:274-282`）先 `RenderSystem.polygonOffset(-1, -10)` + `enablePolygonOffset()`，绘制后关闭并归零，让建筑的面压过与它共面的真实地形；雾由 `ShapePassFog` 换成本帧真实地形雾的起点/终点/形状与雾色，并按近处雾浓度（`SpyglassClientConfig`）给每个网格上传 `FogColor` 的 alpha：建筑在雾起点前保持清晰，进入雾带后只混入一部分雾色，而不是完全无雾。这个 pass **不清深度**，绘制后恢复它改动的每个雾值、uniform 与 GL 状态（多边形偏移、深度测试/函数/写掩码）。OVERLAY 列表由同帧的 `AFTER_SKY` 计算，`AFTER_PARTICLES` 只核对帧号后绘制并立即清空，不会画上一帧的列表。
- 两个 pass 共用同一套分层与 `ChunkOffset` 绘制逻辑（`drawLayers` → `drawLayer`）：按 solid → cutout_mipped → cutout → translucent → tripwire 绘制，每层用自己的 `setupRenderState()`/`clearRenderState()` 与着色器；不透明层由近到远，混合层由远到近。两个 pass 都在 pass 开始时换入最后一次 `FOG_TERRAIN` 的雾起点/终点/形状与本帧雾色，逐网格上传 alpha，并在每层网格循环后把 `FogColor` 与 `ChunkOffset` 一起复位，pass 结束后恢复所有雾值。
- FAR 组画完后**清空深度缓冲**。天空不写深度，帧开头已清过深度，所以清空后交给地形 pass（原版或 Sodium）的深度状态与没装本 mod 时完全一样。结果是凡有真实地形的位置地形都盖在 FAR 建筑上：近处的山挡住远处建筑；渲染距离以外（没有地形）建筑可见。OVERLAY 组不清深度，它本来就要压在已雾化的地形上（再按浓度叠一层同色雾）。

### 雾浓度（可调）

- **模型**：两个 pass 都不再关闭雾，而是使用最后一次 `FOG_TERRAIN` 的起点、终点与形状（`SpyglassRevealState`；FAR 在 `AFTER_SKY` 时读到的是上一帧地形 pass 的值，与地形雾同源），雾色 RGB 取本帧地形着色器实际使用的关卡雾色（`FogRenderer.setupColor` → `levelFogColor`，读取 `RenderSystem` 当前雾色）。原版 `fog.glsl` 的混合量是 `fogValue * FogColor.a`，所以每个网格上传的 `FogColor` alpha 就是它吃到的真实地形雾比例：**0 = 旧版的清晰无雾，1 = 与真实地形完全相同的雾**。
- **OVERLAY（已加载地形内）**：alpha 固定为 `nearFogStrength`。雾起点之前原版 `fogValue` 为 0，所以建筑仍然清晰；进入雾带后最多混入 `nearFogStrength` 的雾色，不会变成纯背景色。
- **FAR（已加载地形外）**：alpha = `nearFogStrength + (farFogStrength - nearFogStrength) × clamp((d - fogEnd) / (1024 - fogEnd), 0, 1)`，其中 `d` 是相机到网格包围盒**水平中心**的水平距离；`fogEnd >= 1024`（含非有限值）时直接用 `nearFogStrength`。每个顶点仍按自己的真实距离采样原版雾曲线，这条 ramp 只决定整块网格拿到多少雾。
- **设置**（`SpyglassClientConfig`，NeoForge `ModConfig.Type.CLIENT`）：
  - `nearFogStrength`：double，默认 `0.5`，范围 `0..1`。已加载地形内与雾边缘处结构叠加的地形雾比例。
  - `farFogStrength`：double，默认 `0.7`，范围 `0..1`。1024 格处的雾比例，从雾终点的 near 值线性升到它。
- **在哪里改**：游戏内 **Mods（模组）→ DimBlend Spyglass → Config（配置）**。渲染线程每个 pass 都重新读值，改完立即生效，无需重启；也可以直接改 `config/dimblend_spyglass-client.toml`，文件监听会热加载。设置页的文字与提示由 `assets/dimblend_spyglass/lang/*.json` 里的 `dimblend_spyglass.configuration.*` 键提供。
- **实现**：`ShapeFogStrength` 是纯函数（端点、夹取、`fogEnd >= 1024`、NaN/负数均有单测）；`ShapePassFog` 在 pass 开始时保存全部雾值（起点、终点、形状、雾色含 alpha），换入地形雾，逐网格设置并上传 `FogColor` 的 alpha，循环后把 uniform 复位，pass 结束后恢复所有保存值。

### 预算

- 构建：最多 2 个在途（排队 + 运行），每帧最多发起 1 个，优先视野内最近的结构。
- 上传：每帧最多 2 个网格，且第一个之后累计不超过 1,000,000 顶点（避免一帧内连传两个超大网格卡顿）。
- 网格缓存 `ShapeMeshCache`：每个结构键一份（新修订号替换旧的），LRU，总顶点上限 8,000,000（BLOCK 格式每顶点 32 字节，约 256 MiB），单结构上限 2,000,000 顶点，条目上限 1024；离开缓存的网格立即关闭 GPU 缓冲。只有本帧实际画出的网格才刷新"最近使用"，所以内存紧张时先淘汰视野外的网格；本帧或上一帧画过的网格不会被淘汰，放不下时拒绝新网格，约 600 帧后重试。
- 绘制循环不分配对象。
- 清空：与 `SpyglassClientState#clear` 相同（维度切换、断开连接、切换世界）；另外资源重载（模型与图集 UV 变化）、切换图形品质或平滑光照时也清空重建。非渲染线程调用时推迟到渲染线程执行（删除 GL 缓冲必须在渲染线程）。

### 已知视觉局限

- **没有方块实体**：箱子、告示牌、旗帜、头颅、Create 动力方块等由方块实体渲染器（BER）绘制的内容不显示。
- **没有流体**：水和熔岩不画；海底神殿等只显示建筑本体。
- **固定的白天式光照**：处处天空光 15，建筑内部和地下的真实暗处不体现，只随昼夜整体明暗变化。
- **刚改动的建筑可能短暂显示旧存档**：数据只来自已存盘 chunk，而原版在区块改动后约 10 秒才存盘（`ChunkMap.saveChunkIfNeeded`，`ChunkMap.java:711-735`，冷却 10,000 ms 在 `:726`）。这段时间里 OVERLAY 会继续按上次存盘的方块画在真实建筑上，改动处可能看到旧墙/旧地板，最多持续到下一次存盘与下一次扫描。
- **半透明方块会叠在真实方块上**：OVERLAY 是画在已经画好的地形/建筑之上的，玻璃等半透明方块会与下面那层真实方块叠加，看起来比真实建筑更亮/更花；FAR 组不存在这个问题（它在没有地形的渲染距离外）。
- **水下看不到结构**：相机在水下、岩浆或细雪里时雾类型不是 `NONE`，有失明/黑暗效果时雾被效果雾接管；这些情况下本模组直接关闭显示（标记、距离、箭头都不画）。这是刻意保留的旧行为，不是内沿逻辑的一部分——内沿已经固定为 128。
- **128 格内的建筑不标**：这是固定内沿；128 到雾起点之间已加载地形里的建筑仍会被存档方块叠画，渲染距离越大这段越明显（假光照、玻璃/水叠两层）。
- **地下结构**：可见性过滤已经把埋在土里的方块剔掉；在渲染距离以内，剩余的表面方块由真实地形深度遮挡，只有渲染距离以外（没有地形）才会透过地面看到地下结构露出的表面。
- **悬空感**：渲染距离以外的周围地形不画，建筑可能看起来浮在空中。
- **云、天气、雾**：FAR 组画完后深度已清空，云和雨雪画在 FAR 建筑前面；OVERLAY 组写入了深度，云/雨雪会被它挡住。两个 pass 都按「雾浓度」吃真实地形雾（默认近处 0.5、远处 0.7），不再与渲染距离边缘的雾化地形脱节；把两项都设为 0 就回到旧版的清晰外观。
- **依赖 `ModelData` 的模型**（连接纹理等）按 `ModelData.EMPTY` 画成默认外观。
- **相机在超大结构包围盒内或紧挨着时**，FAR 组的 near 退回 0.05，同帧较远的网格可能出现 z-fighting。
- **形状只代表磁盘版本**（见上文）：渲染距离以内若真实建筑已被改动，被拆掉的位置会露出旧方块（那里没有地形覆盖）。
- **模板预览会浮空**：`?` 的模板预览只有结构本体，没有地形；处理器、水logged、战利品、方块实体、第二套调色板不还原，和真实生成后的样子可能有差异。模板不可用（Feature-only、矿井/要塞等程序化结构）时仍是线框。

## 线程模型

服务端每 10 tick 轮询一次使用望远镜的玩家。**只在服务端主线程**做：取玩家位置/维度、把白名单 tag 解析成结构 holder 与 id、向生成器要本次扫描窗口的结构状态、发送快照与形状包。候选区块枚举（可能上万次 placement 判定）、region 读取（结构 start 与形状两遍读取、覆盖区块 `PalettedContainer` 解码、高度图读取与可见性搜索、过滤、编码）以及快照限量计算都在名为 `dimblend-spyglass-scan` 的单线程 executor 上执行；形状流式发送按每玩家每 tick 64 KiB 预算在主线程执行，排队的形状已是不可变编码包，发送只是一次 `PacketDistributor` 调用。

方块状态与生物群系解码使用原版自己的 codec（`ChunkSerializer.java:70-72,275-279`），高度图按原版原始布局用 `SimpleBitStorage` 解码（`Heightmap.java:31-36,131-137`），两者都在扫描线程上读冻结的注册表，和原版 IO worker 在后台解码区块的前提一致。

旋转维度（`RotatingChunkGenerator`）按 X 轴分带，每个带使用自己的 delegate 与世界生成状态。主模块的 `StructureStateProvider#structureStatesFor` 在服务端主线程解析"本窗口覆盖哪些带、各自负责哪段区块 X"，并对每个状态调用 `ensureStructuresGenerated()`：

- 原版只在 `ServerLevel` 构造时初始化主生成器状态（`ServerLevel.java:266`）；delegate 的分带状态原版不会初始化，首次读取会在调用线程上跑 128 次生物群系搜索并写非线程安全的 placement 表（`ChunkGeneratorStructureState.java:81-106,170-182`），既卡主线程又与世界生成线程竞争。
- 初始化之后，候选枚举只做纯读（`getPlacementsForStructure` / `getRingPositionsFor` / `isStructureChunk` / `getPotentialStructureChunk`），可以安全地放到扫描线程。

## 刷新与补边

服务端只在以下时机重建快照：开始使用望远镜、切换维度、相比上次构建快照的位置移动 ≥ **32 格**、或距上次构建 ≥ **30 秒**（30 秒计时用于补上这段时间新存盘的结构）。显示环的内沿固定为 128 格，所以不再有「揭示距离变化」这个触发条件。

快照下发的是**补边后的环**：`[128 − 48, 1024 + 48]`（`PAD = 48`）。因为两次重建之间玩家最多移动 32 格，任何在玩家**当前**位置下应显示的结构，相对构建快照时的状态一定落在补边环内；客户端每帧用实时位置再过滤一次精确的 `[128, 1024]`（总包围盒中心距离）。`PAD` 必须不小于移动阈值这一约束由 `SpyglassRefreshPolicy` 持有，并在单测中校验。

## 快照上限

一个快照最多 **256** 个结构、每个结构最多 **4096** 个部件、总计最多 **16384** 个部件（`SpyglassSnapshotPayload`，协议版本 `6`）：

- 结构部件超过单结构上限时，保留**体积最大的前 4096 个**部件而不是丢弃整个结构；
- 总部件数达到上限时，丢弃**更远**的结构：按到玩家的水平距离升序装入，第一个装不下的结构及其后所有更远的结构都丢弃（不会让更远、部件更少的结构顶替装不下的近处结构）；
- 两种情况都在 DEBUG 记录数量，不刷屏。

快照条目的**总包围盒（union bounds）由服务端在截断之前对全部部件计算并随包下发**，客户端只使用它计算中心与距离，不在每帧重新求并集。因此即使部件被截断，中心与距离仍然精确；`SpyglassSnapshotPayload` 也会校验下发/解码的包围盒必须包含所有部件。

## 读取失败上报

region 读取的 IO、解析、坐标身份校验失败逐次计数，只跳过出问题的区块；`DataVersion` 不是当前游戏世界数据版本的区块（更旧或更新）同样计入 `unsupportedDataVersion` 跳过计数，而不是静默返回空。结构 start 扫描与形状提取两遍读取共用同一组计数（每帧只发一次快照，因此两遍属于同一次请求），每 60 秒每个维度最多输出一条 WARN（含各类计数与首个异常）。形状提取的"没有形状"原因（未生成、超上限、空、无法编码、可见性体积超预算）在 DEBUG 汇总，缓存命中单独计数，都不算读取失败。可见性过滤额外读的一圈邻区块高度图是**尽力而为**：邻区块缺失、未生成或读不出属于正常情况（那些列按封闭处理），不计入读取失败，也不会因此丢掉整个结构；但覆盖区块本身的失败仍按上面的规则计数并让该结构没有形状。

## 建筑白名单

白名单位于 `src/main/resources/data/dimblend_spyglass/tags/worldgen/structure/visible.json`。条目按当前整合包 JAR 中的实际结构 ID 与结构标签核对，主要包括：

- 原版明确的地表建筑与遗迹；暮色森林、TF DNV、森罗暮色、Aether 三地牢、Eternal Starlight、Deeper Darker 与 Voidscape；
- When Dungeons Arise、Seven Seas、Dungeons and Taverns（`nova_structures`）、YUNG 系列地牢/矿井/神庙/要塞/海底神殿/女巫小屋；
- Formations、Terralith、BWG、Atmospheric、Explorations、Wetland Whimsy 的人造建筑与遗迹；
- Create Easy/Rustic Structures、村民交易员房屋、`desert_oasis`、`ruined_lighthouse`、`saloon`、Beautify、Ecologics 等独立建筑。

只列具体建筑 ID 或已核验的建筑 tag，不按整个 namespace 放行。模组 ID 与嵌套 tag 均使用 `{"id":"...","required":false}`，缺少对应模组时不会因可选条目报错。云、湖泊、天然拱门/岩石/化石/树木/矿石/天然尖刺等非建筑不收录。

### 不收录：完全地下的内容

`minecraft:ancient_city`、`minecraft:stronghold`、`minecraft:trial_chambers`、所有 ID 带 `underground` 的条目（`nova_structures:underground_house`、`terralith:underground/*`、`terralith:underground_cabin`、`explorations:underground_temple`、`create_easy_structures:undergroundtrain`）、`#betterdungeons:better_dungeons` 与 `dungeons_arise:mining_system` 已从白名单移除：

- 它们几乎完全埋在地表以下；可见性过滤之后只剩极少数贴着洞口/竖井的表面方块，远景上要么什么都没有，要么只是零碎的一层，没有提示价值。
- 要塞、试炼密室、远古城市与矿井/地下网络规模巨大、部件极多，却换不来可看的外观；移除后也省掉这些结构每次扫描的 region 读取与编码预算。

### 不收录：废弃矿井

`minecraft:mineshaft`、`minecraft:mineshaft_mesa` 与 `#bettermineshafts:better_mineshafts` 明确排除：

- 原版矿井的 placement 是 `spacing = 1`（`StructureSets.java:107-108`），即每个区块都是候选生成点；近 200×200 区块的扫描窗口里，每个结构、每个分带状态要做约 4 万次 placement 判定（且还要再验证一次），完全不成比例。
- 矿井在地下且绵延数千米，轮廓既没有观赏价值，也很容易把玩家所在位置整个包住；排除后既省服务端开销，也避免无意义的海量部件。

### 首版不支持的 Feature-only 内容

首版只处理具有已保存 structure start 的建筑，不支持仅由 Feature 生成的内容，也不会为它们伪造结构 ID。包括暮色森林的 `druid_hut`、`well`、`grove_ruins`、`stone_circle`、`graveyard`、`foundation`、`monolith`，以及 YUNG's Extras、YUNG's Better End Island 的 Feature-only 内容。

## 构建与测试

在仓库根目录执行，确保 `JAVA_HOME` 指向 JDK 21：

```sh
./gradlew :dimblend:compileJava :dimblend:test :dimblend-spyglass:test :dimblend-spyglass:jar
```

JAR 输出到 `dimblend-spyglass/build/libs/`。

## 客户端结构

- `SpyglassRevealState`：观察 `ViewportEvent.RenderFog`（`EventPriority.LOWEST`，只认 `FOG_TERRAIN`），记录地形雾的起点、终点与形状供结构网格复用；只有 `FogType.NONE` 且相机实体没有失明/黑暗效果时才启用，否则整体关闭（标记、距离、箭头都不画）。内沿已固定为 128，这个值不再参与筛选。等级切换、维度切换与退出登录由 `SpyglassClientState#clear` 一起复位。
- `SpyglassClientConfig`：`ModConfig.Type.CLIENT` 的两个雾浓度（`nearFogStrength` 0.5 / `farFogStrength` 0.7），由 `SpyglassClientSetup#register` 在构造期注册，并用 NeoForge `ConfigurationScreen` 提供游戏内设置页；渲染线程每 pass 读取，改完立即生效。
- `SpyglassClientState#currentStructures()`：当前帧、玩家实时位置下、中心距离 ∈ `[128, 1024]` 的结构列表（只是数据，不含门控）；雾不可用时返回空列表。
- `StructureOutline#key()`（`StructureKey`：结构 id + start 区块）/ `#id()` / `#bounds()`（总包围盒）/ `#pieces()`；`OutlineBox#centerX/Z()`（含边界 max 的水平中心）。
- `SpyglassDistance#horizontal(x, z, outline)`、`#MIN_BLOCKS` / `#MAX_BLOCKS`、`#inRange(center)`、`#inSnapshotRange(center, pad)`、`#roundToTen(distance)`、`#format(distance)`。`farthestHorizontal` 已随 reveal 一起删除。
- 复用 `dimblend.spyglass.client` 包内的 `SpyglassScopeGate#isScoping(Minecraft)`（包可见；已包含第一人称、`screen == null`、`hideGui` 判定），不要在别处重写门控。
- `SpyglassShapeRenderer` 的 `AFTER_SKY` pass 负责本帧唯一一次选择、剔除、网格构建请求、FAR 绘制与 OVERLAY 列表；`AFTER_PARTICLES` pass 画 OVERLAY，然后发布 `MarkFrame`（本帧 128–1024 环内**全部**结构 + 本帧 `projection × modelView`、`modelView` 与相机位置）→ `StructureMarkLayer`（GUI 标记，发布 `MarkScreenFrame`）。纯逻辑拆在 `WorldToGuiProjection`（投影到 GUI 坐标）、`MarkerGlyph`（`?`/`!`）、`LabelPlacement`（重叠规则）、`CrosshairPicking`、`DistanceText`、`EdgeArrowGeometry`，均有单测。三个 GUI 图层：`StructureMarkLayer`（`CAMERA_OVERLAYS` 之下）、`CrosshairDistanceLayer` 与 `EdgeArrowLayer`（之上）。
- 模板预览：`TemplatePreviewExtractor`（服务端：`StructureStart` 的 `PoolElementStructurePiece` → `SinglePoolElement` 模板 id → `StructureTemplate.save` 的第一套调色板 → 旋转变换 → `StructureShapeCodec.encode`）、`PreviewBudget`（最近 8 个 `?`）、`PreviewSelection`（客户端：有形状且网格就绪画网格，否则线框）。`SpyglassShapeRenderer` 的预测分支按它决定是否加入 `MARKED`。
- `ShapePassSelector#isOverlay(...)`：最近水平距离 < 已加载半径 + 16 且最远角距离 < 0.95 × `getDepthFar()` 的网格走 OVERLAY，其余走 FAR。
- `SpyglassShapeCache#shapeFor(StructureOutline)`：返回该轮廓对应形状的 `Optional<StructureShape>`（`SpyglassShapeRenderer` 用它取网格）。`StructureShape` 不可变、线程安全，提供 `originX/Y/Z`、`sizeX/Y/Z`、`biome()`、`blockCount()`、`getBlockState(x, y, z)`（未保留位置返回空气）与 `forEachBlock(consumer)`（按索引序遍历保留方块）。
- 形状分包由 `ShapePartAssembler` 在客户端主线程重组，`SpyglassShapeCache` 解码并做按方块数的 LRU；清除点与 `SpyglassClientState#clear` 相同。
- 真实方块渲染：`SpyglassShapeRenderer`（AFTER_SKY 与 AFTER_PARTICLES 两个 pass）→ `ShapeMeshStore`（构建请求、上传预算、清空、本帧网格状态 `meshUse`）→ `ShapeMeshBuilder`（工作线程网格化，产出 `BuiltShapeMesh`）→ `UploadedShapeMesh`（每层 `VertexBuffer`）。纯逻辑拆在 `ShapeMeshCache`（顶点预算 LRU）、`ShapeBuildTracker`（在途与拒绝记录）、`ShapeDepthRange`（near/far）、`SpyglassProjection#withDepthRange`（深度范围替换）、`ShapePassSelector`（OVERLAY/FAR 分组）、`ShapeFogStrength`（雾浓度模型，纯逻辑）与 `StructureShapeBlockGetter`（假世界视图），均有单测；`ShapePassFog` 为两个 pass 共用：换入最后一次 `FOG_TERRAIN` 的雾参数与本帧雾色，逐网格上传 alpha，并恢复所有改过的雾值。`SpyglassClientConfig` 提供实时可调的两个雾浓度。
- 服务端形状与可见性：`StructureShapeExtractor`（读区块、过滤、编码）→ `BuriedBlockFilter`（纯可见性搜索，单测覆盖埋地/屋檐/门/水下/竖井/洞穴六种几何）+ `ChunkHeightmaps`（`WORLD_SURFACE` 解码，含回退与损坏处理）+ `PieceBoxIndex`（部件盒按 16³ section 分桶，供搜索做 O(1) 级成员判断），均有单测。
- 未生成结构的重放：`ReadOnlyRegionScanner#scanInto`（收集"没有已存盘 start"的候选到 `absentCandidates`，`RegionScanResult` 携带）→ `PredictionContext`（服务端主线程解析生成器、结构状态、模板管理器与高度访问器；在一次性 `ProtoChunk` 上调用维度自己的 `createStructures`）→ `StructurePredictor`（按距离排序、每次扫描固定预算、按区块缓存、只重放可见距离内的区块）。`StructureOutline#predicted()` 带标志，协议版本 6 把它传到客户端；客户端 `SpyglassShapeRenderer` 用 `RenderType.lines` 只画线框并跳过网格请求，`StructureMarkLayer` 画 `?`。
