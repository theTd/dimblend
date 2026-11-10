# 2026-11-10 岩浆补充实现

## 独占新增文件（实施前登记）

- `src/main/java/dimblend/worldgen/CorridorLavaSources.java`：生成期源扫描、真实通行检查、无掉落应用。
- `src/main/java/dimblend/worldgen/CorridorLavaPlan.java`：无世界依赖的范围与操作合并规则。
- `src/main/java/dimblend/worldgen/CorridorLavaOriginals.java`：区块生成期原始状态的持久 attachment，跨处理次序恢复只读视图。
- `src/main/java/dimblend/mixin/FlowingFluidSpreadInvoker.java`：调用真实 `canSpreadTo`。
- `src/main/java/dimblend/mixin/CorridorLavaChunkPyramidMixin.java`（追加授权）：仅 generation pyramid 的 INITIALIZE_LIGHT 增加 FEATURES 半径 2 依赖。
- `src/main/java/dimblend/mixin/CorridorLavaChunkStatusTasksMixin.java`（追加授权）：initializeLight 前仅 rotating 执行最终中心区块处理。
- `src/test/java/dimblend/worldgen/CorridorLavaSourcesTest.java`：范围、同柱原始判定（不保留低处源）、清空/玻璃冲突、跨区块计划。
- `src/main/java/dimblend/gametest/CorridorLavaSourceGameTests.java`：真实 BlockState/流体通行路径。

已有独占文件：`NetherLavaFlow.java`、`NetherLavaFlowTest.java`、`RotatingChunkGenerator.java`。

状态：原交付冻结后完成 P2 定向修正：移除早期 FEATURES 入口，唯一处理阶段为现有 radius2 barrier 后的 INITIALIZE_LIGHT HEAD。本次仅改批准的五个文件；不改共享注册、阶段 mixin 或其依赖半径。尚未编译或运行，按授权不运行 Gradle/tests/游戏，不 stage/commit。

## 规则与操作优先级

- `NetherLavaFlow.isNetherSpeedLane` 加入 `underground`，保留 `nether`、`voidscape`；仍由既有 rotating/位置上下文门控流速，不改全维度 ultraWarm。
- 只在 rotating 新区块的生成路径调用；源范围 `y >= 67`、`-64 <= z <= 64`。水平玻璃可以延伸到 `z = ±65`，不能按源范围裁掉输出。
- `FlowingFluidSpreadInvoker` 调用引擎 `canSpreadTo`，任一水平或 DOWN 通行就处理，不检查 UP。不以空气、可替换性或碰撞形状单独近似通行。
- 先完整收集源与操作，再写方块；每柱清空上界取所有命中源 `y - 1` 的最大值，下界为 66（含）。清空优先于所有玻璃计划，跨多次处理也一样。
- “source 自身保留”只表示本次操作不改该源，不豁免另一上方源的清空。低处源先按原始状态作判定、产生自己的玻璃计划，随后仍被上方清空柱删除。没有新增低处源/红玻璃例外。
- 直接 `ProtoChunk.setBlockState`，先移除旧 block entity（含 pending NBT），不调用 destroyBlock、掉落函数、邻居通知或 tick 加载。
- 隔墙/传送门列始终拒绝岩浆写入。FEATURES 已完成的墙门状态参与最终通行判断，不恢复墙门覆盖的历史源，也不清掉已经写完的门。

## P2 定向修正：自然 FEATURES 覆写使历史计划失效

独立复核的实际原版路径：源 S 在 Nether chunk 右边缘 `(x=15, y=90, z=32)`，水平封闭、仅下方为空。右邻的 `BasaltPillarFeature.placeHangOff` 可由 `S.east().below()` 向西写入 basalt 封住 S 的最后出口。原版本机 `net/minecraft/world/level/levelgen/feature/BasaltPillarFeature.java:89–91`、NeoForge patched source 同文件 `:96–98`：随机条件通过后直接 setBlock，不检查目标旧状态；`net/minecraft/data/worldgen/biome/NetherBiomes.java:88` 将 BASALT_PILLAR 注册到 SoulSandValley。

旧实现提前清空并单调保存 AIR；即使最终已判封闭，重放旧 outputs 仍清掉新 basalt。自然 FEATURES 直接覆盖源时，同样会留下本来不该存在的历史清空/玻璃。用户“原始判定”只要求消除本处理自身的干扰，不允许恢复被自然 FEATURES 覆写的源计划。

本次修正：

- `RotatingChunkGenerator.applyBiomeDecoration` 删除岩浆调用；`CorridorLavaSources` 删除 `place`、双阶段 process 与 centerOnly 分支。FEATURES 期间不扫描、不建快照、不排队、不写岩浆处理方块。
- 唯一生产入口仍为现有 `CorridorLavaChunkStatusTasksMixin` 调用 `finish`。取得 FEATURES 已完成的生成缓存后，执行中心区块加一格 source halo 的完整读取/计划，再写中心方块。
- `finishStableChunks` 是生产入口实际调用的最终事务函数，同时让 GameTest 用真实 ProtoChunk 覆盖同一路径；其契约是所有天然 FEATURES 写入者已完成、相邻事务由调用者串行。
- 快照改为稳定阶段首次值不可覆盖，只还原本处理的最终事务；outputs 只由这个稳定阶段发布，保留 AIR 优先和邻区完成顺序的请求重放。没有添加撤销/恢复天然地形的事务。
- 已完成中心幂等退出；不再允许低层 apply 修改带 finished 标记的 proto。

本次精确五文件：`worldgen/RotatingChunkGenerator.java`、`worldgen/CorridorLavaSources.java`、`worldgen/CorridorLavaOriginals.java`、`gametest/CorridorLavaSourceGameTests.java`、本说明。

## 原始判断与跨区块操作记录

`CorridorLavaOriginals` 注册三个序列化 attachment：

- `dimblend:corridor_lava_originals`：天然 FEATURES 完成后的源、邻格判定上下文和本处理改动前的 BlockState。首次值不覆盖；高源清掉低源后仍可找回低源参与判断。不会在天然 FEATURES 完成前记录历史源/空气。
- `dimblend:corridor_lava_outputs`：只接受稳定最终事务的空气/红玻璃操作，AIR 对玻璃优先。目标最终事务可重放这些已经稳定的请求，无需再次扫描已到 FULL 的来源区块；不再承接早期雕刻与自然覆写之间的历史请求。
- `dimblend:corridor_lava_finished`：最终处理结束前发布完成标记；后续邻居不再修改它的快照或追加请求，避免 FULL 转换与邻居修改 attachment 的竞态。已完成邻居只提供只读上下文。

记录随 ProtoChunk 保存/载入；不用静态全局队列，也不加 onload 修复。现场源搜索复用 `ChunkAccess.findBlocks` 的 section palette 跳过逻辑，加上稀疏原始源索引，避免每次对 3×3×整高度逐格分配字符串；非目标 lane 在 palette 扫描前退出。写入事务按 generator 实例串行；delegate 装饰不占此锁。稀疏快照与输出请求会增加受处理 chunk 的内存/NBT 体积，目前不做 FULL 后清理（邻区尚可能需要生成期记录），需实际地图测量成本。

所有扫描与应用先排除 `LevelChunk`、`ImposterProtoChunk`、FULL 和升级旧区块。旧 FULL 仅允许按需读取相邻碰撞状态，既不扫描源也不写它。邻区读取只走已给定 `WorldGenRegion` 的生成缓存；不存在服务端 `getChunk(..., FULL, false)` 调用，没有同步建 future/加载区块。

唯一处理阶段是 INITIALIZE_LIGHT 的 HEAD：实际 light source 初始化前完成中心区块及 1 格 source halo 的判定。只改中心区块方块，向仍在生成且未 finished 的邻区追加稳定输出请求，来源先到 FULL 时目标也能重放请求。没有早期 FEATURES 处理、SPAWN、tick 或 onload 补处理。source 判定层与输出请求分开，避免清空/玻璃本身改变后续源判定。

## 已核实的引擎证据

原版本机源码根 `E:/misc/mcsrc-1.21.1/`：

- `net/minecraft/world/level/material/FlowingFluid.java:414`：`canSpreadTo(BlockGetter, BlockPos, BlockState, Direction, BlockPos, BlockState, FluidState, Fluid)`，同时检查目标流体替换、face occlusion 和 liquid containment；`:394` 包含门、梯子、portal、容器等额外限制。
- `net/minecraft/world/level/material/LavaFluid.java:140,155,165` 的 `getSlopeFindDistance`、`getDropOff`、`getTickDelay` 读取 ultraWarm；沿用原有三处 mixin，只加 lane。
- `net/minecraft/server/level/WorldGenRegion.java:113`、`:147`：getChunk/hasChunk 受生成阶段直接依赖缓存限制；与 ServerChunkCache 的阻塞加载接口不同。
- `net/minecraft/world/level/chunk/ChunkAccess.java:321`：findBlocks 先通过 section.maybeHas 过滤，再访问该 section 的方块。
- `net/minecraft/world/level/chunk/ProtoChunk.java:114`：直接改状态并维护 heightmap；`:129` 在 INITIALIZE_LIGHT 后也更新光照；`:280` removeBlockEntity 同时清活动和 pending 记录。
- `net/minecraft/world/level/chunk/status/ChunkPyramid.java:36`：FEATURES 只要求半径 1 的 CARVERS，写半径 1；`:43` LIGHT 只要求半径 1 的 INITIALIZE_LIGHT；SPAWN 在 LIGHT 后。

NeoForge 本机 patched source：`E:/misc/dimblend/build/moddev/artifacts/neoforge-21.1.249-sources.jar`（已读 zip 条目，没有解压或改主仓库）：

- `net/minecraft/world/level/material/FlowingFluid.java:427`：patched `canSpreadTo` 同签名与上述三项检查，没有按空气简化的替代 API。
- `net/minecraft/world/level/chunk/ChunkAccess.java:494` 起：attachment API；setData/原地变更必须标记 unsaved。
- `net/minecraft/world/level/chunk/storage/ChunkSerializer.java:214`、`:400`：attachment 的读取/保存；`ImposterProtoChunk.java:313` 会转发 attachment holder，所以必须明确排除这种 wrapper，不能仅判断 `instanceof ProtoChunk`。
- `net/neoforged/neoforge/attachment/AttachmentType.java:35`：type 必须注册；`:184` 支持 Codec 序列化。

## 阶段调整：晚到边缘源与完成性

仅在 FEATURES 或原版 SPAWN 补处理不足：C0 和 C1 直接相邻，C2 在 C1 的另一侧；C2 的 FEATURES 能向 C1 靠 C0 的边缘写入新源，而 C0 原先只等待 C1 的 FEATURES，可能已经 FULL。写回 C0 会违反“不改旧 FULL”，不写则漏玻璃。snapshot/队列本身不能改变阶段依赖。

主编排追加授权后，已实施：

1. `CorridorLavaChunkPyramidMixin` 在 `ChunkPyramid.<clinit>` 的 Builder.step **ordinal=8**（EMPTY 从 0 起，第 9 次恰为 generation INITIALIZE_LIGHT）修改 UnaryOperator 参数；保留原 operator，再加 `FEATURES, radius=2`，block write radius=0。`require=1, allow=1`。第二个 loading pyramid 的相同阶段不拦截。
2. `CorridorLavaChunkStatusTasksMixin` 注入 `initializeLight` HEAD（同样 require/allow=1）；只在 rotating、RotatingChunkGenerator、新 ProtoChunk、persisted status 早于 INITIALIZE_LIGHT 时构造该阶段 WorldGenRegion 并调用最终处理。已有 INITIALIZE_LIGHT+ 的 loading step 不进入处理。未完成的 FEATURES proto 恢复时，此次推进 INITIALIZE_LIGHT 属于 generation step，仍受新依赖保护。若只注册 task mixin 漏注册 pyramid mixin，检测到缺半径 2 FEATURES 依赖会明确报错，避免静默边界漏处理。
3. 新区块中心和 1 格源 halo 的方块，其潜在 FEATURES 写入者都在中心 chunk 的半径 2 内；这些 FEATURES 全部完成后，才计算最终中心输出。邻居最终阶段若先完成，会先留下跨边输出请求；中心最终阶段若先完成，则此时邻区源已生成，中心能直接看到它。已经到 FULL 的来源不再扫描。
4. 最终阶段的跨区块部分只保存请求；不写邻居方块，更不修改 FULL 或 Imposter wrapper。墙列整个排除，以已完成 FEATURES 的墙门状态为最终结果，不保留已被墙门覆盖的历史源。

### 无环与不阻塞证据

- `ChunkStatus.java:24` 至 `:29`：FEATURES < INITIALIZE_LIGHT < LIGHT < SPAWN < FULL；新增边只从 INITIALIZE_LIGHT 指向严格更早的 FEATURES。
- `ChunkStep.java:71` 起 `Builder.addRequirement` 拒绝 same/later status，`:111` 起重算 accumulatedDependencies。没有 INITIALIZE_LIGHT→邻居 INITIALIZE_LIGHT 的同阶段边，没有 FEATURES→INITIALIZE_LIGHT 的反向边。
- `ChunkGenerationTask.java:126`、`:131`：原版按 pyramid 调度各层，`:139` / `:156` 用 getNow 查询，未完成 future 交还调度器。新增代码没有 join/get/managedBlock，没有拿 ServerLevel.getChunk 请求未完成区块。
- `ChunkMap.java:651` 起通过 worldgenMailbox 执行 runUntilWait；本处理在该生成任务中运行，generator monitor 只串行 helper 的读/计划/写事务，不持锁等待任何 future，也不在主线程加同步 chunk 请求。
- `GenerationChunkHolder.java:247` 和 `WorldGenRegion.java:113`：读取已经完成的生成缓存；新增 helper 不主动调度、等待或加载。
- `ChunkStatusTasks.java:159` 至 `:166`（patched source 同段）：HEAD 处理发生在 initializeLightSources 和 light engine.initializeLight 之前，避免先照明后大范围挖柱。

### 全局依赖范围与成本（不可当作只影响 rotating）

本机 `ChunkGenerationTask.java:33,126,134` 和 `ChunkLevel.java:13` 共用静态 generation pyramid 与其 accumulatedDependencies；两处许可 mixin 无法按维度替换整套调度/票据等级。因此 **所有维度的新生成任务** 都会取得更宽的 FEATURES 先决范围；具体岩浆扫描/写入仍严格只在 rotating，已有 FULL 加载不执行岩浆处理，loading pyramid 本身不改。

按本机 `ChunkStep.Builder` 原有递推逐项计算（只读 Python，小规模计算，无构建）：

| FULL 累积依赖 | 原版半径 | 本实现半径 |
| --- | ---: | ---: |
| INITIALIZE_LIGHT | 1 | 1 |
| FEATURES | 1（3×3） | 3（7×7） |
| CARVERS | 2 | 4 |
| BIOMES | 3 | 5 |
| STRUCTURE_STARTS / EMPTY 总缓存 | 11（23×23） | 13（27×27） |

单个孤立 FULL 请求的 FEATURES 最坏需求由 9 个变成 49 个（增加 40），总缓存边界增加 200 个 holder；相邻玩家请求通常共享这些依赖，不能把此数字当每个区块都新增 40 次生成。静态改动会扩大新区域边缘的预生成、缓存和票据传播范围，并改变各维度 FEATURES 执行时序；这是明确的性能/整合兼容风险，未实测耗时，不声称零成本。没有改 FEATURE 本身内容或一般维度的方块规则。只在必要的 INITIALIZE_LIGHT 加一个低阶段依赖，未另扩 FEATURES 自身的写入半径。

如后续要求完全消除其他维度的调度成本，需要追加 per-level pyramid/ChunkLevel/ticket 一致性的独立改造；不能仅在 task 中临时等待邻居 future，那会绕过缓存和票据契约。

## 集成者共享文件接线清单

1. `dimblend.mixins.json` 的 common mixins 增加 `"FlowingFluidSpreadInvoker"`、`"CorridorLavaChunkPyramidMixin"`、`"CorridorLavaChunkStatusTasksMixin"`；无需可选 mod 门控。
2. 核心 `DimBlend(IEventBus modBus, ...)` 中增加 `CorridorLavaOriginals.ATTACHMENTS.register(modBus);`（三个 attachment 均由这个 register 注册，不能只注册 invoker 就运行生成）。
3. 核心 `RegisterGameTestsEvent` 注册 `dimblend.gametest.CorridorLavaSourceGameTests.class`。不加自动 GameTestHolder，避免重复注册。
4. 为纯 core GameTest 提供 `data/dimblend/structure/corridor_lava_empty.nbt`（3×3×3 空模板）。其 SNBT：`{size:[3,3,3],palette:[{Name:"minecraft:air"}],blocks:[],entities:[]}`。该共享资源不在当前独占清单中，故没有擅自写入；不能借用 experience 的模板制造隐藏依赖。

## 测试与待验收

- 新增 8 个纯 JUnit 用例：源边界、±65 玻璃输出、精确清空列与 y66、最低源、同柱原始计划、清空/玻璃顺序、负坐标跨区块输出、封闭源空计划。
- 现有 NetherLavaFlowTest 改为三条目标 lane，增加空串/null 非目标输入。
- 当前 12 个 GameTest；本轮移除“早期玻璃被后续雕刻再重放”和“天然 FEATURES 后刷新旧空气快照”的双阶段测试口径，改用同一个 `finishStableChunks` 生产事务覆盖：basalt 封闭最后出口后保留玄武岩且零输出；天然 FEATURES 覆盖源后不清柱、不留玻璃且零输出；晚到边缘源在稳定阶段被发现、邻区只收请求到自己最终阶段才应用；相邻区块两种完成顺序（第一块先转 FULL）结果一致、稳定跨边玻璃不漏、上下源先判定再清空、清空压过重叠玻璃。其余五方向/真实流体阻挡/空气-only/范围与流动源/BE 清理/序列化/pyramid/FULL 保护测试保留（部分断言合并在同一用例）。
- 按用户约束未运行 Gradle、编译、GameTest、游戏，也未宣称红绿通过。仅做只读源码核对和 diff/UTF-8/文件范围检查。
- 核心定向任务供主编排安排：`:dimblend:test --tests dimblend.fluid.NetherLavaFlowTest --tests dimblend.worldgen.CorridorLavaSourcesTest`；随后核心 GameTest 的 `corridor_lava` batch、TrainTripWorld 实际新地形与跨 chunk 顺序验收。运行前依本仓库约定检查负载。
- 本次机制细节已写在此文档；技能文件不在独占范围内，没有修改。没有新增可独立回写的环境事实。

### 本轮静态交付的剩余边界

- 新增/替换的回归代码尚未编译或执行；因此没有红绿测试结果，不能把代码用例数视为通过数。真实 BasaltPillarFeature 路径按上述本机源码取证；GameTest 精确模拟其覆写结果并调用实际最终事务，不启动随机世界生成。
- 两种邻区完成顺序通过独立 ProtoChunk fixture 建模；第一块设置 FULL 后不再作为源扫描/写入。真实线程调度、NBT 往返和整合包验收仍由主编排后续安排。
- 已有静态 pyramid 的全维度调度成本，以及受处理区块的快照/NBT 成本仍待测；本轮没有扩大或缩小依赖半径。
- 处理依赖现有 FEATURES 阶段与写半径契约；整合包是否存在这些阶段之外的地形写入尚未经实机验证。本轮也不迁移/撤销旧试跑版已经落盘的历史 outputs，不回溯修改 FULL 地形。
