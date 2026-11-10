# 2026-11-10 补充需求与实施记录

依据：用户原始清单及 `dimblend-supplement-20261110-decisions.json`（用户下载结果，2026-11-10）。推荐项未自动套用；下列均为用户实际选择或自填。

## 已确认口径

1. 背包/快捷栏图腾保护实际死亡前的原版经验与 S.O.L 食物记录、额外生命；不改变原版手持图腾的复活。每次实际死亡最多消耗一枚，并转成 `kaleidoscope_grilling:totem_powder`。忽略 S.O.L 的死亡清空配置，仅本次不清空；未使用保护的死亡不受影响。
2. 图腾说明：`放进背包里可保护经验和额外生命值`。
3. 仅旋转维度中的普通指南针指原点 (0,0)，磁石绑定保留。导航台目标一致。
4. 扳手只可在旋转维度的地表区域启动断钩；已充能可跨区域持续，沿用附近 2 格内无其他车钩后自动取消。扳手 shift 概要补 `对车钩使用时：可在安全区域临时解除车钩一次`（原文“车购”订正）。
5. 牵引效率 = `abs(沿轨速度 m/s) / (abs(输入 rpm) / 4 - 1) × 100%`；转速 ≤4 显示 `--`，其余保留两位小数，不截断超过 100% 的数值。菜单三项为数据源名称“牵引效率”、玩家自填标签、固定百分比格式；名称本身不拼入结果。物理转向架加入显示连接器思索来源。
6. 普通与珠光浮空水晶都移除额外阻力、保留浮力及运动视觉，免疫载具岩浆熔毁。桶说明：`用于列车时 可有效减重 且不产生阻力`。
7. 全部森罗物语“原版玩偶”均能安装在 Create 彩色坐垫上；恰好一个同高度水平相邻仓储发报机。生存安装消耗一只、保存物品数据；管理员坐垫不能坐人，只能破坏取回。面向发报机，右键复用仓库管理员菜单；左下角为该玩偶物品图标两倍尺寸（32×32）。破坏坐垫掉一只玩偶，破坏配对发报机连同坐垫一起破坏，不重复掉落。玩偶说明：`它也会是称职的仓库管理员`。
8. 地下/下界/虚空区域岩浆使用下界流速。地形处理只在新生成时执行，源范围 `y≥67`、`-64≤z≤64`，按真实流体通行判断能否流出；水平空气换红玻璃，源本身不变。

## 最终确认

2026-11-10 用户明确回复“确认，按此实施”：岩浆源正下方至 y=66（含）全部清空、不掉落；区域隔墙及传送门最后生成保留。旧地形与玩家后放方块不处理。

## 实施文件与串行归属

用户要求主代理负责编排，实际实现交给子代理。通过 agent-hub 分配四个独占单元；共享入口、语言文件、mixin 列表由后续集成者串行修改。不 commit、不清理其他工作树。

| 单元 | 当前 Hub 会话 | 文件归属 |
|---|---|---|
| 死亡保护、文案、车钩范围复核 | `codex-045d8f0e` | 第一组生产代码、`gametest/BackpackTotemGameTests.java`、独立 `supplement-death-coupler-implementation.md`；共享入口除外 |
| 指南针、牵引效率、浮空水晶 | `codex-89bee536` | 第二组生产代码、`gametest/SupplementCompassGameTests.java`、独立 `supplement-navigation-physics-implementation.md` |
| 玩偶管理员 | `codex-a8125083` | `compat/dolls/**`、`client/dolls/**`、`Doll*Mixin.java`、`gametest/DollStockKeeperGameTests.java`、keeper seat 方块状态/掉落资源、独立 `supplement-doll-implementation.md` |
| 岩浆生成 | `codex-250e90ea` | core `fluid/NetherLavaFlow.java`、`worldgen/{CorridorLavaSources,RotatingChunkGenerator}.java`、必要流体 invoker、对应单测/GameTest、core `docs/supplement-lava-implementation.md` |

原四个 DSH 会话因 Windows 沙箱初始化失败未产生生产改动。按 Hub 基础设施回退规则切换至 Codex；已用 `apply_patch` 在本工作树创建、核验并删除临时文件验证写入能力。未修改 ACL、未设置会话级全盘访问规则。

第一组（死亡保护、文案、车钩范围）预定文件：

- `exploration/BackpackTotemProtection.java`（新）：死亡一次保护状态、消耗/粉末、经验与记录继承。
- `exploration/DeathRules.java`：与已有死亡经验缩放衔接。
- `mixin/compat/solcarrot/FoodRecordDeathMixin.java`（新）：只覆盖受保护死亡的清空判断。
- `client/SupplementTooltips.java`（新）、`client/DimBlendClient.java`：新增说明。
- `compat/simurail/CouplerCharge.java`：按车钩投影到世界的位置限制启动区域。
- 核心模块 `api/RegionLanes.java`（新）：对外提供区域名称，避免跨模块导入 worldgen 实现。
- `mixin/DimBlendMixinPlugin.java`、`resources/dimblend_experience.mixins.json`、`resources/assets/dimblend_experience/lang/{zh_cn,en_us}.json`：门控与注册。
- 定向死亡保护 GameTest 与区域判定单测；详细路径在新增前列入此文档。

第二组文件：`compat/compass/OriginCompass.java`、`mixin/OriginCompassMixin.java`、`mixin/compat/simulated/NavigationCompassMixin.java`；`compat/simurail/{TractionEfficiency,TractionEfficiencyDisplaySource,TractionDisplaySources}.java`、`client/ponder/SupplementPonderPlugin.java`、`src/test/java/dimblend/experience/compat/simurail/TractionEfficiencyTest.java`；`data/aeronautics/floating_materials/levitite.json`、`data/dimblend_experience/tags/block/lava_melt_immune.json`。共享注册入口、mixin 配置、语言及依赖排序同步更新。

## 验证状态

- 四个实现单元和共享注册均已完成 native 交付并冻结。首组旧版本曾通过 `:dimblend-experience:compileJava`，不能作为当前代码编译或行为验收结论；当前版本尚未编译、运行测试或启动游戏。
- 玩偶、指南针、牵引效率、浮空水晶的独立只读审查已完成，未发现有充分源码证据支持的可行动缺陷；不代替 mixin、GUI、物理运动的实际路径验收。
- 共享接入的独立只读复核未发现已证实的可行动缺陷：注册/门控、资源排序、安装依赖闭包和开发 sourceSet 接线已有本地源/字节码证据；实际 Gradle 任务图和启动织入仍未运行。
- 交叉复核确认的创造模式满包 `Inventory.add` 吞粉末问题已修正并补两项 GameTest 回归代码；定向独立静态复核确认不再进入吞物分支，测试构造能触发旧缺陷。测试未执行，实际死亡掉落仍待运行验收。
- 岩浆与死亡/车钩交叉审查已完成，两项确定 P2 均由原负责人修正并通过定向独立静态复核，全部实现与接入已冻结，当前未见仍待修正的确定缺陷。
- 岩浆移除早期 FEATURES 输出，所有扫描/计划/应用统一在稳定最终阶段，消除自然封口或覆源后重放历史请求的问题。当前 12 项 GameTest 未运行；封口/覆源用例覆盖最终事务，没有实际执行 BasaltPillarFeature 或完整生成链，不能作为运行回归通过证据。
- 岩浆阶段依赖仍影响所有维度的新生成调度：FULL 的 FEATURES 累积半径 1→3，总缓存半径 11→13。静态审查未发现依赖环或确定线程竞态；附件的内存/存档体积、真实生成耗时及整合兼容性仍需实测，不能宣称性能可接受。
- TrainTripWorld Java 客户端未运行，现有三个世界均为用户存档；实机验收使用独立新世界。CPU 五秒复核持续约 93%–94%，另一 `Minecraft.Windows` 进程正占用较多资源。用户明确选择“先完成静态工作，构建和实机都等我通知”；本批不启动构建、测试或游戏，待用户通知后重新核查资源并续跑。
- 实机默认 TrainTripWorld；构建前检查负载，JDK 21，优先定向模块任务。修改 mixin 或客户端显示必须走实际功能路径。

待运行的精确开发测试指令与依赖闭包见 `supplement-integration.md`。实机准备工件已保存在工作树忽略目录 `build/diag/supplement-20261110-{runtime-plan.md,launch.ps1,probe.py}`；Python/PowerShell 语法检查通过，未执行启动或探针。探针仅为客户端快照，不能证明服务端私有状态；权威服务端取证方式仍需后续运行验证。

验收覆盖：实际死亡/被取消死亡/手持图腾复活/连续死亡/keepInventory 两值及 S.O.L reset 两值；地表启动车钩与跨区取消；普通指南针及磁石指南针在手持和导航台的方向；牵引效率停转、反转、超 100% 和标签/格式配置；两类浮空水晶运动与熔毁免疫；全部色坐垫安装和保存、菜单图标与权限、破坏坐垫/发报机掉落守恒；岩浆范围边界、封闭源、非整块流体通行、跨区块、同柱多源、隔墙/传送门保留，以及旧区块不处理。

## 已核实接入点

- S.O.L：运行 jar `solcarrot-1.21.1-1.16.6.jar`，`CapabilityHandler.onClone`：死亡且 `shouldResetOnDeath()` 为真直接返回，否则复制旧玩家 FoodList；`onPlayerRespawn` 同步并更新额外血量（JDK 21 javap）。
- 原有 `DeathRules.onClone` 按配置缩放经验，仅旋转维度生效；图腾保护必须优先于该缩放且阻止额外掉经验球。
- Create 源码 `StockTickerInteractionHandler.interactWithLogisticsManagerAt` 可复用权限、订货单及原生菜单；上游默认匹配包含高一格，而用户明确本批只认同高度。
- 轨道 `OakTrackCorridor.TRACK_Y=64`，路基 y=63；拱洞半宽 9、顶高 y=81，普通结构禁区半宽 271。新岩浆范围扩至 z=±64。
