# DimBlend Experience 需求规格（已核对版 v1.2）

> 来源：`docs/requirements.md` 原始清单 + 四轮人工核对结论（2026-09-19）。
> v1.1 增补：需求表更新后的新增/细化项（A9 虚空共存、C 硬度统一+C3 伪装板、F 组装器禁摆、E5 车钩红石、D4 阈值 64、E2 新映射）。
> v1.2 改动：A1 经验球同步取消（不能找回）、A5 bossbar 方向门控、D2 红石强度实时随动、
> D3 耗电下限改回按 4 计、行进循环音量×3、刹车音阶 0.5（松闸保留）；HUD 换位条经确认忽略；
> A4/C1 用户标"未实现"但开发侧已实现，记"已实现待实测"。
> v1.3 增补（2026-09-21 合并核对）：z16 隐藏数字（`|z|≤16` 只画条不画数字，`ZProgressHud.NUMBER_HIDE_RADIUS=16` 代码已实现，文档滞后补入；清单同步订正为待实测）；本次粘贴增量逐条核对无新需求（明细见清单头注）。
> v1.4（2026-09-22 拍板实施）：A4 严格回血（层级只增不减、仅 `|z|≤128` 清零）、
> z256 回程锁存（旧 §3.2 分段作废，归 0% 锁存、回 128/向前跨段解除，见 `docs/z256-bar-requirement.md`）、
> E2 低端音阶 0.5、E3 刹车音阶 1.0、D4 启动音后延迟 3 秒起循环。
> v1.5（2026-09-22 口径落字）：G 板块四个新系统（G1 村民大师 / G2 结构床 / G3 天然水 / G4 传送门禁令）
> + E6 宽轨粒子；一律仅 rotating 维度；回家通道不管、不留后门。
> v1.6（2026-09-23 迁移）：C 板块（创造模式伪装方块，C0–C3，含 `BlockPropertiesAccessor`
> 与 copycats/create 侧 C0 mixin）整体迁出至姊妹工程 `E:\misc\dimblend-blocks`
>（modid `dimblend_blocks`）；本工程 mods.toml 的 copycats/create 可选依赖、
> libs/copycats 编译 jar、lang/blockstates/model 资源随之移除。§3 及相关条目
> 保留为迁移前实现记录，运行时行为以 dimblend-blocks 交付为准。
> v1.7（2026-09-23 深夜批次）：D4 根因修复（客户端 active 永不同步 → 运转判据改读
> 同步的 "Speed"）、新条目 D5 交流发电机 <16rpm FE 流失、D6 马达护目镜
> 应力量/已使用能量改实际转速响应值；新开关 `alternatorIdleDrain`。
> 实测反馈修订：A5 z256 回程锁存口径作废，改为回程瞬时归零（转头前进即恢复，无记忆），
> 详见 `docs/z256-bar-requirement.md` §3.2/§4。
> v1.8（E7 离结构传送 + E3 刹车速度门）：任意车架 &gt;4 m/s 时每 10 秒以玩家为中心
> 探测半径 32 格 sable 结构，都无则传送回重生位置；刹车音效仅速度 &gt;4 m/s 时播放。
> v1.9（E8 车架随机横向力）：车架 &gt;4 m/s 时每固定 2 秒以 速度/20 概率施加 1200 pN
> 横向力（左右随机、持续 10 tick）；新开关 `trainLateralForce`。
> 间隔原为随机 2-8 秒，后改为固定 2 秒。
> v1.10（G5 末影存储仅限结构放置，2026-09-27）：rotating 内 `enderstorage:ender_chest` /
> `enderstorage:ender_tank` 只能放在 sable 结构上；新开关 `enderStorageStructureOnly`。
> v1.11（dimblend-blocks 批次，2026-09-29）：板块 C 增补 C4（洁净伪装板/半砖）/C5（伪装板/半砖隐藏）；
> 新增板块 K（§12，Create 创造模式传动件：creative_shaft/creative_cogwheel，蒸汽引擎绝缘）。
> v1.12（2026-09-30 用户口径，A5 计数器方案）：z256 进度条改读远行诅咒层级，
> 进度 `(|z| − 256×层级)/256`、负数 0%、`|z|≤128` 清零；方向判定（回程瞬时归零/锁存）作废；
> 层级经 `FarCurseTierPayload` S2C 同步；层级计数常开，`depthCurse` 只管扣上限与粒子。
> v1.13（2026-10-05 用户拍板，A1/A2 重写）：旧「死亡保留物品」实机会丢失饰品栏物品
> （快照只覆盖原版栏位，饰品随掉落取消蒸发）——死亡保留物品、床遗失改道走廊重生（A2）
> 整体取消，死亡掉落/重生落点回归 gamerule 与原版逻辑；A1 改为按比率清空经验
> （`deathExpClearRatio`，默认 1.00，无视 keepInventory，经验球不掉落），比率经 /dbx 面板可调。
> v1.14（2026-10-10 用户口径，E9 刹车信号视为 1 级）：转向架收到任意强度红石信号一律按
> 1 级信号处理（原行为为刹车力度随信号强度线性）；新开关 `trainFixedBrakeSignal`。
> v1.15（2026-10-11 用户拍板，A10 战利品探索地图过滤，全局功能）：任何战利品箱不再产出
> Dungeons Arise（含七海）探险地图与原版藏宝图；新开关 `lootMapFilter`。
> v1.16（2026-10-11 用户拍板，A11 藏宝箱贝壳 X 标记，全局功能）：埋藏的宝藏生成时在其
> 正上方沙滩表层用贝壳标记方块摆 X（A10 禁藏宝图后的替代发现机制）；新开关 `treasureShellX`。
> v1.17（2026-10-12 用户口径，E9 调整：刹车信号上限可调）：转向架红石信号由「任意强度一律按
> 1 级计」改为强度上限——1~上限原样生效、超过上限压到上限；开关 `trainFixedBrakeSignal` 改为
> `trainBrakeSignalCap`（1-15 级，默认 1 行为不变、15 等效关闭），经 /dbx 面板可调。
> v1.19（2026-10-14 用户口径，E3 刹车音速度门可调）：默认阈值由 4 降为 2 m/s，新增
> `trainBrakeSoundSpeedThreshold`（0-64 m/s，/dbx 面板步进 0.1）；松闸音不受此门控。
> v1.18（2026-10-14 用户口径，E7 重做：无人值守刹车锁存）：废弃离结构传送（`TrainOffStructureRules`、
> `CorridorRespawnLocator` 随之删除，`offStructureTeleport` 键作废），改为——rotating 内编组在动
> 且无人值守时全维度强制满刹锁存；锁存由任意车架红石信号通一次再断开解除；锁存期间转向架播
> portal 粒子（E6 参数）。新开关 `trainDeadmanBrake` + 半径 `trainDeadmanRadius`
> （默认 32，经 /dbx 面板可调）。
> 2026-10-15 实测修订（同日两次）：①值守判定改对整列编组量距离（按车厢及真实车钩连接成组，编组最近架
> 超半径才算无人值守，不看该架速度——此前「最近架必须超速才算跟车」在玩家身边有低速架时误判，
> 列车明明在旁却紧急刹车）；②钉死解除语义：一节车架的完整通断脉冲重置全列车（含他车），
> 无需逐节操作。
> 2026-10-15 后续用户确认距离刹车正确，解除口径更新为：任意车架的原始红石等级变化即
> 解除所在编组全列紧急刹车，不再要求完整脉冲。锁存及粒子同步收敛到编组/车厢，其他编组独立。
> 2026-10-26（分液池下界蒸发 + A10 全量换纸）：N6——下界群系内分液池按
> `itemDrainNetherEvaporationMbPerSecond`（默认 50 mB/s）蒸干存水，只认水；
> A10 由「移除部分 destination 地图条目」改为三层「一切地图最终换 1 张纸」
> （表加载替换 / exploration_map 全目的地短路 / NeoForge GLM 最终网）。
> 本文件是开发依据；原始清单仅作需求索引，两者冲突时以本文件为准。

## 0. 环境基线

- 开发目标：Minecraft 1.21.1 + NeoForge 21.1.251（ModDevGradle，Java 21）
- 运行实例：`E:\misc\TrainTripWorld`（NeoForge 21.1.249）
- 依赖对齐口径：**addon 模组（B/C/D/E 五个 jar）字节级对准**——从实例 mods 目录原样复制到 `./libs` 作 compileOnly 依赖；**Create 例外**——编译走 maven.createmod.net `com.simibubi.create:create-1.21.1:6.0.10-281`，与运行时 6.0.10 为同上游版本、版本级对齐而非字节级一致（启动器重打包，SHA 不同）
- 本模组为唯一交付物（单 jar）；v1.6 起原 C 板块（创造模式伪装方块）迁出至 `dimblend-blocks`，其余板块收入 `dimblend_experience`

| 板块 | 目标模组 | 编译对准 jar（TrainTripWorld/mods） | 源码参考位置 |
|---|---|---|---|
| A | 无（纯原版/NeoForge API） | — | `build/moddev/artifacts/neoforge-21.1.251-sources.jar`（补丁版源码） |
| B | Create: Diesel Generators 1.3.15 | `createdieselgenerators-1.21.1-1.3.15.jar`（同级源码构建，见 0 节 provenance） | `E:\misc\Create-Diesel-Generators` |
| C | Create 6.0.10 + Copycats+ 3.0.4 | Create：maven `6.0.10-281`（版本级对齐）；`copycats-3.0.4+mc.1.21.1-neoforge.jar` | `E:\misc\Create`、`E:\misc\copycats` |
| D | Create: Crafts & Additions 1.5.10 | `createaddition-1.5.10.jar` | `E:\misc\createaddition` |
| E | Create Simurail 0.0.0-a + Sable 2.0.5 | `simurail-1.21.1-0.0.0-a+ecd2dd3.jar`、`sable-neoforge-1.21.1-2.0.5_cui-modded.jar` | `E:\misc\Create-Simurail`、`E:\misc\sable`（已有） |

## 1. 板块 A：探索限制（核心，无需第三方依赖）

**生效范围（用户拍板）**：A1–A6 全部仅在 `dimblend:rotating` 维度生效；其他维度走原版逻辑。A7/A8/A10 为全局功能，不受此限。

### A1 死亡清空经验（v1.13 重写为比率制）
- 在 rotating 维度死亡：重生后经验 = 死亡前（等级+进度）× (1 − 比率)，经验总量同比缩放
- 比率 = `deathExpClearRatio`（0.00–1.00，精度 0.01，默认 1.00 全清、0.00 全保留），
  经 /dbx 面板「死亡重生清空经验比率」实时可调
- **无视 keepInventory 游戏规则**：true 时覆盖原版 `restoreFrom` 的经验复制，false 时按比率
  回补，两种 gamerule 下结果一致；比率是唯一的经验损失规则
- **经验球掉落同步取消**（不能找回；比率 0 时若掉球会白嫖一份，故无条件取消）
- 物品栏不再由本模组处理：死亡掉落/墓碑交还 gamerule 与其他 mod
  （重写根因：旧快照只覆盖原版栏位，饰品栏物品随掉落取消一并蒸发）
- 维度判据取死亡瞬间原实体所在维度，重生落点维度不影响命中
- A4（v1.2 备注）：用户标"未实现"，但开发侧已实现（tier 0 摘修饰符 + happy 粒子），
  记"已实现待实测"，以实机为准

### A2 重生点规则（v1.13 作废）
- v1.13 起整体取消：床遗失/被挡/失效时不再改道 `(死亡x, 66, 5)` 走廊旁，重生落点完全走原版逻辑
- 走廊旁落点算法 `CorridorRespawnLocator` 曾保留供 E7 离结构传送回送落点；v1.18 E7 重做后无调用方，已随 E7 一并删除

### A3 远行诅咒（z 轴生命上限递减）
- `|z|` 每跨过一个 256 整数倍边界（256/512/768/…），生命**上限 ×0.75 乘算叠加**（20→15→11.25→…）
- **下限 1 点（半颗心）**，跌破不再下降（用户拍板）
- 跨界瞬间对该玩家播放 `angry_villager` 粒子
- 一次跨多级（传送等）时补齐全部层级惩罚

### A4 诅咒恢复（v1.4 严格口径）
- 回到 `|z| ≤ 128` → 生命上限完全恢复（tier 清零摘修饰符），播放 `happy_villager` 粒子
- 其余位置层级**只增不减**：`128 < |z| < 256` 僵持区保持现状；回退跨过 256 整数倍
  边界同样保持现状，**不降档、不恢复任何已扣除的上限**；再次深入按新层级补足
- 死亡重生按新位置重算层级（附件不随实体克隆，2026-09-22 拍板维持现状，
  不视为违规清零；严格口径仅约束存活移动）
- （v1.4 备注：旧僵持区“回退降档恢复一部份”已作废，`DepthCurse` 取 `max(target, current)`，仅 0 允许清零）
- （v1.12）层级 = z256 进度条已满次数，A5 直接读它；层级计数在旋转维度内常开，
  `depthCurse` 关闭时只摘上限修饰符、不放粒子，层级照常增减

### A5 z256 常驻进度条（v1.3：bossbar 方案作废）
- bossbar 方案（顶部条、最后 32 格、方向门控）已删除（`CurseBossbar` 移除），
  改为客户端常驻 HUD 条，详见 `docs/z256-bar-requirement.md`（显示唯一依据）
- 位置：护甲 HUD 列（81px 宽，包裹 `ARMOR_LEVEL` 层绘制，读 `leftHeight` 无漂移）；数字为 `|z|` 取整
- 进度（v1.12 用户口径，计数器方案）：`(|z| − 256×层级) / 256`，为负显示 0%；层级即 A3/A4 远行诅咒层级
  （进度满 → 层级 +1 并扣上限；`|z|≤128` 清零重新开始），与走向无关；
  旧 `|z| % 256` 段内进度、回程瞬时归零、回程锁存口径全部作废
- 层级同步：服务端附件 `far_curse_tier` 经 `FarCurseTierPayload` 下发（变更/登录/重生/切维度），客户端断线复位；
  客户端 `|z|≤128` 直接按层级 0 计，越过边界而 +1 未同步到的几 tick 钳为满条
- 颜色按进度：[0,60%) 绿、[60%,80%) 黄、[80%,100%] 红
- 数字隐藏（v1.3 新条目，代码已实现）：`|z|≤16` 时只画进度条、不画数字（`ZProgressHud.NUMBER_HIDE_RADIUS=16`，`absZ>16` 才绘制）
- 开关：复用 `curseBossbar`（Config 注释已同步）；256/128 常数取自 `DepthCurse`
- HUD 换位旧条（band 条挪走）不再执行：dimblend 基底的 band 条保持原位，
  z256 条同样画在护甲列同一坐标——两者是否重叠取决于两 mod 的层包裹顺序，
  未经实机核实，**重叠与否留实机确认**，若重叠再定错位方案

### A6 安全区生成拦截
- `|z| ≤ 64` 内**仅拦截自然生成**：NATURAL / 区块生成 / 结构生成 / 巡逻队 / 增援
- 放行玩家侧与机器间接生成：刷怪笼、刷怪蛋、命令、发射器、繁殖（用户拍板）

### A7 信标效果全图
- 激活的信标效果广播至**其所在维度的全体玩家**，无视距离（用户拍板）
- 效果种类与等级仍随金字塔层数

### A8 物品昵称（命令为主，用户拍板）
- `/dbx nickname <名称...>`：对**主手物品**起昵称；注视方块时对该方块对应物品 id 起昵称
- `/dbx nickname clear`：清除（主手物品优先，注视方块次之）
- 昵称与**物品 id** 绑定：本世界内该 id 的所有实例替换显示名
- 纯显示层：不改 NBT、不动任何属性；数据存世界 SavedData，同步客户端渲染

### A10 战利品地图过滤（全局功能，用户拍板；2026-10-26 修订为「一切地图换纸」）
- 任何战利品箱不再产出任何地图：空地图、藏宝图、Dungeons Arise（含七海）、
  Dungeons and Taverns / Nova Structures 等一切 exploration_map 目的地一视同仁，
  最终都变成 **1 张纸**（`minecraft:paper`）。判定只看物品（`minecraft:map` /
  `minecraft:filled_map` / 任何 `MapItem` 子类），不再看 exploration_map 的
  destination 结构 tag——旧版「按 destination 禁出」的清单已删除
- 三层实现（全部受 `lootMapFilter` 控制）：
  1. **表加载替换**：`LootTableLoadEvent` 把每张表每个 pool 条目树里的地图条目
     **替换**为纸条目（不是移除）。保留原条目 weight / quality / conditions，
     丢弃全部 functions，故产出恒为 1 张纸；alternatives / group / sequence 递归
     重建（父条目 conditions 保留）。所有命名空间、所有表；`/reload` 后重新生效，
     JEI/JER 展示同步干净。pool entries 是 ImmutableList，经 accessor 整体换引用
  2. **运行时短路**：`ExplorationMapFunctionBanMixin` 在 exploration_map 函数
     `run` HEAD 直接返回 1 张纸——不再区分 destination，**绝不执行
     `findNearestMapStructure` 结构搜索**（大搜索半径/未加载区块下很贵）。
     复合条目嵌套、程序化战利品表、经该函数产图的 GLM 都在覆盖内
  3. **最终网**：`PaperInsteadOfMapsLootModifier`（NeoForge 全局战利品修饰器，
     无 conditions）把最终生成的任何 map / filled_map / MapItem 堆换成
     `ItemStack(Items.PAPER, 1)`。序列化器注册在
     `NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS`；数据见
     `data/neoforge/loot_modifiers/global_loot_modifiers.json` +
     `data/dimblend_experience/loot_modifiers/paper_instead_of_maps.json`。
     前两层覆盖不到的「模组直接往 loot list 塞成品地图」路径由本层兜住
- 制图师村民交易不走战利品表，不受影响；关 `lootMapFilter` 时三层全部透传（原版行为）
- 开关 `lootMapFilter`（默认开）

### A11 藏宝箱贝壳 X 标记（全局功能，用户拍板）
- 埋藏的宝藏（buried_treasure）生成时，在宝箱正上方的沙滩表层用贝壳标记方块
  （`dimblend_blocks:shell_marker`）摆一个 X：中心列 = 宝箱列，加四对角共 5 格，
  替换式（换掉表层沙块、与地面齐平），不是在表面上堆方块
- 实现：`BuriedTreasureShellMarkerMixin` 注入原版 `BuriedTreasurePiece.postProcess` 内
  `createChest` 调用之后（该处 boundingBox 已被原版重设为宝箱单格坐标，且只在宝箱成功
  生成的路径执行），`TreasureShellMarker.mark` 从宝箱列向上找地表（头顶实心方块才上移，
  雪层/草/水不算地表，上限 32 格防呆），再对 X 五格各自从中心地表 Y+2 向下找落点
- 只替换 `BlockTags.SAND` 的方块：某臂落点找不到合格沙子就跳过该臂（残缺 X 可接受）；
  宝箱裸露（头顶即非实心，如洞穴穿入）时整标放弃，绝不替换宝箱自身
- 方块在 dimblend-blocks（常驻注册、自身掉落、入建筑方块栏）；experience 侧按注册名
  运行时查找、零编译依赖，dimblend-blocks 缺席时静默跳过
- 仅对新探索区块生效：旧存档已生成的藏宝箱不补标记
- 新开关 `treasureShellX`（默认开）

### A12 旋转维度怪物密度上限压制（2026-10-21 用户拍板）
- 起因：用户实测体感 rotating 怪物密度约为原版两倍，要求压回一半；只做新机制，
  不动「睡觉清雨」等既有行为
- 实现：`NaturalSpawnerCapMixin` 包 `spawnForChunk` 内对
  `SpawnState.canSpawnForCategory` 的调用——rotating 维度且类别为 MONSTER 时，
  全局上限改按 `70×倍率取整 × 可刷区块数/289` 判定（289=NaturalSpawner.MAGIC_NUMBER，
  包私有只能复刻），当前怪物数达缩放上限本轮不刷；未达上限交回原判定
  （原版全局+单玩家局部上限照跑）
- 只压全局密度上限，不改单次刷怪概率；被动/水生等其它类别与其它维度完全走原版；
  纯函数 `RotatingMonsterCap.scaledCap` + 单测
- 新配置 `rotatingMonsterCapMultiplier`（DoubleValue，默认 0.5，范围 0-1；
  1.0 等效原版、0=不自然刷怪；经 /dbx 面板按原版百分比 0–100 可调）

## 2. 板块 B：CDG 柴油机行为

- B1 加燃油启动：转速立即到 16rpm → 每 4 秒 +2rpm 阶梯爬升，直至额定转速
- B2 燃油烧尽 → 正常停机流程
- B3 达到额定后：在 80%~100% 额定区间随机跳变（实现拟每 1~3 秒取一次随机值，可调；2026-10-16 拍板：波动输出取整到整数 rpm，Math.round、不超额定——系数恒 <1 且额定为整数；纯函数 `CdgFluctuationMath` + 单测 `CdgFluctuationMathTest`，普通/组合式/巨型同口径）
- B4 应力过载 → 爆机掉落（用户拍板变更，原"闩锁停机+重新加油重启"作废）：运转中过载连续 40 tick（2 秒）确认后破坏方块、按战利品表掉成物品，油箱余油不返还；加载期误报过滤（2026-09-29 修订）——缓存 `overStressed` 在 `addSilently` 改账后不 sync，会停在过载，而 `(stress, networkSize)` 可以一直不变；确认前若缓存位为真，用 `calculateCapacity/calculateStress` 的实时值复核（`capacity < stress` 才算），实时不成立则不累计并刷掉缓存位（2026-09-30 修订：先 `updateNetwork()`，再按 `calculateCapacity/calculateStress` 实时值经 `CdgOverloadMath.countsAsOverload` 判定，不再只重读缓存位——`updateNetwork()` 仅在网络缓存总量变化时 sync，总量未变时粘住的缓存位原样保留、会被当成过载累计；实时不过载就以实时值 `updateFromNetwork` 只刷本 BE，GameTest `CdgKineticOverloadGameTests` 2 条锁定）；实时过载仍须连续 40 tick，任一非过载 tick 清零（确认计数不序列化）；实时值本身的读档虚高（2026-09-30 修订）——爬梯/波动改转速会触发 Create reapply source（detach + attach）整网重建，已加载未 tick 的成员被 `add()` 收进 members、存档份额却仍留在 unloadedStress，首 tick 的 addSilently 因已在 members 提前 return 永不扣回，整网应力永久多算一份、实时复核照样判过载；由 `KineticUnloadedShareMixin` 在该成员首 tick 前首次换网/被拆时按 addSilently 同口径补扣（对全部 Create 网络生效、无开关），GameTest `KineticUnloadedShareGameTests` 3 条锁定；确认前爬梯/波动计时冻结；过载警告音 `diesel_overstress` 在确认累计首 tick 即播（2026-10-14 修订，原在点引信当 tick），点引信前解除过载（累计清零的一切复位路径）即掐掉、点引信后播完不掐——素材长 6.005 秒（≈120 tick），2 秒确认 + 4 秒引信刚好在爆炸瞬间播完；确认后 4 秒（80 tick）引信倒计时期间每 tick 在引擎中心播 minecraft:large_smoke（delta=0.2,0.2,0.2 / speed=0 / count=10）（引信时长：2026-10-14 修订 6 秒→2 秒，2026-10-15 修订 2 秒→4 秒——用户"再延后两秒"，较最初 6 秒口径仍提前 2 秒）；破坏失败（极端）回退闩锁逻辑；读档宽限（2026-10-01 新增）——开服（`MinecraftServer#getTickCount()` 从 0 起，单人暂停不走）后 `dieselLoadGraceSeconds`（默认 5 秒，0=关闭）内三类柴油机一律不判爆：不累计过载确认（计数保持 0，同未确认口径冻结爬梯/波动）、已在走的引信倒计时暂停，宽限过后重新累计（需再连续 40 tick）；仍照常执行实时复核以刷掉粘住的缓存位；判定为纯函数 `CdgOverloadMath.isWithinLoadGrace`，读配置/tick 封装在 `CdgLoadGrace`
- B5 大型柴油引擎（v1.2 第二版新条目，用户标"未实现"）：热机爬梯 + 额定波动 + 过载损坏，
  与普通/组合式同口径（16/+2/4s，80%~100%/1~3s，爆机掉落余油不返还）。
  调速点 = 传给 `shaft.update(pos, dir, capacity, speed)` 的 speed 参数（ModifyArg）；
  过载信号源 = `shaft.isOverStressed()`（轴为 GeneratingKineticBlockEntity）；
  爆机范围 = **只炸引擎本体**（用户拍板），轴残留停转玩家自拆；状态复用 CdgEngineState 附件。
  原"巨型机不在覆盖内"的已知限制自本条起解除，进入实施。
- B7 爬梯/波动只改转速、应力容量恒按额定（2026-09-30 用户拍板；**2026-10-10 用户拍板回退，已撤除**）：
  玩家实测「全程仅有转速增加和波动，应力输出却维持恒定」，要求回到按转速比例提供应力的口径。
  回退内容：删 `DieselEngineRampMixin#dimblend$divideByActualSpeed`（calculateAddedStressCapacity
  分母换实际转速）、`HugeDieselEngineMixin#dimblend$ratedCapacity`（shaft.update 每转容量放大）、
  纯函数 `CdgRatedCapacityMath` 及其单测/GameTest；每转容量回到 CDG 原样的额定折算，总 SU 随
  爬梯/波动同比缩放。**已知回归风险（回退即接受）**：多台同网（网速取最快一台）或下游转速控制器
  会真过载，确认期冻结波动可锁死缩水系数致同网连锁爆机；单机超额负载的爆机时点回到早期口径。
  以下为 2026-09-30 原设计存档：Create 按"每转容量 × |产出转速|"
  记源容量，CDG 每转容量按额定转速折算，爬梯/波动把总 SU 同比压低——单机应力同比缩、无影响；
  多台同网（网速取最快一台，其余按自身较低转速计容量；两台 100%/80% 实测只剩额定 90%）或下游
  转速控制器（应力不随柴油机转速变）会真过载，确认期冻结波动再把这组系数锁死，同网柴油机一起爆。
  改为：普通/组合式把 `calculateAddedStressCapacity` 的分母 `Math.max(0.01F, 额定转速)` 换成实际
  产出转速（`DieselEngineRampMixin` ModifyExpressionValue），总 SU 恒为额定；巨型机把
  `shaft.update` 的每转容量实参（index 2）按 额定/轴转速 放大，本机贡献 = 每转容量 × max(额定, 轴转速)
  （轴转速高于本机额定的混烧情形保留原版口径）。与 CDG 模拟调速下总 SU 不随转速变的原版口径一致。
  行为差：单机超额负载不再在点火 2 秒后爆，而在爬梯越过 额定 SU/负载 对应转速时爆；随开关
  `dieselEngineBehavior` 关闭即回原版。纯函数 `CdgRatedCapacityMath`（单测 6 条）；GameTest
  `CdgRatedCapacityGameTests` 3 条（爬梯普通机、100%/80% 双机、爬梯巨型机；修前分别 1024/11059.2/1170.3
  对额定 6144/12288/16384），需 `-PwithCdg` 把 CDG 提升进 dev 运行，仅 CDG 在场时注册。
- B8 放置柴油机时所接网络必须已静止（2026-10-10 用户拍板）：放置三类 CDG 柴油机
  （普通/组合式/巨型）时，其接入的 Create 动力网络转速必须为 0；放到运转中的网络上
  （含被其他机器带着转）则放行放置后破坏自身按 loot 掉落，不豁免创造模式。
  实现 `CdgPlacementGuard`（`EntityPlaceEvent` 登记 + `ServerTickEvent.Post` 判定）：
  BE 放置当 tick 进 pending 缓冲、首 tick 才 `attachKinetics` 写入网络传导转速，当 tick 读
  `getSpeed()` 恒 0，故登记后顺延一个完整 tick 再判——普通/组合式读引擎 BE `getSpeed()`
  （未加油新机自身产出为 0，非零只可能来自所接网络）；巨型机本体非 Kinetic，读 `getShaft()`
  轴 BE 转速（轴 null = 未接任何网络，放行）。不取消事件只放行后拆（取消会致客户端物品鬼影，
  同 F1 口径）。已知覆盖边界：蓝图炮/`/setblock`/直接 setBlock 不经 `EntityPlaceEvent`，不拦。
  开关 `dieselEnginePlacementGuard`（默认开）。GameTest `CdgPlacementGuardGameTests`，需
  `-PwithCdg`，仅 CDG 在场时注册。
- B9 柴油机 boost 启动（2026-10-15 窗子拍板定稿）：柴油机运转且未达额定
  （"启动直到达到额定转速"期间），每次点火会话仅一次——若 |网络转速| > 当前阶梯
  转速 rampTarget，把 rampTicks 前推使 rampTarget = min(|网络转速|, 0.8 × 额定)，
  之后阶梯照常每 80gt +2rpm 爬完剩余部分。设计动机：借网络转速实现 boost 启动，
  封顶 80% 留 20% 自爬（窗子拍板）。阶梯公式（两 mixin 共用）：
  stepped = min(16 + 2×(rampTicks/80), rated)；逆推 rampTicks = floor((target − 16) × 40)
  （整数除法向下取精不 overshoot）。网络 ≤ 自身、对齐目标 ≤ 当前 rampTarget、
  目标 ≤ 起步 16 均不触发；封顶 0.8×rated 恒小于 rated，B3 波动/饱和判定不受影响；
  过载确认期分支早退（爬梯冻结），boost 自然冻结无需特判。"每会话一次" =
  `CdgEngineState.boostConsumed` 消费标志，随 CODEC 持久化（防读档中途重置多送一次；
  新增第 9 个必填字段，与 commit 3a25aee 同口径——mod 未投产、不容忍旧档），
  rampTicks 复位（停转/燃尽/重新加油=会话结束）时清除；窗子补充：不考虑红石关停
  场景（该用法已禁用），会话边界=真实停机/再点火。网络转速读取同
  `CdgPlacementGuard` 先例：普通/组合式读引擎 BE `getSpeed()`、巨型读轴 BE
  `getSpeed()`，取绝对值；额定口径与各 mixin 现有饱和判定同源（普通/组合式
  upgrade.getSpeed(fuelSpeed)×throttle；巨型 |fuelSpeed×throttle|，1.3.15 上游不乘
  upgrade 保持）。组合式 isController 语义与现有一致（非 controller 油箱恒空走不到
  running 分支）。hook 位置：running 分支 `rampTicks++` 之前
  （`DieselEngineRampMixin` / `HugeDieselEngineMixin`）。无独立开关——作为爬梯固有
  行为随 `dieselEngineBehavior` 总门控。纯函数 `CdgBoostMath` + 单测
  `CdgBoostMathTest`（目标封顶/逆推不 overshoot/不触发四形态/续爬步数）。待实测。
- B10 柴油机小号/中号燃料容量降到 100mB（2026-10-15 用户需求）：CDG 硬编码油箱
  容量——普通（小号）`DieselEngineBlockEntity.addBehaviours` 的
  `SmartFluidTankBehaviour.single(this, 1000)`（javap offset 46 `sipush 1000`，
  全类唯一 1000）与组合式（中号）`ModularDieselEngineBlockEntity` 构造器
  `new SmartFluidTank(1000, consumer)`（offset 24）+ `read(...)` 连通性变化
  isController 分支 `tankInventory.setCapacity(1000)`（offset 268 isController →
  278 sipush 1000，全类仅此两处 1000）——经两个单目标 mixin（`CdgEngineTankCapacityMixin`
  普通、`CdgModularEngineTankCapacityMixin` 组合式）按方法限定 @ModifyConstant 替换为
  配置值（每方法内 1000 唯一、无误伤面；三处注入 require=1 随 mixins.json
  defaultRequire 启动期钉死——CDG 改常量面/方法签名即 InjectionError 即响，含仅
  客户端路径的 read 重断言（GameTest 服务端环境覆盖不到，更依赖启动期钉死）；
  plugin 按 CDG 在场过滤）。CDG 配置文件无油箱容量项
  （TrainTripWorld/config 三个 createdieselgenerators-*.toml 已查），只能 mixin。
  大号（巨型）原生 `single(this, 100)` 为 CDG 原样、本需求不动。新整数键
  `dieselEngineFuelTankMb`（默认 100，范围 1-16000；1000 恢复 CDG 原生；不加 /dbx
  面板行——用户没要求，避免快照协议 churn）。生效机制（javap read offset 201-205
  `iload_3; ifne 206; return` 核实：clientPacket=false 即服务端磁盘读档直接 return，
  走不到 setCapacity 分支）：双端容量均经构造器定型（普通 addBehaviours / 组合式
  构造器）——新放置/区块重进（BE 重建）即生效；read 重断言的真实生效面是客户端
  同步包路径（read(clientPacket=true) 且连通性/长度变化时到达）——防客户端容量被
  重断言回 1000 与服务端分叉；配置未加载（标题画面等）透传原生 1000。
  **存量兼容（接受项，NeoForge 21.1.249 FluidTank
  javap 核实）**：setCapacity 只写 capacity 字段、不截断存油；fill 按 容量−存量
  计空间——容量从 1000 降到 100 后，箱内超出 100 的存油保留烧完为止、不能再补到
  容量以上。配置未加载时 handler 透传原值（SERVER 配置 NeoForge 进服同步客户端，
  双端同值无分叉）。GameTest
  `CdgTankCapacityGameTests` 3 例（普通/组合式容量==配置值、配置 1000 恢复原生，
  需 `-PwithCdg`，仅 CDG 在场时注册）。待实测。
- B11 柴油机忽略红石信号（2026-10-16 确认）：`EngineThrottleLockMixin`
  双端锁定 `IEngine#getThrottle()=1`，并让 `IEngine#enabled()` 仅保留原生
  `validFS()`（无油/无效燃油仍停机）；`ModularEngineRedstoneMixin` 同口径覆盖
  组合式 override 的成员红石检查。模拟调速开/关、红石停机配置开/关均不影响结果。
  随 `dieselEngineBehavior`，关闭恢复原生；配置未加载按默认开启；耗油倍率恒满档。
- B12 空油箱护目镜提示：三种机型以 WrapMethod 包装 `addToGoggleTooltip(List,boolean)boolean`，
  原始内容之后追加灰字「提供燃料起转 需要时间达到满功率」和
  「提供更高的转速以更快达到满功率」。非 controller 的组合机跳过外层追加以避免委托重复。
  每个包装恰好匹配一次；实际渲染仍待客户端验证。

2026-10-16 净水、铜水槽/铜龙头、照片兼容的完整规则与验证见
[本批兼容规格](compat-water-2026-10-16.md)。

## 3. 板块 C：创造模式伪装方块（Create + Copycats+ 扩展）

- C0 全体伪装方块硬度统一黑曜石（用户拍板口径）：`copycats` 命名空间全部方块 +
  Create 本体伪装三件套（CopycatBlock / Panel / Step），`destroyTime=50`、
  `explosionResistance=1200`。实现为两根基类构造器注入（Copycats+ 侧
  `CCCopycatBlock` + `MultiStateCopycatBlock`；Create 侧 `CopycatBlock`，含
  Waterlogged 子类链），`-1`（已不可破坏）直接豁免——创造变体保留生存不可破坏。
- C1 创造模式伪装半砖：基于伪装半砖的 上半/下半/双砖 变体家族
  （v1.2 备注：用户标"未实现"，但开发侧已实现——方块+自有 BE+创造栏+模型+lang+
  启动验证，记"已实现待实测"）
- C2 创造模式伪装粱：基于伪装粱
- C3 创造模式伪装板：基于 **Create 本体的 `CopycatPanelBlock`**（用户拍板基底，
  非 Copycats+ 的 Board）；BE 用本模组自有的 `CopycatBlockEntity` 类型
  （Create 的 COPYCAT 类型白名单注册期固定，第三方块进不去——同 C1/C2 理由）
- 共同规则：
  - 无合成配方（不可制作）
  - 仅创造模式可放置、可破坏；生存完全不可破坏（硬度 -1 不可破坏路线）
  - 扳手只能移除贴图材质，不能拆下方块本体
  - 顺带防爆（与列车使用场景一致，核对时未提出异议）
- 用途：C1 制作车架、C2 制作车勾
- C4 创造模式洁净伪装板/洁净伪装半砖（v1.11 新增）：行为同 C3/C1（含 C5 隐藏能力、
  仅创造放置/破坏、扳手只撕材质），供洗车正常洗车够不到的死角使用。实现上按注册名判定
  天然不是 dimblend-carwash 的车架（`ChassisBlocks.isChassis` 身份判定不含洁净变体），
  永不积脏；BE 用独立的洁净类型（`creative_clean_create_copycat` /
  `creative_clean_multi_state_copycat`），洗车脏值行为绑定不到。洁净=无脏值概念，
  不是「脏值锁定为 0」。
- C5 伪装板/半砖隐藏（v1.11 新增，粱不适用；2026-10-22 修订放开生存）：
  - 手势：未贴材质（板：无自定义材质；半砖：全部件无自定义材质）时，主手空手
    右键 → 隐藏；隐藏时扳手（`c:tools/wrench`）右键 → 恢复正常。2026-10-22 用户拍板：
    隐藏/恢复放开生存模式（原仅创造玩家、与放置/破坏同权）；冒险模式同权
    （不加模式判断，复核在案）；放置/破坏仍仅创造。
    有材质时空手行为保持上游语义（半砖 toggleCT 不动）。
  - 隐藏期间：不渲染（手持扳手时客户端按真实方块状态幽灵显现、恢复选中框；未持扳手连
    选中框也抑制）；幽灵显现带冷蓝着色（`CreativeCopycatGhostRenderer.GHOST_TINT`，
    0.5/0.75/1.0），与未贴材质的普通伪装方块肉眼区分（2026-10-22 拍板）；
    碰撞箱保留；一切物品交互穿透（贴材质/连放禁用）；洗车脏值冻结
    （增长总闸在 `ChassisGrimeBehaviour.changeDirt` 的 `delta>0` 分支；已有脏值保留、
    隐藏期不渲染，渲染抑制口在 `ChassisGrimeBehaviour.visualAt`）。
  - 不变量「隐藏 ⇒ 无材质」由手势前置条件 + 隐藏期禁贴材质共同维持。
  - 实现：隐藏状态挂 BE NBT（`CreativeHidden`，`CreativeCopycatHidable` 接口 +
    `dimblend.blocks.api.CreativeCopycatHiding` 静态查询口），不走方块状态——Sable
    子关卡里改 BlockState 会触发物理碰撞体/质量更新（carwash 既定约束）；同步走 Create
    `sendData` + 客户端区段重绘（同脏值纪律）。carwash 只 import `dimblend.blocks.api`。
    服务端 GameTest `CreativeCopycatHidingGameTest` 6 条（生存/创造隐藏、持物不误触、
    生存扳手恢复板与半砖），需 `-PwithCopycats` 把 Copycats+ 提升进 dev 运行。

## 4. 板块 D：CCA 电动马达

- D1 收到红石信号才开始工作，无信号停转
- D2 信号强度 1–15 线性映射目标转速：1 → 4rpm，15 → 额定（马达面板设定值）；
  **实时随动**（v1.2：原实现只在 4 个边沿传播点重算，稳态运行信号变化不跟——改每
  服务端 tick 重算，motorSpeed 变化超 ε 才调 `updateGeneratedRotation()` 传播，
  避免每 tick `sendData` 刷屏）
- D3 耗电下限：**面板 <4rpm 按 4 计**（v1.2 改回：纯线性保留，但 `|rpm|∈(0,4)→4` 钳定；
  无信号 0 转仍 0 耗）；`Math.max` 下限钳定向保留 bypass（4 的线性项仍低于原 8 下限）。
  映射侧不变：面板设定 ≤4rpm 仍按面板直通（如 2rpm→实际 2rpm，只耗电按 4 计）
- D4 设定转速 >128rpm 时，收到信号启动后循环播放运转声音（v1.1 口径：16→64；2026-09-27：64→128，严格大于）；
  v1.4：启动音立即播放，运转循环延迟 3 秒（60 tick，由客户端状态机每 tick 推进）
  后起；延迟内信号消失则取消起循环并播停机音。
  **可听范围须单声道素材（2026-09-24 复查）**：16 格线性衰减只对单声道源生效——
  OpenAL 对立体声源不做空间化与距离衰减，原 loop/startup 为立体声、实际全距离满音量；
  已用 libsndfile 解码 L/R 平均降混为单声道、峰值归一 -1dBFS、采样数与原件一致
  （ffmpeg 自带 vorbis 解码会吞首 128 采样，不能用它做中转，否则循环长度被截）。
  **运转音按固定间隔起单次音（2026-09-24 用户要求）**：素材换 `motor_running (1)`（1.32 秒，
  自带约 0.2 秒渐强与 0.45 秒渐弱，素材不裁剪），启动延迟 3 秒后每 5 tick 起一条单次
  `MotorLoopSound`，间隔与素材时长无关（约 5-6 条同时叠播）；代码无淡入淡出；
  素材本体增益 +11dB（峰值 -3.8dBFS；实例音量上限 1.0 且超 1.0 只扩射程，故增益落在 ogg 本体）；
  停机/移除/热关闭时掐断全部在播实例。
  **v1.7 根因修复（用户回标"未实现"成立）**：原实现运转判据读 CCA 的
  `active` 字段，但 CCA 1.5.10 只在 `writeSafe`（原理图路径）写该键、不覆写
  `write`，客户端同步包（sendData → writeClient → write(tag,true)）内永无
  "active" → 客户端 active 恒 false，三态机永不触发。现判据改读
  KineticBlockEntity 的 `speed`（"Speed" 标签按实际值同步，D2 映射后输出转速）：
  `|speed|>0 且 |面板|>128 且无外部源`（`!hasSource()`，Source 标签进同步包，与 D6 同判据；
  2026-09-27：被更强动力源反拖时轴在转但不播，此前为照播）。接受边界：
  过载/冻结网络下服务端 active 仍 true（照常耗电），循环继续。
  **2026-09-23 用户追加**：三处音效音量减半（one-shot 实例音量 0.5、循环
  `MotorLoopSound.LOOP_VOLUME=0.5`）、可听范围约 16 格（sounds.json 三条目
  `attenuation_distance=16`；循环另显式 `Attenuation.LINEAR` 作防回归/自文档
  （基类默认即 LINEAR））
- D5 交流发电机无输入自放电（2026-09-23 新条目；2026-09-25 用户回标收敛：此前
  |rpm|<16 口径下 1~35rpm 产电跑不赢 50FE/t 漏电、有输入也净减少，收敛为无输入才放）：
  仅当本 tick 不产电（产能门 `|speed|>0 && isSpeedRequirementFulfilled()`
  为假：停转 0rpm / 过载·冻结网络读数归零 / 最低转速门未满足）时内部
  储存 FE 自行减少约每秒 5000（每 tick 250，扣到 0 为止；有输入产电时不扣；2026-09-25 用户改量：1000→5000）；
  读数口径与产能门一致用 `getSpeed()`（过载/冻结返回 0，归并入流失判据）；
  新开关 `alternatorIdleDrain`（默认开）；`AlternatorIdleDrainMixin` 注入
  `AlternatorBlockEntity.tick` HEAD（ServerLevel 守卫 + config 门控）
- D6 马达护目镜显示改实际转速响应值（2026-09-23 新条目）：
  ① "已使用的能量"行原按面板设定值取数（`getEnergyConsumptionRate(generatedSpeed.getValue())`），
  现以 ModifyArg 直改该静态调用 float 入参为 |实际转速|（float 直通无截断）——
  D3 钳定同步生效、无信号显示 0，自驱动下与服务端实扣完全一致；② "应力量"行（Create
  `tooltip.capacityProvided`）原版即因客户端 `getGeneratedSpeed()` 恒 0
  （active/motorSpeed 不同步）而折算系数 ×0、恒显示 0 su，现对马达实例把
  折算读数替换为实际转速 → 显示 MAX_STRESS/256 × |实际映射转速|，与
  KineticNetwork 实际入网容量一致。两处均挂 `electricMotorBehavior`
  （关=透传原值）；**2026-09-27 反拖回退**：`hasSource()` 为真（Source 标签进
  客户端同步包）时两处改写统一跳过、回退原版——反拖无电时"应力量" 0 su
  （与服务端 0 入网一致）、"已使用能量"按面板原值；接受边界：通电同时被更强源
  反拖本机确有出力但显示被压，D7 锁存期若恰有外部源 ×2 显示同样丢失。
  client 侧 mixin（`ElectricMotorGoggleMixin` +
  `ElectricMotorGeneratorStatsMixin`，后者目标为 Create
  `GeneratingKineticBlockEntity.addToGoggleTooltip`，handler instanceof
  限定马达）。实际转速一律经 public `getTheoreticalSpeed()` 读取
  （mixin 继承字段 @Shadow 不受支持——findAliasedField 只解析目标类自
  声明字段，父类字段 shadow apply 期抛 InvalidMixinException，运行库
  sponge-mixin 0.15.4+mixin.0.8.7 fork 字节码核实）
- D7 马达过载锁存（2026-09-26 新条目）：进入判据 kinetic 网络过载（`isOverStressed()`）
  且 |面板|>64rpm 且运转中（active 且理论转速非零）；进入瞬间记录红石强度并冻结输出转速，
  期间一切外部更改（信号增减/撤除、面板扳手、CC setRPM）经 recompute 统一旁路直接丢弃，
  D1 维持判据旁路（撤信号也不停转）；耗电按实时 rate×2（调用点翻倍，冻结值故数值恒定）；
  服务端每 tick 方块中心 `enchanted_hit`（count=5、delta=0、speed=0.5）+
  每 20 tick `motor_overstress`（音量 0.5，单声道 ogg、attenuation 16 格，与 D4 三态音叠加共存）。
  退出：过载恢复（tick HEAD 判）或 active=false（FE 耗尽断电，TAIL 补判——CCA 主体后半才写 active）
  任一即清锁存；纯内存不写 NBT，区块卸载/重进按新进入重判；开关关闭清锁存透传原版。
  护目镜“已使用的能量”锁存期×2（客户端三项已同步状态派生等价判据：过载 + |面板|>64 +
  理论转速非零；Network/Speed/behaviour 三标签全同步）。实现：`compat.cca.MotorOverstressLatch`
 （无状态 helper）+ `ElectricMotorMixin`（HEAD 驱动/TAIL 补判/重算旁路/D1 旁路/耗电 MEV×2）
  + `ElectricMotorGoggleMixin`（显示×2），全挂 `electricMotorBehavior`。待实测。
- D8 交流发电机过载解列锁存（2026-10-14 新条目，用户拍板）：背景——发电机网络应力需求 =
  `KineticNetwork.getActualStressOf` 的 members 缓存 impact（`calculateStressApplied()`，
  CCA 恒定 MAX_STRESS/256）× |`getTheoreticalSpeed()`| 实时读数；而 Create 6.0.10 的
  `updateNetwork()` 只在 networkDirty（拓扑增删）时重算（`RotationPropagator` 传播转速只
  setSpeed、不 dirty——javap 核实），故需求随速度变化落到账本有迟滞，过载后需求残留、恢复慢。
  改为：进入判据 kinetic 过载（`isOverStressed()`）且被驱动中（理论转速非零，含负向）
  且本机已在网络账本上（`members.containsKey(self)`——读档竞态门控：读档后
  network/speed/overStressed 均从 NBT 恢复，首 tick HEAD 即满足前两项，但
  `initialize()`（SmartBlockEntity.tick 首 tick 惰性调用）尚未跑，其内 `addSilently`
  负责把存档份额从 unloadedStress 扣回并 put members、第一句即 `members.containsKey`
  早退；若 updateStressFor 抢先 put，结算被跳过、存档份额幻影残留 unloadedStress
  （应力虚高并落盘累积、解锁后双计）。门控把读档路径进入推迟到第二个 tick，
  正常放置 attachKinetics→add() 首 tick 前已入 members 不受影响）→
  置锁存 + `KineticNetwork.updateStressFor(self, 0)` 即时清零 members 缓存并触发
  updateStress→sync（本机造成的过载当 tick 解除）+ sendData + 当 tick 即播副作用；
  锁存期 `calculateStressApplied` RETURN 钩子压 0（网络 split/merge 重 add 等重缓存路径
  仍得 0，锁不被拓扑变化顶掉），服务端每 tick 方块中心 enchanted_hit×5 + 每 20 tick
  motor_overstress 音量 0.5（复用 D7 `MotorOverstressLatch.tickEffects` 同素材同参数）；
  **FE 产出同样锁 0**（2026-10-15 用户需求变更，原"锁存期照常空转产 FE——只摘应力需求、
  不断产能"口径作废）：`tick()` 内产能率 `getEnergyProductionRate(I)I` 唯一调用点
  （javap 核实 offset 67 invokestatic，本类静态方法、无重载）经 MEV 压 0，
  `internalProduceEnergy(0)` 照走、buffer 不再入账；**只锁产出、不锁输出**——buffer
  存量（默认 5000 FE）继续经六面输出 loop 流出直至放空（约 1 tick），属拍板语义。
  护目镜产能行经同签名 MEV 同压 0（`addToGoggleTooltip` 内调用点 javap 核实 offset 47；
  客户端锁存字段经同步包回写，显示与实际一致）。D5 漏电门（`AlternatorIdleDrainMixin`，
  产能门 `|getSpeed|>0 && isSpeedRequirementFulfilled()` 的取反）不受影响：锁存期转速
  非零、产能门仍为真，不会并发漏电（交互已评估）。
  退出（2026-10-15 用户拍板改口径，原"理论转速归零"作废）：理论转速与进入锁存时
  记录值（`dimblend$latchedSpeed`，进入时快照、纯内存不落盘）**不同即解锁**——精确
  比较、不加阈值（Create 转速传播是确定性浮点，同拓扑同源逐 bit 相同；split/merge
  微差导致误判解锁也接受，明确不做任何防翻动处理）；停机归零天然满足（记录值必非零，
  进入判据要求非零），新判据是旧判据超集；过载冻结只压 `getSpeed()` 读数、不动
  speed 字段，`getTheoreticalSpeed()` 不受冻结影响不误判）→ 清锁存 + updateStressFor
  恢复真实 impact（转速 0 下账本数值不变、无 sync 抖动）+ sendData；开关关闭同样
  清锁存恢复。**拍板接受项（重锁语义）**：再次满足进入判据即重新锁存——重录记录值、
  重播进入音效/粒子（latchAge 归零）；窗子原话口径"锁存的音效粒子本身就是一直播放、
  需要一直吵提醒玩家处理，柴油机 B3 波动（额定后 80%~100% 每 1~3 秒跳变）造成的
  频繁解锁→重锁不构成问题，可以不处理"。
  锁存标志经 Create KBE `write`/`read`（clientPacket=true 才写读）进客户端同步包、
  磁盘不落（与 D7 同例纯内存，区块卸载/重进按新进入重判）；客户端护目镜应力段随
  calculateStressApplied 归零整段隐藏（KBE.addToGoggleTooltip 的 Mth.equal 早退），
  显示与实际入网需求一致（客户端无法像 D7 那样从已同步量派生锁存——锁存成功时网络
  恰好不过载，故显式同步）。实现：`compat.cca.AlternatorOverstressLatch`（判据 helper）
  + `AlternatorOverstressLatchAccess`（跨 mixin 字段访问口，免 Create 目标类引用 CCA 类）
  + `compat.cca.AlternatorOverstressLatchMixin`（CCA 目标：HEAD 驱动 + RETURN 钩子
  + 锁存期产出/护目镜两处 `getEnergyProductionRate` MEV）
  + `compat.create.AlternatorLatchSyncMixin`（Create KBE write/read TAIL，instanceof 门控），
  挂新开关 `alternatorOverstressLatch`（默认开）；单测 `AlternatorOverstressLatchTest` 钉
  进出判据。待实测。


## 5. 板块 E：Simurail

- E0 架构结论（已读源码确认）：列车以 **Sable sub-level 为单位整体运作**，转向架 `PhysicsBogeyBlockEntity` 以物理轮轴挂载其上；音效按整车口径播放（E2 整车单循环、E3/E4 整车广播；仅 E6 宽轨粒子仍按转向架粒度，与现有 rumble 一致）
- E1 Simurail **自带方块**防爆防拆，仅创造模式可拆改。2026-10-20 用户补充：生存模式可以右键物理转向架（车架），使用原生界面调整参数和外观；普通右键及双手空手时潜行右键沿用 Simurail 行为。只放行方块交互，工具/物品使用仍禁止，不能借潜行扳手拆走转向架；冒险/旁观不放行。自动车钩仍由原有独立开关控制。
- E2 整车按速度播放行进音效（v1.4 口径 + 2026-09-24 整车单实例收敛 + 短促脉冲）：pitch 分段线性——
  2m/s→0.5、10m/s→1.0、16m/s→1.5；低于 2m/s 静音、高于 16m/s 钳制 1.5；
  音量固定 1.5（代码 `FULL_VOLUME`，不随速度；v1.2 ×3 口径后 2026-09-23 用户条目行进声减半：3.0→1.5）；淡入 5 秒（100 tick），淡出 1 秒（20 tick，2026-09-24 用户条目"车架停止时停止播放"）。
  **整车只由 leader 起脉冲**：同维度新鲜成员经"同子层级 / 25m 车钩邻接"连锁闭包成整列（`TrainLoopLeader`），闭包内按 identityHashCode 最小者当选 leader，只有 leader 按集群最大速度驱动包络，并以固定 1 秒（20 tick）间隔起单次 `bogey_track_loop`（间隔不随音调拉伸）；车场内 60m 开外的他车链不上，自然分离、不抢播；已知接受项：相邻股道 25m 内并排同速两车可能并入一簇（一车静默，10m 内听感无差）。BE 卸载致 mixin 停驱时孤儿看门狗（30 tick）同样快速淡出自停。
  **v1.2：仅行进循环音量×3**（用户拍板；2026-09-23 起刹车音量减半，松闸维持原音量，见 E3/E4；2026-09-23 用户条目行进循环音量减半，见 E2）。
  **根因修复（2026-09-23 用户回标"未实现"成立）**：循环以零音量淡入起播，
  但 vanilla `SoundEngine.play` 在起播计算音量为 0 且 `!canStartSilent` 时直接
  跳过（"Skipped playing sound, volume was zero"，合并 jar 字节码核实）——实例
  永不进入播放集合、tick 淡入永不执行，全车架无声。`BogeyTrackSound`
  现覆写 `canStartSilent` 恒返 true；定位仍经 sable 移动声委托逐帧变换到世界坐标。
  注意引擎钳制：`SoundEngine` 峰值响度封顶 1.0，超 1.0 部分只扩大可听距离
  （约×3 射程）。用户拍板**直接放大 ogg 本体**（ffmpeg 线性增益、峰值归一到
  -1dBFS，保循环无缝）：brake_release +8.9dB、electric_motor_loop +11.1dB、
  electric_motor_stopping +6.5dB、train_brake +22.8dB；
  bogey_track_loop（峰值 -0.7）与 startup（峰值 0.0）已满幅未动
- E3 刹车时播放刹车音效，**音阶 1.0、音量 0.5**（v1.4 拍板音阶 1.0；
  2026-09-23 用户条目音量减半：代码 `BRAKE_VOLUME=0.5`）；
  **v1.8 引入速度门，v1.19 起仅 |visualSpeed| &gt; `trainBrakeSoundSpeedThreshold` 时播放**
  （阈值默认 2.0 m/s、0-64 经 /dbx 面板按 0.1 调节；等于阈值不播；沿基线仍更新，
  低速施闸后加速不补沿。松闸 E4 不受此门）
- E4 解除刹车时播放泄气阀音效（v1.2 确认**保留**，`brake_release` 维持原参数：音量 1.0、音阶 1.0）
- E3/E4 触发沿：刹车强度 0→正施闸播 `train_brake`，正→0 松闸播 `brake_release`
  （服务端沿检测；强度读运行 jar 实有的 `getBrakeStrength()`，新版源码的
  `getGroupBrakeStrength()` 在运行 jar 中不存在，见已知限制）。
  **根因修复（2026-09-23 用户回标"未实现"成立）**：① 触发从
  `sable$physicsTick` 移到服务端 `tick()` RETURN——物理步进在列车静置/休眠时
  不跑，刹停车辆的沿会被漏掉，tick 分支每游戏 tick 常跑；② 播音坐标须先
  `Sable.HELPER.projectOutOfSubLevel` 投影到世界坐标（`AutomaticCouplerBlockEntity`
  同型），转向架 `getBlockPos()` 是子层级局部坐标，服务端 `playSound` 不做路由，
  此前声音落在 plot 区、车旁听不见。沿仍在受电车架自身上检测
  （运行 jar 无编组级刹车传播），但触发后经 `TrainBrakeBroadcast` 向同列整车补播
  （注册表内距沿位置 ≤120m 且 |visualSpeed| 差 ≤1.5m/s 的成员逐个播音，单次上限 48；
  2026-09-24 实测：此前只响受电单架，判失败）：每个沿整列各位置都播，即"所有车架都会播放"。
  已知接受项：同速并排邻车可能误播；静置施闸时全员速度为 0，天然同窗。
- E5 取消自动车钩的红石信号解锁机制（用户拍板：仅车钩，贯通框 `GangwayFrame`
  的同类红石断开逻辑不动）；实现为 `AutomaticCouplerBlock.neighborChanged`
  HEAD 取消，`simurail` 在场条件加载，新开关 `couplerRedstone`（默认开）
- E6 幻纱宽轨末地烛粒子（2026-09-22 新条目，纯客户端视觉）：车架（转向架 BE）
  世界坐标脚下（y-1）是幻纱宽轨（`railways:track_phantom_wide` 精确匹配，其他宽轨不播）时，在其 5x5 平面生成下落的末地烛粒子
  （`END_ROD` 加下坠初速，密度每 tick 每格约 40%——2026-10-15 用户拍板：范围 3x3→5x5、密度 0.2→0.4 翻倍，与 E7 锁存粒子同步改，25 格期望约 10 个/tick/车架）；动静都播；
  与行进声同粒度（按转向架）；新开关 `wideGaugeParticles`（默认开）；
  宽轨方块 id 以运行实例 railways jar（0.2.0-beta.2）注册表核实为准（2026-09-23 用户条目：仅幻纱宽轨）
- E7' 无人值守刹车锁存（v1.18 重做，仅 rotating；旧「离结构传送」废弃；2026-10-15 实测修订）：
  每 10 tick（0.5 秒）在维度 Post tick 扫描一次；转向架 tick 仅采集速度和信号，
  扫描时统一投影当前世界坐标，避免首架 tick 扫描半份登记表及沿用音效缓存旧坐标。
  编组界定：同一 Sable 子层级内的车架归为一车厢，按自动车钩的真实 partnerSubLevelID
  连接做传递闭包（含无车架的中间车厢）；不按 24 格距离猜连接，并排他车不再误并入，
  长车厢也不会因架间距过大误拆组。未加载的伙伴不强制加载、不生成虚构连接。
  逐编组问责：编组内存在 |visualSpeed| &gt; 4 m/s 的车架（编组在动），
  且编组上离<b>每个</b>非旁观玩家最近的车架都超出值守半径（`trainDeadmanRadius`，默认 32 格，
  8-128 经 /dbx 面板可调）时，该编组全列进入<b>刹车锁存</b>。距离对整列编组量、不看最近架自身
  速度（2026-10-15 实测修订：此前「离玩家最近的车架必须超速才算跟车」在玩家身边存在任何
  低速架——旁边停放的车、编组中未加速到 4 m/s 的架——时误判无人值守，列车明明在旁却紧急
  刹车）。锁存期间 `getBrakeStrength()D` RETURN 经 `PhysicsBogeyDeadmanBrakeMixin`
  强制 1.0 满刹（正控/反控一致；E3/E4 沿检测同读此方法，锁存/解除自动伴随施闸/松闸音效沿）。
  玩家坐标先 `projectOutOfSubLevel` 投影（车上乘客落在车体位置，天然值守）。
  **解除由编组内任意车架原始红石等级变化触发，立即解除该编组所有车厢的紧急刹车**
  （2026-10-15 后续新需求，取代此前完整通断脉冲）：0→正、正→0、正→另一正等级均有效。
  读取 Simurail 同一安全输入面上未经 E9 强度上限压顶的原始 0-15 等级，因此 1→2 等变化
  不会被默认 1 级上限遮蔽。新加载车架首采样仅建立基线，不视为信号变化。
  解除只作用于当前真实连接编组，其他列车保持自身锁存；信号变化当次更新不建立新的紧急锁存，
  下次距离扫描若仍超速且无人值守，可重新施加紧急刹车。接入已锁存编组的车厢同步锁存，
  拆开后各编组分别锁存/解除；临时卸载的车厢 UUID 保留状态，返回时继续按当前连接判定。
  锁存不自动解除、不随停车解除；内存态，维度卸载和服务器停止时清空，切换存档不继承。
  **锁存强制驱动网络过载（2026-10-15 窗子拍板，实施方式放权）**：意图——锁存要保持
  "掉下车=事故"而非"主动跳车=免费自动停车"，代价 = 锁存期间让驱动列车前进的 Create
  网络过载，触发动力源（CDG 柴油机）自身过载惩罚（B6：2 秒确认警告 → 4 秒引信 → 爆炸）。
  实现：`PhysicsBogeyDeadmanStressMixin` @ModifyReturnValue `calculateStressApplied()F`
  把锁存转向架的 impact 读数替换为 `TrainDeadmanBrake` per-bogey 表中的强制值；
  强制 impact 按网络当前总容量反算
  `forced = (capacity × 1.5 + 1) / max(|theoreticalSpeed|, 1)`（Create 需求 =
  缓存 impact × |理论转速|，容量与需求都随转速正比缩放，比值跨转速恒 ~1.5 倍；
  max(...,1) 防零转速——锁存时网络若停转，一旦启动立即过载）；锁存瞬间无网络回退
  65536（窗子原数，兜底）。路径选择：反算收敛在 `TrainDeadmanBrake.syncForcedImpacts`
  （服务端主线程、锁存变化或扫描周期）存表 + 显式 `updateStressFor` 立即生效/恢复
  （impact 入网络时缓存进 members，锁存/解除瞬间 Create 不会自动重算，必须显式推；
  禁止逐 tick 推——sync 抖动），MRV 钩子只读表作 split/merge 重缓存路径兜底——
  不在钩子内读网络容量：`calculateCapacity()` 迭代 sources 会顺手剔除失效 source
  （javap 核实，有副作用），且钩子会被网络更新重入调用，不干净。多个锁存转向架
  各自压自己所在网络，允许。**既定后果（拍板接受）**：过载期间整个网络停摆
  （Create 原生语义，代价本身）；同网络 D8 发电机会跟着解列（预期联动）；玩家抢救
  窗口 = 过载确认 2 秒（红石脉冲解锁/把转向架断网），引信点燃后不可挽回；过载只压
  getSpeed 读数，不影响 E7 锁存自身逻辑（用 sable 物理 m/s 速度）与红石脉冲解除路径。
  已知接受项：强制值按进入锁存时容量快照反算，之后网络拓扑变化（增源扩容 >1.5 倍）
  可能不再过量，不追踪刷新；维度卸载/重进时表随 BE 清，扫描周期内补算（至多 0.5 秒
  窗口按真实 impact 计）。不加配置键（E7' 总开关 trainDeadmanBrake 覆盖）。
  锁存车厢 UUID 完整快照经 `DeadmanLatchPayload` S2C 广播（协议版本 2；进服/换维度由 `TrainDeadmanSync`
  补发），锁存期间客户端仅在已锁存车厢的转向架播 `PORTAL` 粒子（E6 同参数：y-1 上方 5x5、
  每格每 tick 40%、下坠初速 -0.25——2026-10-15 用户拍板范围 3x3→5x5、密度翻倍，两功能同步改）。新开关 `trainDeadmanBrake`（默认开；
  关闭即解除全部锁存并广播）。simurail 缺席时 mixin 不应用、无登记即无扫描，
  fail-open。旧键 `offStructureTeleport` 作废自动清除。
  验证：编译及无人值守/速度门槛局部单测；用户已确认此前距离刹车实测正确；新的信号等级变化整列解除、他列隔离与粒子同步待实机验证。
  连接语义仅覆盖 Sable 同车厢及 Simurail 自动车钩；其他模组的连接器不自动视为同列。
- E8 车架随机横向力（2026-09-24 新条目，全维度，服务端）：逐车架（转向架 BE）独立计时——
  |visualSpeed| &gt; 4 m/s 期间每隔固定 2 秒（40 tick）判定一次，以
  min(速度/20, 1) 的概率触发；触发后对车架所在子层级施加 1200 pN 力，方向为车架局部
  `getLateral()`（水平、垂直车架朝向），左右随机，持续 10 tick（0.5 秒，总冲量 600 pN·s；2026-09-24 用户由 12000 改为 1200）。
  作用点为车架方块中心（plot 局部坐标，偏离质心，带出摇摆/侧倾）。计时在服务端 `tick()`，
  速度不大于 4 m/s 即撤销计时、再次超速重新开始固定 2 秒计时；进行中的 10 tick 不因降速中断。
  施力在 `sable$physicsTick` 每物理子步按 `1200 × timeStep` 记入本模组 sable 力分组
  `dimblend_experience:lateral_force`（`QueuedForceGroup.applyAndRecordPointForce`，plot 局部点 +
  局部冲量，螺旋桨/simurail 牵引同型；sable 同子步统一 applyQueuedForces），Simulated 力示意图
  可按该分组（“横向力”，默认显示）绘制；分组注册条件与 mixin 一致（simurail 在场）；
  pN 为 sable 力单位（`BlockEntityPropeller#getThrust` 同单位）。未组装（不在子层级）不计时。
  实现 `PhysicsBogeyLateralForceMixin` + 纯函数 `TrainLateralForceMath`；
  新开关 `trainLateralForce`（默认开，热关闭下一 tick 清空计时与进行中施力）
- E9 转向架红石信号强度上限（2026-10-10 用户口径，2026-10-12 v1.17 调整为上限可调，全维度）：转向架
  收到的红石信号强度不高于上限的原样生效，超过上限的一律压到上限；原行为为 `getControlStrength()D` =
  信号/15 随强度线性（上限 15 即等效关闭、恢复原版）。实现
  `PhysicsBogeyBrakeSignalCapMixin`：`getControlStrength()D` RETURN 处把超过 上限/15 的强度压到
  上限/15、0 保持 0——该方法是信号唯一归一化入口，刹车力度、动力网络应力
  （`calculateStressApplied` 同按 controlMode 三分支：正控 control×stress、反控 (1−control)×stress、
  默认恒 1.0×stress 与信号无关，即默认模式本条目零效果）与 E3/E4 沿检测（读 `getBrakeStrength`）
  自动一致；`getSteerValue` 转向差值直读 `getSignal`，不受影响。
  反控模式二阶后果（接受项）：刹车恒 ≥(15−上限)/15 不归零，E3/E4 施闸/松闸沿不再触发。
  上限 `trainBrakeSignalCap`（1-15 级，默认 1 即 v1.14「视为 1 级」口径；经 /dbx 面板可调，
  旧布尔开关 `trainFixedBrakeSignal` 被上限蕴含、旧键已被自动清除）
- A9 虚空共存（用户拍板）：rotating 内 voidscape band 里 Voidscape `Insanity`
  的生命上限修饰符保留；我方 A3 只读写自有 id
  `dimblend_experience:far_curse_max_health`（代码已确认天然共存），叠加效果留实机验证；
  不做禁用虚空侧的 mixin

## 11. 板块 H：Create 蒸汽引擎过载两阶段（新增）

- H1 第一阶段（过载连续计时 0..319 tick = 16 秒）：引擎运转（`isValid` 贴锅炉 + `getShaft()` 非 null）
  且轴 `isOverStressed()` 时 overloadAge++；每 2 tick 在引擎中心播 `minecraft:cloud`
  （count=1、delta=0、speed=0.05），每 20 tick 播 `steam_overstress`（单声道 1.49 秒，
  1 秒间隔近无缝）。任一条件消失（过载解除/停机/轴缺失）即清零，声音粒子同时停
- H2 第二阶段（满 320 tick 当 tick）：只破仍是 `create:powered_shaft` 的格
  （`AllBlocks.POWERED_SHAFT.has`，`destroyBlock(shaftPos, true)` 按战利品表掉成 `create:shaft`），
  引擎本体保留停转；`steam_exhaust` 播 1 次 + 8 秒（160 tick）排气粒子
  （每 2 tick `minecraft:cloud` count=5、delta=0、speed=0.1）。过载解除不中断排气；
  引擎被拆/区块卸载则 BE 不再 tick，自然终止
- 实现 `SteamEngineOverloadMixin`（`SteamEngineBlockEntity.tick` RETURN）+ 纯函数
  `SteamEngineOverload` / `SteamEngineOverloadMath`；状态全内存态（卸载清零）；
  新开关 `steamEngineOverload`（默认开）；音效 `steam_overstress` / `steam_exhaust`
  单声道入库（`attenuation_distance=32`）

## 6. 横切约定

- 每个功能挂模组 Config 开关（默认开启）
- 所有 mixin 按目标模组存在性条件加载，单独安装本模组不崩
- **C 板块例外说明**：创造模式伪装方块为内容方块，注册期无法受 Config 控制，以 **Copycats+ 存在性**（ModList 守卫）作为加载条件；无配方、无战利品表，生存不可破坏
- 开发顺序：A → C → B → D → E
- 编译依赖对准运行实例 mods 内的实际版本 jar；参考源码 clone 到 `E:\misc` 同级目录
- **版本差注意**：clone 到的 CCA（1.21.1 分支 HEAD=1.7.1）新于运行 jar（1.5.10），且无对应 tag。写 mixin 前必须以运行 jar 的实际字节码为准（必要时反编译核对），clone 源码仅作结构导览；**CDG 例外**——B 板块已整体对齐到最新版 1.3.15（同级源码构建 jar，mixins 按源码口径编写，mods.toml 下限 `[1.3.15,)`），不再以旧运行 jar（1.3.11）字节码为准
- **构建环境**：本机默认 JAVA_HOME 为 JDK 11，每条 gradle 命令需显式 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`
- 音效素材由**用户提供**（用户拍板），我负责接入；素材到位前相关功能用占位实现，代码先行
- **已知接受风险（A1/A2 L4）**：v1.13 起作废——快照捕获、LivingDropsEvent 拦截、重生改道均已删除，原三条风险（快照残留、连带取消、附件序列化）随之消失；现 A1 仅剩经验比率（克隆时覆盖经验字段 + 取消经验球事件），无新增在案风险
- **GameTest 共享配置纪律（2026-10-24 源码校正）**：vanilla `GameTestRunner.runBatch` 在前一批全部结束后才启动下一批，并发只发生在同一 batch 内。取值一致的共享键（维度门、SABLE_STRUCTURE_VOID_FIT、SABLE_VOID_FIT_REFIT_TICKS、SABLE_LAVA_MELT）一律走 `GameTestSableRules` 引用计数覆写（首个 acquire 应用、最后 release 还原、中途不改值），防止同 batch 用例的私有还原互踩；用例中段的临时翻转必须自己翻回（含 finally 兜底）；取值互斥的键（LIMITED_WATER：limited_water 要开、载具用例要关）仍按各类私有保存/还原

### 需用户提供的音效素材（OGG Vorbis，建议单声道 44100Hz）

| 文件名（放入 `src/main/resources/assets/dimblend_experience/sounds/`） | 用途 | 循环性 |
|---|---|---|
| `bogey_track_loop.ogg` | E2 行进声（每秒脉冲；2026-09-24 素材换 `train-running (2)`，1.14 秒立体声） | 单次短音 |
| `train_brake.ogg` | E3 刹车 | 触发式 |
| `brake_release.ogg` | E4 泄气阀 | 触发式 |
| `electric_motor_loop.ogg` | D4 马达运转（须单声道：立体声不做距离衰减；2026-09-24 素材换 `motor_running (1)`，1.32 秒，降混 + 增益 +11dB） | 单次音，运转期间每 5 tick 起一条（忽略时长） |

## 9. 板块 F：Simulated 物理组装器（新增）

- F1 `simulated:physics_assembler`（Simulated 模组，以 jarjar 内嵌于
  `create-aeronautics-bundled-1.21.1-1.3.0.jar`，mod id `simulated`）
  禁止摆放：生存模式禁、创造模式放行；非玩家实体放置一律拦截
- 实现为「放行放置 + 破坏自身返还」（2026-09-23 实测回改，EntityPlaceEvent
  取消方案作废）：事件只登记不取消，本服务端 tick 末尾拆除——真人玩家
  （精确 `ServerPlayer` 类判据；机械手 FakePlayer 继承 ServerPlayer 故落入
  掉落分支）`destroyBlock` 不掉落 + 物品直回背包（无提示）；
  机器/非玩家放置 `destroyBlock` 掉落（loot table 掉自身）。识别用快照
  目标状态 `getBlockSnapshot().getCurrentState()`（`getPlacedBlock()` 对
  非玩家实体返回放置前状态）。拆除延迟到 tick 末是硬约束：事件回调期间
  放置事务尚未收尾——状态虽已写入区块，快照的客户端同步/邻居通知未应用、
  取消回滚路径仍在场，当场拆会与其竞争（旧破坏自身方案的鬼影即源于此）
- 不取消事件的原因：取消仅回滚服务端快照，客户端预测（生存下
  `performUseItemOn` 直接执行 `Item#useOn` 且事后不恢复物品数量）已扣掉
  物品；服务端物品净变化为零不再下发槽位纠正——实测表现为组装器消失
  不返还。放行后两端终态一致，无鬼影无丢失
- 注册表名比对，无编译依赖；新开关 `assemblerGuard`（默认开）
- F2 simulated 便携引擎（16 色 `simulated:<color>_portable_engine`，全算同类；注册表名
  比对，无编译依赖）同一 Create 动力网络最多一台
- “同网”按连通判、与转速无关（2026-09-27 用户新口径：网络不必转起来，零转速静置也照毁）。
  Create 的 `KineticNetwork` 只在转起来后才存在，故不用 network id，改由
  `KineticComponentScan` 按 Create 连接规则逐块广搜：候选位置复用
  `RotationPropagator#getPotentialNeighbourLocations`（invoker），连接判定
  `RotationPropagator.isConnected`；只扫已加载区块、不强制加载
- 分轴静置语义：Create 离合器/顺序齿轮箱无动力源时一律判连通（倍率 1），照搬会误拆
  用通电离合器隔开的引擎。改按运转语义：原版变速箱恒连通、原版离合器通电即隔断、
  其余（顺序齿轮箱/第三方离合类）静置保守视为隔断——转起来有源后按 Create 实际倍率判
  （起转经接入钩子复扫，不会漏成永久绕过）
- 结算（服务端 tick 末，`destroyBlock(true)` 按 loot 掉落、燃料由引擎自身 `onRemove` 掉出、
  无豁免）：① 实体放置（`EntityPlaceEvent`，玩家/机械手）的新引擎所在分量已有他台 →
  新放的自毁（后来者让位；按方块查连通，机械手在 BE tick 内放置也当拍判定）；
  ② 任何动力块（重新）接入（`RotationPropagator.handleAdded` HEAD 只登记维度）→
  tick 末从该维度已加载引擎出发扫分量，N≥2 随机留 1 台其余全毁。②覆盖传动杆/齿轮/
  皮带接通、离合器/变速箱红石切换、蓝图炮/装置解体/指令写入、区块加载（存量违规读档
  即收敛）、引擎起转
- 不轮询（已砍）：只在有接入的 tick 结算，维度内已加载引擎不足 2 台连扫描都跳过；
  开关关闭期间放置登记丢弃、维度变动标记保留，重开后补结算
- 已知接受风险：跨未加载区块的那一截等其区块加载后再判；第三方分轴静置视为隔断；
  维度内 ≥2 台引擎已加载时，每次接入对含引擎的分量整体广搜一次（超大工厂有开销）
- 实现 `PortableEngineExclusivity`（事件+结算）+ `KineticComponentScan`（Create 连通扫描）
  + `PortableEngineAttachMixin` / `RotationPropagatorInvoker`；开关 `portableEngineExclusivity`
  （默认开）；运行时验证 `KineticComponentScanGameTests`（静置传动杆连通、空隙隔断、
  离合器静置/运转两态通断）

## 10. 板块 G：新条目系统（v1.5 起，一律仅 rotating 维度）

### G1 村民大师单次交易
- 村民初次获得职业时收编：转职瞬间打新鲜戳（`setVillagerData`
  HEAD，定级不打、NONE 不打、开关关闭不打），rotating 内扫描只收有戳者；
  自带职业的新生（刷怪蛋 / /summon / 自然 / 结构 / 治愈）由 `FinalizeSpawnEvent`
  在转职前补打戳；新生无业者保持无业，不做随机指派，等自然认领工作站点后收编；
  读档加载不走此事件，存量村民天然无戳、不追溯；
  傻子（nitwit）永不收编、婴儿跳过、无业跳过（获得职业后再收编）
  收编瞬间定为大师（`VillagerData` level 5），并补全该职业 1–5 级全部交易
  （每级照原版取 2 条，新人新口味）；存档老村民天然无戳、不追溯；
  流浪商人不算村民；戳持久化，重启不丢，何时交易都不影响收编
- 每个交易条目全局只能成交一次：收编/重建时全部 offer `maxUses=1`，用完永久锁死；
  已收编者的 `restock()` 由 `VillagerMasterMixin` 直接取消（需求/价格不再浮动），
  无逐条成交记录机制；其余原版行为不动
- 职业方块丢失后不掉职业：凭成交/转职记录恢复职业（或拦截掉职业转职），
  无工作站点的 fresh 大师同样保留；傻子（nitwit）无职业、跳过

### G2 只有 sable 结构上的床可交互
- rotating 内：床方块自身位于 sable 子层级内（放在列车/舰船结构上）→ 右键正常
  （睡/设重生点）；否则右键无反应（不睡、不设重生点、不爆炸）
- 只拦使用不拦破坏（拆床、搬床随意）；创造模式豁免；村民 AI 睡觉不管
- 判定走 sable API 查床坐标归属子层级（实施时按 sable jar 核实方法，
  行为以“结构上的床能用、地上不能用”实测为准）；新开关 `structureBed`（默认开）

#### G2 配套：睡眠只休息、不跳时（2026-10-20）

- 生效于原版主世界 `minecraft:overworld` 及配置的 rotating 维度；rotating 仍保留上述结构床限制，地面床不额外放行。
- 合法、可触及且未被占用/阻挡的床，白天与附近有怪时也可入睡；白天和自然天亮均不强制唤醒。其他维度的床规则不改。
- 睡眠人数达到门槛时——主世界：不跳到早晨、不强制唤醒、不清除天气，世界时钟照常自然流逝，`doDaylightCycle=false` 时仍保持暂停；rotating（2026-10-23 修订）：夜晚/雷雨恢复原版跳夜语义——跳到早晨（经 `RotatingLevelData.setDayTime` 穿透写主世界共享时钟，主世界同步天亮）、强制起床、清天气；白天小睡仍只休息不跳日（`BedRestRules.suppressesTimeSkip`：rotating 仅白天非雷雨时抑制）。跳夜修复配套在 dimblend 模块：`setDayTime` 穿透 + `TimeCommandMixin` 拦 `/time add` 对 rotating 的回声写（防穿透与主世界叠成 +2X；rotating 构造时 `tickTime=false` 本就不自增，共享时钟推进完全来自主世界）。
- 睡眠期间暂停饥饿、饱和度和饥饿消耗累积，以及 Yet Another Thirst 的口渴、解渴储备与消耗；饥饿/口渴降到零也不会在休息中造成饥饿或脱水伤害。
- 入睡后先等待 100 tick（正常速度下 5 秒），期间无回血、无爱心粒子；第 100 tick 给一次 30 秒生命恢复 II（本段睡眠不重复给），血量全交给效果自然结算、不做任何血量检测（2026-10-21 修订：原为每 100 tick 回复半颗心并封顶满血）。替换睡眠中的饱食快速回血与和平难度回血，不要求饥饿/口渴充足。起床后恢复原有消耗，下一次睡眠重新计时，即刻重新入睡也必须重新等待；不改变其他伤害/状态效果。
- 等待完成后每 20 tick 在床尾上方的真实世界位置广播 3 个爱心粒子，频率与血量无关；起床/死亡立即停止（2026-10-21 修订：粒子中心由玩家头部移至床尾，第一人称可见；原为受伤每秒 3 个、满血每 5 秒 1 个）。床尾中心经 Sable 世界位置投影封装，车辆上的床（plot 本地坐标、随载具旋转）也落到真实世界位置。
- 在这两个休息维度通过现有 `RegisterGuiLayersEvent` 包装 `VanillaGuiLayers.SLEEP_OVERLAY`，省去原版睡眠及短暂起床退场的变暗遮罩；其他维度沿用原版，停止睡眠按钮和聊天保持可用。不改写原版睡眠计时器。
- 通过 NeoForge 的入睡/继续睡眠事件和 YAT 自有 `suspendsThirst` 扩展点接入；不修改时间/睡眠人数游戏规则，不增加持久化状态。
- 验证：单测与 8 项睡眠 GameTest 通过，覆盖 5 秒等待、爱心数据包数量/频率、满血持续、起床即刻重入及原有饥渴/时间规则。实际客户端的 GUI 注册和睡眠遮罩回调在 rotating 世界上下文下验证为不调用变暗层；最终睡眠画面及真实载具床路径待玩家回归。2026-10-23 rotating 跳夜恢复：`BedRestRulesTest` 4 条锁跳夜抑制口径（主世界永不跳、rotating 白天小睡不跳/夜晚雷雨跳、其它维度不动）、`RotatingLevelDataTest` 锁写穿透（含「原版 DerivedLevelData 空实现」对照）；主世界 GameTest 语义不变仍绿；rotating 实机跳夜待玩家回归。

### G3 有限水（2026-09-30 口径，仅 rotating）
> 2026-09-29 旧口径（孤立源水降级 + 冰破坏产水 bypass + 写入归因）已于 2026-09-30 整体删除，
> 按下述新口径重做。

放冰：
- 生存模式玩家不能放置冰/浮冰/蓝冰；其它模式玩家可以
- Create 机械手（Deployer）不能放置这三种冰，与附近玩家模式无关；其它机器/假玩家不拦
- 拦截点 `BlockItem#place` 入口（`IcePlacementBanMixin` → `IcePlacementRules`）：
  机械手点空气时不发 `RightClickBlock`，事件层拦不全，故在放置层判定；双端执行防幽灵方块

源水检测：
- 每次在 rotating 写入源水（water8 = `Blocks.WATER` 且 `LEVEL=0`，经 `Level#setBlock`），
  无论来源（玩家倒桶、玩家破冰、管道、发射器、机械手、冰融化、水流成池），
  由以该格中心为球心 8 格内（三维直线距离）最近的玩家决定；旁观者不计入；
  Sable 载具上的玩家与方块按真实世界坐标计距
- 该玩家为创造模式：不检测，源水原样写入
- 其余情况（生存/冒险，或 8 格内无人）：数水平四邻（x±1、z±1）中源水或冰/浮冰/蓝冰的格数，
  ≥2 保留源水，0–1 改写为流动 water7（`LEVEL=1`，amount 7 非下落）
- 流动水、含水方块、霜冰不计入四邻；已存在的水与世界生成（`WorldGenRegion`）不动，只拦新写入
- 破冰贴原版：普通冰无精准采集破坏留水（该水同样走检测），浮冰/蓝冰不留水
- 实现：`LimitedWaterMixin`（`@ModifyVariable` 改写 `Level#setBlock` 的 state 参数，不取消不重入）
  → `LimitedWaterRules` / `LimitedWaterMath`
- 开关 `limitedWater`（默认开）+ `icePlacementBan`（默认开）

### G3 配套一：载具结构空位拟合（2026-10-10 实施）
- rotating 内给 Sable 载具的世界投影体积拟合 `structure_void`（原版
  `FlowingFluid#canHoldFluid` 硬编码排除空位），行驶载具内部不进水；
  拟合格区块保存时剥成空气再还原（不落盘）；水密要求保守覆盖取格，
  凸出载具可见几何不可避免（格粒度表达不了亚格表面）
- 凸出无害化（2026-10-11 拍板方案三）：`StructureVoidBlockPassthroughMixin`
  在配置+开关+rotating 门控下把 `StructureVoidBlock#getShape` 掏空——交互射线
  （`ClipContext.Block.OUTLINE`）全穿透，挖/打/右键/桶 POV 不再被隐形空位拦截；
  挡水、保存剥离、调度器均不动，维度外保留原版可选中语义
- 区块加载补放不走事件内联写入（2026-10-11 死锁修复，见
  `docs/sable-void-fit-chunk-deadlock.md`）：`ChunkEvent.Load` 只登记 ChunkPos，
  tick 扫描按 2 区块/轮 + `CELL_BUDGET` 预算消化，放不完回队尾
- 空 target 重试（2026-10-11）：refit 后 target 为空（plot 懒加载未就位）则留
  retry 下轮续试（静置载具不再触发位姿 refit，否则最长等一个自愈周期才拟合）；
  连续 20 轮仍空（限高外/病态）熔断退回自愈节拍，防挤占预算
- 实现：`SableVoidFit`（4Hz 扫描、2 载具/轮预算、位姿阈值、20 周期自愈）
  → `VoidFitVoxelizer`/`VoidFitTracker`/`VoidFitApplier`/`VoidFitStrip`
  + `ChunkMapVoidFitStripMixin`；GameTest 8 条。投影 AABB 仅 Y 受世界高度钳制，
  X/Z 不做 Y 上限裁剪（2026-10-24 修复远正 Z>319 时 z0>z1 导致整列 target 为空；
  `VoidFitVoxelizerTest` 锁远 Z 覆盖与 Y 钳制）
- 开关 `sableStructureVoidFit`（默认开）+ `sableVoidFitRefitTicks`（默认 5）

### G6 岩浆熔毁 sable 结构方块（2026-10-11 拍板口径，仅 rotating）
- 岩浆与岩浆流按 **sableLavaMeltSpeed 倍徒手速度**（默认 4 倍；2026-10-14 起经
  /dbx 调参面板与配置可调，0.5-64.0 步进 0.1，逐次扫描读值即时生效、已累进度
  不清零）熔毁位于 sable 结构上的方块并使其掉落：
  熔岩格与方块投影体积（旋转保守半 extent）AABB 距离 ≤0.25 即"被岩浆浇着"
  ——同格（载具驶入熔岩）与贴壳（熔岩流到空位壳边）都覆盖
- 硬度只决定速度不设阈值：熔毁耗时 = 徒手耗时/倍率（需正确工具的方块徒手术
  divisor=100、否则 30，同原版 `getDigSpeed` 分支）——黑曜石级（50）默认倍率下
  约 62.5 秒持续浇淋才熔（等效抗岩浆），硬度负（基岩等）免疫，硬度 0 即熔；
  接触中断进度清零（与徒手停手同型）
- 免疫清单（2026-10-14 增补）：`#dimblend_experience:lava_melt_immune` 内的方块
  一律不熔（不进接触、不累计、不掉落）——标签继承 `#create:non_breakable`
  （打包标为机械不可破坏的车辆关键部件：simurail 转向架/自动车钩）；这些部件靠
  Sable 架构（生存攻击载具方块只产生拳击冲量、不路由破坏）与 Create 清单等硬度
  以外的口径防破坏，熔毁直改 plot 存储会绕过，故显式豁免同一张清单
- 掉落：方块自身掉落规则 `Block#getDrops`（无工具/时运/精准），在投影世界位置
  生成掉落物并播 2001 破坏粒子+音效；容器内容物并入掉落并清空（防撒到 plot 存储区）
- 浇淋音效：熔毁进行中（本轮未熔、进度仍在累计）的方块每轮扫描在其世界侧投影位置
  播一次 `block.fire.extinguish`（原版灭火口径：音量 0.5、音高 2.6±0.4 随机，同
  `BucketItem` 灭火）——持续浇淋即持续嘶声，熔穿瞬间由破坏粒子/音效收尾；播世界
  坐标（播在 plot 存储格玩家听不见，`PhysicsBogeyBrakeSoundMixin` 事故口径）
- 与拟合的配合（2026-10-26 安全柱顶 + 封口格）：开关开启时 `VoidFitApplier#placeVoid`
  只保留"安全柱顶"熔岩——该格对每个 target 它的载具都是柱顶、正下方同柱下一格仍在
  target 内（实体投影，或 `VoidFitColumnTops#sealCellsFor` 给下方悬空柱顶补的封口格），
  且正下方世界格此刻是 structure_void 或 blocksMotion 方块。体素化只收非空气方块
  （`VoidFitVoxelizer#forEachProjectedBlock` 跳过 air），舱内空气不在投影里，所以
  缩放载具下的单格柱（1 格厚屋顶/甲板，下方是舱内空气；scale=1 时自身下层覆盖即
  充当屏障，见下条形状事实核对）和悬空柱顶（间隙/外挑下沿）必须先补封口格；否则
  柱顶保留的熔岩没有下方屏障，会从完整船壳漏下去（2026-10-26 修的缺口）。
  安全论证：柱顶下方是（或即将是）structure_void，原版 `FlowingFluid#canHoldFluid`
  （FlowingFluid.java:394-412）在 :408 显式排除 structure_void，熔岩无法下行；下方
  target 一旦离开（方块熔掉/投影收缩），保留格每轮 refit 重新判定（不入 materialized）
  并驱逐。多载具取交集：任一 target 该格的载具把它当中层格/悬空柱顶都否决，避免保留格
  落进另一载具的投影。柱内更低投影格照常驱逐成空位（舱内不积岩浆、不导流）；熔穿后
  投影收缩、空位与封口格拆除，熔岩向缺口推进、逐层向下。舱内空气不在投影内，熔穿层
  下方若是空腔，熔岩经熔出的洞进入空腔（原版流体行为），仍被下一层投影挡住；
  关闭后熔岩与水同被驱逐、本特性停摆
- 浇淋接触（2026-10-26 补）：`SableLavaMelt#touchesLava` 在 AABB+0.25 之外，把保守
  覆盖最高格的再上一格纳入接触——拟合格是保守覆盖，熔岩停在柱顶空位上时可能比方块
  AABB 高出近一格（按亚格偏移可达 1 格），只按 AABB+0.25 会漏判"浇在车顶上"的熔岩，
  车顶就永远等不到第一次接触；只向上多收一格，侧向/下方仍 0.25 口径
- 开关耦合（2026-10-26 登记，不改行为）：`sableStructureVoidFit` 关闭时没有拟合格，
  `sableLavaMelt` 仍照常扫描/熔毁（它只读 sableLavaMelt）——熔岩不会被挡，直接流进/
  流过载具并按接触熔毁
- 形状事实核对（2026-10-26，审计口径修正）：`VoidFitVoxelizer` 只收非空气方块，
  舱内空气不是 target；保守覆盖半 extent = 0.5×pose scale，scale≥1 时每轴
  `floor(w+h)-floor(w-h) ≥ 2` 恒成立——**scale=1 的载具每个方块每轴至少覆盖 2 格，
  柱顶正下方必是同一方块的另一覆盖格**，单格柱/悬空柱顶不出现（审计"1 方块=1 格"
  的推演与实际保守覆盖不符）。缺口只在这些情形出现：缩放子层级（scale<1 且亚格偏移
  使覆盖退化成 1 格）、世界限高夹取、或正下方是未物化的非流体方块（世界侧检查兜底
  驱逐）。因此封口格是防御性/缩放载具修复；1 格厚空心车顶/外挑甲板在 scale=1 下
  完整期间仍被自身下层覆盖挡住，只有熔穿后熔岩才经熔出的洞进入舱内/甲板下（原版
  流体行为），被下一层投影挡住；开顶货车的车厢本来就不封闭（空气与外界连通、不进
  投影），熔岩会灌进车斗，但车斗地板在完整时仍挡在下方
- 实现：`SableLavaMelt`（5 tick 一轮、2 载具/轮游标轮询；连续接触按真实 tick 差
  累计、新接触从 0 起算不预支空窗；开关停摆恢复后首扫重置计时不补发停摆期；
  倍率逐次扫描读 `Config.SABLE_LAVA_MELT_SPEED`，运行中改值即时生效）
  → `LavaMeltMath` 纯函数（速率按倍率参数化，两参重载走默认值）；`VoidFitVoxelizer#forEachProjectedBlock` 与体素化共用
  投影枚举；plot 读写一律 `level` + 全局存储坐标（`EmbeddedPlotLevelAccessor`
  是中心相对坐标，严禁混用）；免疫守卫在扫描枚举与熔毁执行两处各查一次
  `state.is(LAVA_MELT_IMMUNE)`（执行处复核防枚举后邻接更新改写）；柱顶索引
  `VoidFitColumnTops` 纯函数（柱顶/安全柱顶/封口格；单测 11 条）随 target 同步重建
  存于 `VoidFitTracker.VehicleFit`；refit 分两轮先物化非柱顶（含封口格）再判柱顶
  保留，补放路径同样两轮、多载具取交集；G6 相关单测 19 条（`LavaMeltMath` 6 +
  免疫标签 2 + 柱顶 11）+ GameTest 13 条（原 6 条 + 屋顶阻断 7 条：空心车厢/外挑甲板/
  逐层下降/单格柱/间隙/无碰撞封口回退/缩放 refit；GameTestSableRules 把倍率钉为
  默认值，套件时序与服务器配置无关）
- 开关 `sableLavaMelt`（默认开）+ 倍率 `sableLavaMeltSpeed`（默认 4.0，/dbx 面板
  可调，快照协议随新增选项升版、当前版本以 parameter-tuning 为准）；需要 Sable 在场

### G4 传送门禁令（回家通道不管）
- 下界门：rotating 内点火生成一律取消（`BlockEvent.PortalSpawnEvent` 取消，
  镜框搭好也点不着）
- 传送功能：rotating 出发的跨维度旅行凡目的地在禁令清单一律取消
  （`EntityTravelToDimensionEvent`，只看目的地、不看坐什么门）；
  清单 = 下界 + 末地（含主岛/外岛）+ 折跃门目标 + 已知 mod 维度（dimblend、
  虚空类，实施时按注册表枚举，硬编码+注释可增补）
- 折跃门方块保留（可摆可拆），传送不生效；失败静默（无提示）；
  折跃功能经 `WarpGateBlockMixin`（dimblend 字符串目标）在任意维度直接掐断
  （跨维度功能无视 rotating 作用域，用户可 veto 回 rotating 内）。
  字节码事实（dimblend 0.1.1）：`entityInside` 本体为 no-op、`portalTick` 为原版
  冷却逻辑，全 jar 无跨维度传送调用——0.1.1 本来就传不了，本取消为面向未来的保险；
  若未来走 `changeDimension` 路径，rotating 出发的由旅行禁令兜底；
  指令/死亡等非门手段不动；新开关 `portalBan`（默认开）

### G5 末影存储只能放在 sable 结构上（v1.10）
- rotating 内：`enderstorage:ender_chest` / `enderstorage:ender_tank`（EnderStorage
  2.13.0.191）放置坐标位于 sable 子层级内（贴列车/舰船结构放置）→ 正常留块；
  否则不留块。原版 `minecraft:ender_chest` 不在此列；其他维度不管
- 创造模式豁免；机器/非玩家实体放置同样拦截
- 拦截方式沿用 F1「放行放置 + 本服务端 tick 末破坏返还」（不取消 `EntityPlaceEvent`，
  理由同 F1）：真人玩家（精确 `ServerPlayer` 类判据）`destroyBlock` 不掉落、掉落物
  直回背包（无提示；同 tick 内已下线则改原地掉落）；机器/非玩家放置 `destroyBlock`
  掉落。返还走方块自身掉落规则 `Block#getDrops`（拆除前按 BE 计算）而非
  `new ItemStack(block)`：`BlockEnderStorage#getDrops` 读 BE 频率写回物品，
  私有频率另返还钥匙物品，与玩家挖掉该方块所得一致；箱/罐内容存于频率全局存储，
  拆除不丢内容
- 结构判定与 G2 同口径（`Sable.HELPER.getContaining` 查放置坐标）；识别用快照目标
  状态 + 注册表名比对，无编译依赖；enderstorage 缺席无目标、sable 缺席 fail-open 放行
- 已知接受风险：只拦 `EntityPlaceEvent` 路径，Create 装置解体、蓝图炮等直接
  `setBlock` 写入不触发事件、不拦
- 实现 `EnderStorageStructureGuard` + 纯函数 `EnderStorageBlockIds`；
  新开关 `enderStorageStructureOnly`（默认开）

### G7 产蛋器内嵌鸡在 Sable 子层级不显示（2026-10-15，纯客户端修复，v6 按渲染层拆分模型，**2026-10-15 实机验收通过**）

> 前三轮「谓词翻转 + BER 兜底 + 单块重排」方案实机均未通过（BER 从未被分发），
> 已整套删除。复盘与探针证据见 `docs/createeggproduction-egg-collector-chicken-handover.md`。

- 症状：Create:Egg Production（createeggproduction 1.1.0）的产蛋器方块
  （`createeggproduction:egg_collector_block`）放在 sable 子层级内时，玻璃罩里的
  内嵌鸡不显示；孵化器正常（其模型无半透明玻璃罩、整块不透明，不受本问题影响）
- 根因：鸡几何直接烤在方块模型里（`block/texture4` 贴图的 `chi` 组），整个方块被
  mod 注册为 translucent 渲染层；translucent 画会写深度，玻璃罩组在模型里排在鸡组
  **前面**，Sable 的子层级渲染不按摄像机距离对 translucent quad 排序（原版区块
  渲染有 per-section 半透明排序），玻璃先写深度 → 里面的鸡全被深度测试剔除
- 修法：`EggCollectorModelInstaller`（`ModelEvent.ModifyBakingResult`，客户端 MOD 总线，
  `ModList.isLoaded("createeggproduction")` 门控、只按注册名匹配不引用该 mod 类）把
  `egg_collector_block` 全部方块状态变体（`inventory` 物品变体不动）换成
  `EggCollectorLayerSplitModel`：`getRenderTypes` 返回 `{cutout, translucent}`，
  `getQuads(..., cutout)` 只给鸡组 quad（贴图名 `createeggproduction:block/texture4`），
  `getQuads(..., translucent)` 给其余（底座/玻璃/草），`renderType == null` 原样全给
- 为何覆盖全部路径（字节码核实）：主世界 Sodium、Sable 单块（`SableSubLevelRenderPlatformImpl.getRenderLayers`
  → `model.getRenderTypes`，`tesselateWithoutAO(..., renderType)`）、Sable 区块化
  （`VanillaChunkedSubLevelRenderData.compileSections` → 原版 `SectionRenderDispatcher.rebuildSectionSync`
  → NeoForge 补丁 `SectionCompiler` 逐层 `getRenderTypes`/`renderBatched`）均按渲染层取 quad；
  Sable 在 Sodium 下经 `SodiumWorldRendererMixin` 挂 `drawChunkLayer(RenderType)`，随原版逐层
  调用，cutout 层先于 translucent 层 → 鸡先写深度、玻璃后混合，不依赖逐 quad 排序
- 鸡贴图 `texture4` alpha 只有 0/255（已核对 png），cutout 截断与原半透明混合观感一致；
  主世界行为不变
- 不需要 mixin、不需要 compileOnly 依赖；拆分结果按原模型返回列表引用缓存（不可变快照，
  区块编译多线程安全）
- 无配置开关（纯客户端视觉修复，非 gameplay）

### G8 车灯紫水晶激活（2026-10-20）

- DnDecor 所有前灯默认不产生自定义聚光效果；原有红石供电、亮灭和装饰状态照常运行。
- 生存/创造玩家手持紫水晶碎片右键当前车灯，激活聚光功能；2026-10-22 修订：**生存激活消耗 1 个碎片**（创造按惯例不消耗），拆除车灯不返还碎片。支持副手和潜行右键。冒险/旁观不放行。
- 仅未激活→激活时在车灯的真实世界位置播放一次 `block.end_portal_frame.fill` 音效和一阵红色红石粉粒子。再次右键不反转状态、不重播反馈。
- 激活后的灯在地面和 Sable 结构上均可投射，仍须灯自身 `lit=true`，并服从现有聚光开关与参数。
- 状态属于当前方块：`dimblend_spotlight` 默认为 false，随区块保存及 Sable 组装保留；旧存档未携带此属性时默认关闭。破坏掉落、扳手回收均不复制到物品，重新放置需要再次激活。
- 渲染仍要求 Sable/Veil 在场且未安装 Iris；本次激活机制不改变既有 Iris 兼容限制。客户端和服务端必须同步更新。
- 验证：169 项单测及 5 项实际 DnDecor/Sable GameTest 通过。可用 `:dimblend-experience:runGameTestServer -PwithHeadlights -PheadlightDecorJar=<DnDecor jar 路径>` 重跑；地面聚光已由玩家确认激活后正常，载具视觉回归待完成。

### G9 Linked Transmitter 交互修复与诊断（2026-10-24）

- 根因：linked 拉杆/按钮继承 `noCollission`，Create `LinkHandler` 的 `COLLIDER` 频率射线会穿过 linked 命中支撑块；Connected 的 `ItemUseOverrides` 又用格心伪命中并独立重射线。Sable 双端位姿差使这些独立命中在频率边界分流，客户端预测放置扣物而服务端实际只设置频率，形成假消失。
- 修复：真人 linked 交互统一使用右键事件附带的精确命中，按 `ServerboundUseItemOnPacket` 的相对 float 坐标格式量化后交给 `LinkHandler` 和 `BlockHelper.invokeUse`，不再各自重射线。`ItemUseOverrides` 保留扳手排除、提前交互、蜜脾/斧、蜡锁及潜行底座 `CONSUME`，不增加实体碰撞、不改第三方注册表。
- 边界：`FakePlayer`/Deployer 保留原路径，未经专门回归不承诺其最近槽回退已改变；蹲姿问题本轮只加运行时取证，不直接改 Sable `lastPose`，不把发射器附件说成实体碰撞源。
- 诊断：CLIENT 配置 `linkedTransmitterDiagnostics`（默认 false），`/dbx linkeddiag`（权限 2 级）可切换；无权限玩家由管理员或配置文件开启。日志前缀 `[linked-diag]`，记录双端命中、频率判定、物品/Shift/实际蹲姿/三种姿态适配/AABB，以及 Sable 的 `lastPose`/`logicalPose`/客户端 `renderPose`。dedicated server 的客户端与服务端需分别开启，不能用客户端开关代替服务端日志。
- 自动验证：`LinkedTransmitterInteractionTest` 量化单测；`LinkedTransmitterGameTests` 覆盖两个频率槽、底座提前交互、蜜脾/斧/蜡锁、潜行 `CONSUME`、FakePlayer 对照。重跑：`JAVA_HOME='C:\Program Files\Java\jdk-21' ./gradlew :dimblend-experience:test` 与 `./gradlew :dimblend-experience:runGameTestServer -PwithLinkedTransmitter`。

### G10 车灯照亮岩浆高亮（2026-10-26，纯客户端）

- 需求：被前照灯光锥实际照到的岩浆顶面高亮；用户选定样式为「发光橙色描边、只描连接区域的顶面外缘、做深度测试，墙体/地形/列车能遮挡」。
- 实现：`client.headlight` 的 `HeadlightLavaHighlight`（10 Hz 重算调度 + 描边绘制）、`HeadlightLavaScan`（世界扫描 + 遮挡射线）、`HeadlightLavaMath`（纯函数：照亮判定、周长边、上限、脉动）、`LavaSurface`/`LavaEdge`。无新增 mixin。
- 照亮判定：流体为岩浆（源/流动均可）、顶面暴露（上方非同流体且非实心整方块）、顶面中心在光锥内且在射程内、`cone × attenuation > 0.04`；遮挡开关打开时再用与阴影网格同一套 occluder 判据做方块 DDA 射线（`HeadlightOccluders` + `HeadlightOcclusionMath.trace`，跳过灯所在格）。默认参数（射程 24/全角 50/强度 1）下轴向照亮到 22.1 格，软肩使离轴照亮宽度为锥半径的 84–91%。
- 描边：仅画「水平邻居不是同高度照亮岩浆」的顶面边（连通岩浆海只出一圈轮廓，台阶两侧各画一条）；高度 = 方块 y + 流体自身高度（源岩浆 8/9）+ 0.01；颜色 0xFFB040，alpha 以 1 Hz 在 0.35–0.75 间脉动。
- 绘制：`RenderType.lines()` 在 `AFTER_TRANSLUCENT_BLOCKS`（岩浆顶面属 solid 层、实体在其前绘制，故 LEQUAL 深度测试即被墙/地形/列车遮挡；半透明块已写深度，水/玻璃同样遮挡）。Fabulous 下该渲染类型的 `ITEM_ENTITY_TARGET` 输出交给 transparency 后处理链按最终主深度排序合成，遮挡语义不变；不额外改动渲染状态。
- 节流：客户端 tick，最快每 2 tick（10 Hz）且仅在光锥位姿变化时重算（灯位量化 0.5 格、朝向量化 1/64），静止灯每 20 tick 兜底刷新；单次最多 1024 个照亮方块（按相机距离保留最近）与 65536 次 section 体素读取（离灯最近的 section 优先；超出射程或调色板无岩浆的 section 直接跳过、一次体素都不读）。默认单灯估计 0.1–0.3 ms；`-Ddimblend.headlight.diag=true` 可实测（日志前缀 `[headlight-lava]`）。
- 开关：`sableHeadlightLavaHighlight`（默认 true）在 Sable 面板、与前照灯参数同属 experience 的服务端配置并经快照下发；仍要求 Sable/Veil 在场且未安装 Iris（与渲染器同一注册条件），并随前照灯总开关与强度为零关闭。
- 验证：`HeadlightLavaMathTest` 覆盖锥体/射程/阈值边界、周长去重（同高/台阶/对角/2×2）、上限就近取整与乱序稳定、1 Hz 脉动；渲染观感（移动列车跟随、Fabulous、水/玻璃遮挡）待实机验收。

## 8. 实现覆盖对账（实现完毕时点）

### Config 开关清单（server 端 `dimblend_experience-server.toml`）

| 开关 | 板块 | 默认 |
|---|---|---|
| deathExpClearRatio | A1（v1.13 重写：重生清经验比率 0.00–1.00，无视 keepInventory，经验球不掉落） | 1.0 |
| depthCurse | A3/A4 | true |
| curseBossbar | A5 | true |
| safeZone | A6 | true |
| globalBeacon | A7（全局功能） | true |
| nickname / nicknamePermission | A8（全局功能）/ 命令权限等级 | true / 0 |
| lootMapFilter | A10（全局功能：一切战利品地图最终换 1 张纸；表加载替换 + exploration_map 短路 + GLM 最终网三层） | true |
| treasureShellX | A11（全局功能：藏宝箱上方沙滩摆贝壳 X 标记，需要 dimblend-blocks 在场） | true |
| dieselEngineBehavior | B | true |
| dieselLoadGraceSeconds | B6 读档宽限秒数（开服后这段时间内柴油机不判爆，0=关闭） | 5 |
| dieselOverloadProbe | B 诊断探针（只读，输出 `[CDG-PROBE]` 日志，定位完毕可关） | true |
| dieselEngineFuelTankMb | B10（CDG 小号/中号柴油机燃料容量 mB，1000 恢复 CDG 原生；大号原生 100 不受影响） | 100 |
| electricMotorBehavior | D（含 D4 自定义音效替代 + D6 护目镜实际转速显示） | true |
| alternatorIdleDrain | D5（交流发电机无输入自放电） | true |
| alternatorOverstressLatch | D8（交流发电机过载解列锁存） | true |
| simurailProtect | E1 | true |
| couplerRedstone | E5（true=红石信号不再断开车钩） | true |
| assemblerGuard | F1 | true |
| portableEngineExclusivity | F2（便携引擎整网唯一，零转速也算同网） | true |
| trainSounds | E2/E3/E4 | true |
| wideGaugeParticles | E6（纯客户端视觉） | true |
| offStructureTeleport | （v1.18 作废：E7 离结构传送已重做，键自动清除） | — |
| trainDeadmanBrake | E7'（无人值守编组刹车锁存，仅 rotating；关闭即解除全部锁存） | true |
| trainDeadmanRadius | E7' 配套（值守半径 8-128 格，经 /dbx 面板可调） | 32 |
| trainLateralForce | E8（车架随机横向力，全维度） | true |
| trainBrakeSignalCap | E9（转向架红石信号强度上限，全维度，经 /dbx 面板可调） | 1 |
| villagerMaster | G1 | true |
| structureBed | G2 | true |
| limitedWater | G3（源水检测） | true |
| icePlacementBan | G3（生存玩家与机械手禁放冰/浮冰/蓝冰） | true |
| sableStructureVoidFit | G3 配套（载具结构空位拟合挡水） | true |
| sableVoidFitRefitTicks | G3 配套（拟合扫描周期 1–40 tick） | 5 |
| portalBan | G4 | true |
| enderStorageStructureOnly | G5（末影存储仅限 sable 结构放置） | true |
| sableLavaMelt | G6（岩浆按 sableLavaMeltSpeed 倍率（默认 4 倍徒手速）熔毁 sable 结构方块并掉落） | true |
| sableLavaMeltSpeed | G6 配套（熔毁速度倍率 0.5–64.0 步进 0.1，运行中即时生效；经 /dbx 面板可调） | 4.0 |
| steamEngineOverload | H（蒸汽引擎过载两阶段） | true |
| trainHorn{Steam,Diesel,Elec}Pitch{Small,Medium,Large} | L（列车汽笛 9 键：音效×汽笛尺寸各自 pitch，0.5–2.0，服务端权威随包下发；无功能开关；经 /dbx 面板可调） | 小 1.5 / 中 1.0 / 大 0.5（小笛高音、大笛低音） |
| itemDrainIrrigation | N1（分液池保湿：水箱有水即票据维持周围同层耕地湿润，免费、无最低水量门槛） | true |
| itemDrainCoverageRadius | N1/N2 配套（保湿/催熟半径 1–8，默认 3=7x7；由旧键 itemDrainIrrigationRadius 改名，旧值自动清除） | 3 |
| itemDrainGrowth | N2（分液池催熟：每周期随机挑一株作物型骨粉目标走原版骨粉逻辑，生效才扣水；无候选倒计时停走） | true |
| itemDrainGrowthIntervalSeconds | N2 配套（催熟周期秒 1–600；由旧键 itemDrainGrowthIntervalTicks 改名） | 30 |
| itemDrainGrowthCostMb | N2 配套（每次催熟生效扣除水量 mb，0=免费催熟；经 /dbx 面板可调） | 20 |
| itemDrainGrowthMinMb | N2 配套（存水低于此值停催熟，保湿不受影响，回水自动恢复） | 200 |
| itemDrainPipeRefill | N3（分液池管道补水：放开 forbidInsertion + 追加 UP 面流体接口，关闭恢复原版） | true |
| itemDrainNetherEvaporation | N6（下界群系分液池蒸干存水，只蒸水；Sable 载具按世界位置判群系） | true |
| itemDrainNetherEvaporationMbPerSecond | N6 配套（mB/秒，0–1500，默认 50；每 10 tick 结算，余数跨拍累加） | 50 |
| rotatingDimensionId | A1–A6 作用域维度 id | "dimblend:rotating" |

### CLIENT 诊断配置（`dimblend_experience-client.toml`）

| 开关 | 用途 | 默认 |
|---|---|---|
| linkedTransmitterDiagnostics | G9 linked 交互与蹲姿双端取证；`/dbx linkeddiag` 也可切换，dedicated 双端分别开启 | false |

### Mixin 清单（`dimblend_experience.mixins.json`，plugin 按模组存在性过滤）

| mixin | 目标 | 板块 | 条件（DimBlendMixinPlugin） |
|---|---|---|---|
| BeaconBlockEntityMixin | 原版信标 BE | A7 | 无条件（原版目标） |
| compat.cdg.DieselEngineRampMixin | CDG 普通/组合柴油机 BE | B | createdieselgenerators 在场 |
| compat.cdg.CdgEngineTankCapacityMixin | CDG 普通柴油机 BE（addBehaviours 油箱容量 1000 常量 @ModifyConstant） | B10 | createdieselgenerators 在场 |
| compat.cdg.CdgModularEngineTankCapacityMixin | CDG 组合柴油机 BE（构造器 + read 两处油箱容量 1000 常量 @ModifyConstant） | B10 | createdieselgenerators 在场 |
| compat.cdg.EngineThrottleLockMixin | CDG IEngine 接口（throttle 恒 1、enabled 保留有效燃油检查） | B11 | createdieselgenerators 在场 |
| compat.cdg.CdgEngineGoggleFuelHintMixin | CDG 三类柴油机 BE（包装 boolean tooltip，空箱追加两行） | B12 | createdieselgenerators 在场 |
| compat.cdg.HugeDieselEngineMixin | CDG 巨型柴油机 BE | B5 | createdieselgenerators 在场 |
| compat.cca.ElectricMotorMixin | CCA 电动马达 BE | D | createaddition 在场 |
| compat.cca.ElectricMotorSoundClientMixin | CCA 电动马达 BE | D4 | createaddition 在场（client 数组） |
| compat.cca.AlternatorIdleDrainMixin | CCA 交流发电机 BE | D5 | createaddition 在场 |
| compat.cca.AlternatorOverstressLatchMixin | CCA 交流发电机 BE | D8 | createaddition 在场 |
| compat.create.AlternatorLatchSyncMixin | Create KineticBlockEntity（write/read TAIL） | D8 同步 | createaddition 在场（handler 接口 instanceof 限定发电机） |
| compat.cca.ElectricMotorGoggleMixin | CCA 电动马达 BE | D6 能耗行 | createaddition 在场（client 数组） |
| compat.create.ElectricMotorGeneratorStatsMixin | Create GeneratingKineticBlockEntity | D6 应力量行 | createaddition 在场（client 数组；handler instanceof 限定马达） |
| compat.connected.LinkHandlerEventHitMixin | Create LinkHandler（频率处理复用按右键包精度量化的事件命中，不再独立 COLLIDER 重射线） | G9 | create_connected 在场 |
| compat.connected.LinkedItemUseOverridesMixin | Create ItemUseOverrides（linked 提前交互改用量化事件命中，跳过格心伪命中及 Connected 二次射线） | G9 | create_connected 在场 |
| PlayerStandingFitInvoker | 原版 Player（只读调用当前实际站立/蹲下/游泳适配，含 Sable 补丁效果） | G9 诊断 | 无条件（原版目标） |
| compat.create.SteamEngineOverloadMixin | Create 蒸汽引擎 BE（tick RETURN） | H | create 在场 |
| compat.create.WhistleHornBlockMixin | Create 蒸汽笛方块（neighborChanged TAIL 双边沿识别） | L | create 在场 |
| compat.create.WhistleHornBlockEntityMixin | Create 蒸汽笛 BE（鸣笛锁定纯内存字段，经 TrainHornStateAccess 访问） | L | create 在场 |
| compat.create.WhistleHornClientMixin | Create 蒸汽笛 BE（tick 张嘴驱动 + tickAudio HEAD 特殊模式音频接管/音符粒子） | L | create 在场（client 数组） |
| compat.create.ItemDrainIrrigationMixin | Create 分液池 BE（tick HEAD 10 tick 降拍保湿驱动 + invalidate TAIL 摘票） | N1 | create 在场 |
| compat.create.ItemDrainGrowthBoostMixin | Create 分液池 BE（tick HEAD 催熟驱动 + invalidate TAIL 复位倒计时） | N2 | create 在场 |
| compat.create.ItemDrainPipeRefillMixin | Create 分液池 BE（tick HEAD 注水门逐 tick 双向置位自愈） | N3 | create 在场 |
| compat.create.ItemDrainNetherEvaporationMixin | Create 分液池 BE（tick HEAD 10 tick 降拍下界蒸发 + invalidate TAIL 清余数） | N6 | create 在场 |
| compat.create.ItemDrainHudSyncMixin | Create 分液池 BE（write/read TAIL：下次击发绝对 gameTime 仅 clientPacket 捎带，不污染存档） | N4 | create 在场 |
| compat.create.ItemDrainGoggleMixin | Create 分液池 BE（addToGoggleTooltip RETURN 追加灌溉/催熟 HUD 行） | N4 | create 在场（client 数组） |
| compat.yat.WaterBottleDrainPurityMixin | Create 分液池 BE（continueProcessing 内 GenericItemEmptying.emptyItem WrapOperation，水瓶倒空保 YAT 纯度组件） | N5 | create + yet_another_thirst 双在场 |
| compat.create.PortableEngineAttachMixin | Create RotationPropagator（`handleAdded` HEAD，只登记维度/引擎） | F2 | create 在场 |
| compat.create.RotationPropagatorInvoker | Create RotationPropagator（`getPotentialNeighbourLocations` 静态 invoker） | F2 | create 在场 |
| compat.create.KineticUnloadedShareMixin | Create KineticBlockEntity（`read` RETURN 快照存档份额；`initialize`/`setNetwork`/`remove` HEAD 结算） | B4 读档账本修正 | create 在场 |
| compat.create.KineticNetworkUnloadedAccessor | Create KineticNetwork（unloadedCapacity/Stress/Members 读写） | B4 读档账本修正 | create 在场 |
| compat.create.KineticNetworkLedgerTraceMixin | Create KineticNetwork（initFromTE/addSilently/add/remove/sync/updateCapacityFor/updateStressFor HEAD，只读记账本事件环） | B4 误判过载诊断探针（`CdgOverloadProbe`） | create 在场 |
| compat.simurail.PhysicsBogeyBrakeSoundMixin | Simurail 物理转向架 BE | E3/E4 | simurail 在场（simurail 硬性依赖 sable，蕴含 sable 在场） |
| compat.simurail.PhysicsBogeyBrakeSignalCapMixin | Simurail 物理转向架 BE（`getControlStrength` RETURN 把超过 上限/15 的强度压到 上限/15） | E9 | simurail 在场 |
| compat.simurail.PhysicsBogeyLateralForceMixin | Simurail 物理转向架 BE（`tick` + `sable$physicsTick`） | E8 | simurail 在场 |
| compat.simurail.PhysicsBogeyTrackSoundMixin | Simurail 物理转向架 BE | E2 | simurail 在场（client 数组） |
| compat.simurail.PhysicsBogeyWideGaugeParticleMixin | Simurail 物理转向架 BE | E6 | simurail 在场（client 数组） |
| compat.simurail.AutomaticCouplerPartnerAccessor | Simurail 自动车钩 BE（只读连接车厢 UUID，避免查询缺席伙伴触发加载或空值异常） | E7' | simurail 在场 |
| compat.simurail.PhysicsBogeyDeadmanBrakeMixin | Simurail 物理转向架 BE（tick 速度/信号采样 + `getBrakeStrength` RETURN 锁存满刹） | E7' | simurail 在场 |
| compat.simurail.PhysicsBogeyDeadmanStressMixin | Simurail 物理转向架 BE（`calculateStressApplied` RETURN 锁存强制过载 impact，读 `TrainDeadmanBrake` 表） | E7' | simurail 在场 |
| compat.simurail.PhysicsBogeyDeadmanParticleMixin | Simurail 物理转向架 BE（锁存期间 portal 粒子，E6 同参数） | E7' | simurail 在场（client 数组） |
| compat.simurail.AutomaticCouplerRedstoneMixin | Simurail 自动车钩方块 | E5 | simurail 在场 |
| compat.copycats.CopycatsObsidianHardnessMixin | Copycats+ 两根基类 | C0 | copycats 在场 |
| compat.create.CreateCopycatObsidianHardnessMixin | Create CopycatBlock 根 | C0 | create 在场 |
| BlockPropertiesAccessor | 原版 Properties（硬度豁免判据） | C0 | 无条件（原版目标） |
| VillagerMasterMixin | 原版村民（补货/掉职业拦截） | G1 | 无条件（原版目标） |
| VillagerAccessor | 原版村民（交易补全入口） | G1 | 无条件（原版目标） |
| MerchantOfferAccessor | 原版交易条目（maxUses 改写） | G1 | 无条件（原版目标） |
| LimitedWaterMixin | 原版 Level#setBlock 4 参（源水写入改写为 water7） | G3 | 无条件（原版目标） |
| IcePlacementBanMixin | 原版 BlockItem#place（冰/浮冰/蓝冰放置拦截） | G3 | 无条件（原版目标） |
| ChunkMapVoidFitStripMixin | 原版 ChunkMap#save（拟合空位序列化前剥成空气再还原，不落盘） | G3 配套 | 无条件（原版目标） |
| StructureVoidBlockPassthroughMixin | 原版 StructureVoidBlock#getShape（rotating 内掏空，交互射线穿透拟合空位） | G3 配套 | 无条件（原版目标） |
| compat.dimblend.WarpGateBlockMixin | dimblend 折跃门（字符串目标） | G4 | dimblend 在场 |
| compat.fluid.GutterOutletBiomeProjectionMixin | Create: Fluid 集水器 BE（`handlePrecipitationCollection` 内 `getBiome`/`getPrecipitationAt` 的位置参数 WrapOperation，经 `SableWorldPosition.projectBlock` 投影到世界坐标） | Sable 结构群系投影 | fluid 在场 |
| compat.fluid.SmartGutterOutletBiomeProjectionMixin | Create: Fluid 智能集水器 BE（同上，目标方法 `handlePrecipitationCollectionFiltered`） | Sable 结构群系投影 | fluid 在场 |
| client.ItemStackNicknameMixin | 原版 ItemStack | A8 | 无条件（原版目标，客户端侧） |
| ExplorationMapFunctionBanMixin | 原版 ExplorationMapFunction（run HEAD 一切地图产 1 张纸，不跑结构搜索） | A10 | 无条件（原版目标） |
| LootTableAccessor / LootPoolAccessor | 原版 LootTable/LootPool（pools 遍历、entries 整体换引用） | A10 | 无条件（原版目标） |
| LootPoolSingletonContainerAccessor / LootItemAccessor | 原版条目类（functions/item/weight/quality 读取） | A10 | 无条件（原版目标） |
| LootPoolEntryContainerAccessor / CompositeEntryBaseAccessor | 原版条目条件、复合 children 读取 | A10 | 无条件（原版目标） |
| BuriedTreasureShellMarkerMixin | 原版 BuriedTreasurePiece（postProcess 内 createChest 之后读 boundingBox 拿宝箱坐标，调 TreasureShellMarker.mark） | A11 | 无条件（原版目标） |

事件处理器（非 mixin）：DeathRules（A1，LivingExperienceDropEvent + PlayerEvent.Clone）、DepthCurse（A3/A4）、CurseBossbar（A5）、SafeZoneSpawnGuard（A6）、SimurailBlockGuard（E1）、TrainDeadmanSync（E7' 锁存状态进服/换维度补发；规则本体 `TrainDeadmanBrake` 在 Simurail 在场时显式注册维度 Post tick 与卸载/停服事件，mixin 仅采样）、PhysicsAssemblerGuard（F1）、CreativeTabContents（C/K，dimblend-blocks 创造栏登记）、NicknameCommand/NicknameSync（A8）、VillagerMasterRules（G1，含职业记录附件/restock取消/掉职业恢复）、StructureBedGuard（G2）、PortalBan（G4）、EnderStorageStructureGuard（G5）、ExplorationMapLootFilter（A10，LootTableLoadEvent 战利品表加载时把地图条目替换为纸条目）、PaperInsteadOfMapsLootModifier（A10 第三层，NeoForge 全局战利品修饰器，最终把地图堆换成 1 张纸）。

### 依赖接线（C 板块例外为版本级对齐）

| 目标 | 对齐方式 |
|---|---|
| Create 6.0.10 | maven.createmod.net `6.0.10-281`（版本级对齐） |
| Copycats+ 3.0.4 / CCA 1.5.10 / Simurail 0.0.0-a / Sable 2.0.5 | 运行实例 jar 原样复制（字节级对齐，compileOnly） |
| CDG 1.3.15 | 同级源码 `E:\misc\Create-Diesel-Generators`（1.21.1 分支 HEAD=1.3.15）构建 jar（compileOnly） |

### 已知限制（在案）

- **Sable 结构群系投影（2026-09-30）**：结构方块在 plot 坐标里，plot 区块群系是结构创建时烤入、之后不再更新，结构开到别的群系后集水器等仍按创建时的群系判降水（雨天收细雪）。修法两层：① Sable 本体新增 `biome_projection`（`BiomeManager#getBiome` 把 plot 坐标投影到结构当前世界位置；`Level#isRainingAt` 与 `ServerLevel#tickPrecipitation` 内 `Biome#getPrecipitationAt` 的位置参数同样投影），对所有模组的 `level.getBiome(plotPos)` 生效；② 本模组对集水器 `getBiome`/`getPrecipitationAt` 做同口径投影（幂等，不依赖 Sable 构建版本）。**残余限制**：`Biome` 的温度方法只收 `BlockPos`、无 level，无法在内部反投影，所以其他模组直接对 plot 坐标调 `Biome#getPrecipitationAt/shouldSnow/shouldFreeze`/温度方法时，群系已对但海拔修正（Y>80）仍按 plot 的 Y（约建筑高度中点，等效世界 Y≈128）、冰面噪声按 plot 的 XZ，只影响基础温度约 0.15~0.21 的群系的雪/雨边界。运行验证：`SableBiomeProjectionGameTests`（需带 `biome_projection` 的 Sable 构建）、`GutterOutletBiomeProjectionGameTests`（`-PwithFluid`）。
- CDG 巨型柴油机（PoweredEngineShaft 出力、不实现 IEngine）不在 B 板块覆盖内
  （v1.2 第二版起由 B5 覆盖，本条作废，保留备查）
- Simurail 0.0.0-a 方块注册表仅 PhysicsBogey/AutomaticCoupler 两项（CenteredGangwayJoint 有类无条目）；升级 Simurail 需重核 E1 清单
- D2 映射后马达实际转速 ≠ 滚动面板显示值（规格固有结果，面板保留原设定）
- **D3 口径细化（复核在案）**：面板 |设定|≤4rpm 时按面板直通——面板设为 2rpm 时全信号档实际 2rpm，低于 D3 的"最低 4rpm"下限。"最低 4rpm"约束的是**映射公式**的输出下限（信号 1 档 = 4rpm），非面板设定下限；如需面板低于 4 也钳到 4，需另行拍板
- **E2-E4 运行 jar 差异（2026-09-19 字节码核实）**：spec E0 原拟"恢复 PhysicsBogeySounds 被注释的 track 通道"——**运行 jar 0.0.0-a 无 PhysicsBogeySounds 类**（新版源码才有），音效系从零实现。刹车强度运行 jar 为 `getBrakeStrength()D`（spec 依据新版源码写的 `getGroupBrakeStrength()` 不存在）；刹车状态不在渲染同步包内，E3/E4 只能服务端沿检测（2026-09-23 起挂服务端 `tick()`，见 E3/E4）
- **E3/E4 升级风险 T1（2026-09-23 复核登记）**：新版 simurail 源码已有 `getGroupBrakeStrength` 组播语义（Axle 改调组播）；一旦升级 simurail，单红石输入将物理制动整列、但沿检测仍只在信号车架触发，"所有车架都会播放"即破。升级时须把沿检测切到组播强度（代价：同沿多车架合唱叠加需另行评估）
- **E2 口径（v1.1 新映射）**：pitch 分段线性（2m/s→0.2、10→1.0、16→1.5；<2 静音、>16 钳制 1.5），音量固定 1.0，淡入淡出 100 tick；仍以同步的 visualSpeed（b/s）门控，不查 hasTrack（车轴字段客户端不更新，恒旧值）；脱线但仍有速度的车架会播循环——接受
- **D4 口径（v1.1：阈值 16→64rpm）**：用户补供 startup/stopping 素材，实现为三态（启动→循环→停转）；启动音与循环同时起（启动音盖住循环头，不解析 ogg 时长）；行为开关开启时 CCA 原版马达声被整体静音替代
- **E2 客户端 tick 前提**：客户端子层级客户绘图需 tick 转向架 BE（tick()V 客户端分支存在渲染插值代码，推定成立）——实机听音验证为最终实证
- **N2/N3 分液池（2026-10-23 补录在案）**：① N2 催熟「生效才扣」的已知边界——个别 `performBonemeal` 实现零世界变化仍计生效并扣水（竹子顶端被挡/树苗长成失败），严格前后快照检测覆盖不全故不采用；本催熟不走 `BonemealEvent`，靠骨粉事件联动的第三方模组感知不到。② N3 管道回注——加工流体物品的当拍有 1-tick 注水关窗（`continueProcessing` 收尾复位 `forbidInsertion` 晚于本头置位，下一拍自愈）；热切换不调 `invalidateCapabilities`，缓存 UP 面接口存在性的消费者（如 Create 管道）热开启后顶面注水可能需一次方块更新才开始
- **N6 分液池下界蒸发（2026-10-26）**：按**群系**判定、不按维度——任何下界群系（含旋转维度下界带、模组下界群系）都蒸发；Sable 结构方块先投影到当前世界位置再取群系，列车行驶中逐拍取当前位置，不缓存。与管道回注同拍竞争时按当拍实存水扣：回注补多少扣多少，水箱水位不保证稳定；SIMULATE 因并发/组件失配不足时本拍不扣，且不补扣（避免停机后集中扣水）。

### 游戏内运行验证清单（待实机，当前仅构建通过）

- A 板块：A3-A5 冒烟 ①-⑨；A1 比率矩阵实测（比率 1.00/0.50/0.00 × keepInventory 开/关：重生经验一致、经验球不掉落）+ 饰品栏/墓碑类 mod 组合（物品不再经本模组，确认不丢不复制）；A6 worldgen 实测（含 tag 补强——钻头/锯/机械臂拦截实测）；A8 tooltip/命令实跑
- B：点火爬梯实测（16→+2/4s→额定→波动 76.8~96）；过载闩锁与重新加油触发；双开关矩阵（depthCurse 关 + curseBossbar 开）
- C：粱蓝图打印、物品栏显示、生存不可破坏、扳手只撕材质
- D：红石 1/8/15 档转速映射实测、能耗纯线性（4~7rpm 不再按 8 计）、无红石停转、负面板对称映射、CC setRPM 路径
- E1：生存玩家交互全拦、爆炸不毁、创造可编辑、机器钻头 tag 拦截实测
- **E2-E4/D4 音效**：列车行驶循环随速度、刹车/松闸触发音、马达三态音实机听音验证（启动验证已完成：三个新 mixin 应用成功、0 ERROR 到主菜单、6 ogg 全进 jar）
- v1.1 增补实机项：C0 全体伪装硬度（挖原版/创造变体手感+爆抗）、C3 伪装板（蓝图/物品栏/生存不可破坏/扳手撕材质）、F1 组装器（生存禁摆无提示+创造可摆）、E5 车钩红石不断开（贯通框仍断开）、D4 64rpm 阈值、E2 新映射听感（2/10/16 三点音调+5秒淡入淡出）、A9 虚空 band 内诅咒与 Insanity 叠加上限显示
- 模型装饰器实机视觉、列车移动 BE 同步、mixin 运行时应用（启动日志确认）、mixin plugin 过滤（卸载 CCA/CDG 后日志确认）
- 启动验证（2026-09-19，TrainTripWorld 实例实测两轮）：mod 加载、common setup、全部 mixin 应用（defaultRequire:1 下存活即实证）、0 ERROR/FATAL 到主菜单；期间修复 NeoForge 版本范围硬阻塞（[21.1.251,)→[21.1.249,) 解耦）与 example 脚手架残留
- 启动验证（2026-09-23 v1.7 批次，dev runClient + CCA 临时 localRuntime）：createaddition 1.5.10 加载、CCA Initialized、Sound engine started 到主菜单，0 ERROR/FATAL/InvalidMixin；本批 4 个 mixin（SoundClient 改版 + AlternatorIdleDrain + Goggle + GeneratorStats）随 CCA BE 注册完成 apply；dev 环境 mixin 库为 sponge-mixin 0.15.2+mixin.0.8.7（fabric fork）。实机听音/看数仍在待办
- G7 实机项（2026-10-15 验收通过）：TrainTripWorld 实例放产蛋器于 sable 结构内（单块/多块子层级各一）与主世界各一，确认玻璃内嵌鸡可见、明暗与周围方块一致、玻璃仍半透明、孵化器不受影响、物品栏图标正常
- L 板块实机项：储罐+汽笛（无热源）邻接蒸汽引擎/柴油机/电动马达各放红石，验证鸣笛音效对应、张嘴+音符前冲、持续供能只播一次、播放中断电淡出且立即复电可重播、特殊模式无 Create 原生汽笛叠加（另在有热源锅炉上复核）；256/512 距离衰减与左右方向感听音；Sable 结构上同验（声音随结构当前位置）；9 键 pitch 配置各改一档实测
- M 板块实机项：夜视仪开夜视后电量 50%/25%/10% 三档遮罩深度观感校准（50% 应无遮罩、10% 应严重干扰视线）、电量耗尽自动关夜视后遮罩消失、F1 隐藏 HUD 时遮罩随图层一同隐藏、帧率对比无感（对照原噪点版的 FPS 拖累）
- N 板块实机项：新存档（YAT 组件水链路）催熟生效+默认恰扣 20mb 复测、无候选倒计时停走与作物出现自动恢复、保湿建票拉满与维持、管道侧面/底面/顶面注水与关开关恢复原版、护目镜与 Jade 两行文案同口径、热改半径/周期/单次消耗配置生效
- O 板块实机项：生存挖掘汲水器/高压泵/锥形喷嘴/扁平喷嘴/水桶控制器各掉自身物品、扳手潜行拆卸返还物品（修复前两者皆凭空消失）

### 复核闭环状态（截至第 25 轮）

| 板块 | 首轮 | 增量验证 | 状态 |
|---|---|---|---|
| A1/A2 | 1高3中5低（全属实） | 通道 A **通过**（含归一化硬化采纳） | ✅ 闭环 |
| A3/A4/A5 | 1中2低（全属实） | 通道 A **通过**（第 2 轮） | ✅ 闭环 |
| A6/A7 | 1高1中2低（全属实） | 通道 A **通过**（第 2 轮） | ✅ 闭环 |
| A8 | 5低（无高中） | 通道 A **通过**（第 2 轮） | ✅ 闭环 |
| C | 2中1低（全属实） | 通道 A **通过**（第 2 轮） | ✅ 闭环 |
| E1 | 1中3低（全属实） | 通道 A **通过**（第 2 轮；tag 补强专项核证；non_breakable tag 不受开关控制的例外口径已注释在案） | ✅ 闭环 |
| D | 4高5中2低 + 修复期 6 项（全属实，含字节码误读纠偏×2） | 通道 B 回退（`ff66c9db`，原 agent adcc6814 收尾中断）+ 通道 A 续 ×2：7/7 全关（4 阻断点=build 不可见的 apply 类错误）、1 低措辞修正后**终止条件达成** | ✅ 闭环 |
| B | 2高3中4低（全属实）+ 3 修复引入项 | 第 4 轮增量 **通过**（"循环终止条件达成"；**遗留 fluctFactor 首额定 tick = 接受现状**（代码未变，B 侧第 5 轮逐字节核验一致）。如日后采纳重置，必须覆盖全部三条点火路径：闩锁解除、燃尽复位、**红石停转复位**——只改前两处会让红石闪断场景复现遗留 factor 首额定 tick） | ✅ 闭环 |
| E2-E4/D4 音效 | 2中2低（全属实） | 通道 A **通过**（4/4 全关 + 观察项采纳 remap=false；中1 双声叠加、中2 幽灵声、低3 过时注释×2、低4 首 tick 误播；复核侧字节码独立核实：E2 客户端 tick 前提成立、CCA tickAudio active 单读无状态机残留、sable 路由机制与车勾先例同型、双端类加载安全） | ✅ 闭环 |
| v1.1 增补（C0/C3/F1/E5/D4-64/E2新映射/A9） | 3低（ModSounds注释残留16、panel蓝图取材对齐、车钩客户端漂移；全属实，无高中） | 通道 B 回退（首轮 agent 结束）第 2 轮：2/3 全关，残留 ModSounds:27+过渡注释 → 修复；第 3 轮（原 agent 通道 A 复活验证）：残留清零、CCA 目录 16 零命中（B 板块 CDG 的 16rpm 为合法除外）、**终止条件达成**。panel 不对齐 beam 系核实后有意为之（Create 链无该接口）；车钩改双端 cancel；启动验证（21:56 新 jar，0 ERROR 到主菜单、panel 模型警告清零、defaultRequire:1 存活） | ✅ 闭环 |
| B4 过载爆机（原闩锁作废） | 0 真实问题（首轮即通过）+ 2 处文档债（javadoc 仍写闩锁为主语） | 独立全量复核**通过**：RETURN 内 destroyBlock 无重入/更新风暴；false 基本不可达、E1 与 CDG 零交集、闩锁兜底自洽；单方块只破本体、升级件经 onRemove 另掉、两战利品表掉自身；B1-B3/守卫/门控完整。文档债已修（mixin+CdgEngineState 改"爆机为主、失败回退闩锁"表述）。待实机：爆机掉落/升级件 pop/余油不返还 | ✅ 闭环（待实机） |
| v1.2 代码+B5 | 2中3低（全属实） | 首轮：中1 B5 计时无条件推进（停机照涨致重启丢爬梯）、中2 音量 3.0 只扩射程不提响度；低：B5 无闩锁回退、文档债×3、respawn 未清方向记忆。修复：enabled 门控+复位、过载后移（他人应力不牵连）、闩锁给0/加油解闩；ogg 本体线性增益归一 -1dBFS（brake+8.9/loop+11.1/stopping+6.5/train_brake+22.8，另二满幅未动，ffmpeg；3.0 保留射程）；回退对齐 B4；文档债+respawn 全清。第 2 轮（通道 A）：仅剩 spec E2 增益入库一行 → 第 3 轮补后**终止条件达成**。启动验证（23:12 新 jar，0 ERROR 到主菜单、defaultRequire:1 存活） | ✅ 闭环（待实机：B5 ramp/波动/爆机、音效听感、本底噪声） |
| M 夜视低电量噪点 | 3低（全文档项，代码 6 风险点全无问题） | 首轮：P1 清单标题计数 6→7 未同步、P2 「libs/ 仅剩…」枚举漏 createfirefightingadd/hardcore_nvg、P3 缺 M 板块 spec 条目；已修（另顺修 build.gradle:141 同型过时枚举注释）。第 2 轮（通道 A，原 agent）：4/4 全关、常量逐项对账一致、无新增问题，**终止条件达成** | ✅ 闭环（待实机：50%/25%/10% 密度观感校准、耗尽消失、F1 随图层隐藏） |
| G2 rotating 跳夜恢复 | 2中（全属实）：①`ServerLevelTickTimeMixin` 前提错误成死代码（rotating 构造即 `tickTime=false`，`tickTime()` 体不执行，无双倍流速可拦）+ 四处机制误述；②`/time add` 逐 level 相对写经穿透叠成 +2X 回归 | 通道 A **通过**（第 2 轮，逐项 file:line 证据）：死 mixin 删净零残留、三处文档改写为 tickTime=false 真实机制；新增 `TimeCommandMixin`（`addTime` 内唯一调用点、private static、require=1 应用实证）跳过 `instanceof RotatingLevelData` 回声写；单测独立复跑绿、GameTest 两轮红均为记录在案 sable 抖动签名 | ✅ 闭环（待实机：rotating 跳夜到早晨/白天小睡不跳日/强制起床清雨、`/time add` 单倍生效） |
| 分液池催熟组件水探头 | 0（首轮全量即通过） | 独立复核字节码级坐实根因链（`FluidTank.drain` 按类型+组件匹配→新存档供水链路附加组件→裸探头失配 "cannot drain"）；全模块 drain/fill 调用点排查无同类裸探头（保湿只读不消耗、铜水槽探头派生自 SIMULATE 实栈、铜龙头走数量重载）；回归用例对旧代码必红（fill 保组件）、断言恰扣默认 20mb；无技术债。~~spec 文档债在案：分液池板块（保湿/催熟/管道回注）无条目~~ → **2026-10-23 已补录销账（板块 N，用户拍板「补」）** | ✅ 闭环（待实机：新存档复测催熟+扣水、无作物停走复测） |
| 汽笛音高九项进 tune 面板 | 1中1低（全属实）：①快照协议版本未升版（快照每选项一值，仓纪律每增删选项必升版）；②parameter-tuning.md 主设置表缺汽笛行 | 通道 A **通过**（第 2 轮，逐项 file:line 证据）：`TuningNetwork` registrar "23"→"24" 注释同步、parameter-tuning.md 三处版本号一致 24（含两处历史残留 22 顺修）、主设置表补 3 行（沿用柴油过载合并行风格）；其余 `registrar` 通道独立不受影响；修复 diff 零新增问题 | ✅ 闭环（待实机：面板翻页渲染、缺引擎 mod 灰显、改值即时生效；发布需双端同版本 jar） |
| spec 分液池板块补录（板块 N） | 2低（全属实，纯文档字面值偏差，同在运行验证行）：①「六面注水全收」与代码不符（实为 DOWN/侧面/UP 三面 1000mb）；②`pipe_refill_toggle` 批次归属不精确（三面注水用例在默认批次） | 通道 A **通过**（第 2 轮）：P1/P2 修复文本与 `ItemDrainPipeRefillGameTests` 断言逐字对码一致、与 N3/实机项自洽、无新增问题；首轮已全量核过 N1-N5 机制描述、Config/Mixin 表新行、日期归因、8 个 GameTest 批次名全部属实 | ✅ 闭环（文档债销账） |

**审计链更正**：D 板块的"最小正确结构 6 条"提炼自 B 复核报告的经验教训（name-only target、ServerLevel 守卫前置、跃迁 setChanged、附件 codec 等），不宜记作复核侧"D 板块指引"；D 第 1 轮验证载荷曾误发 B 复核 agent（未达），已重发至 D 复核 agent（`adcc6814`）并确认送达。

复核教训沉淀：① javap 缩写注释（无 owner 前缀的 Methodref）≠ invokeinterface——@At target 写 owner 前需 -v 常量池实读；② SERVER 配置在专用客户端不加载，任何双端方法读 SERVER config 前必须 ServerLevel 守卫前置；③ 转速映射类逻辑的"当前值 vs 原始额定"判据必须用原始额定（首轮抽取后当前值恒低于额定）；④ 无 serializer 的附件不跨区块卸载——需要跨卸载的状态必须 serialize；⑤ **父类声明的 BE 方法不能用类级 HEAD @Inject 注入（Mixin 0.8.5 仅遍历目标类 methods）——必须调用点级 INVOKE 注入**；⑥ panel 类映射的源必须是权威设定值（ScrollValueBehaviour.getValue），不能是已映射的运行值（getRPM 读 motorSpeed 会反馈坍缩）。

## 7. 决策记录

### libs jar 清单（7 个 addon 编译依赖）

> 2026-09-25 单仓更新：开源三件（Copycats+ / CCA / CDG）与 sable-companion 已改走
> Maven 定点版本（见 `dimblend-experience/build.gradle`、`dimblend-blocks/build.gradle`），
> 不再需要本地 jar；`libs/` 仅剩无公开 Maven 的 simurail / sable cui-modded / fluid /
> createfirefightingadd / hardcore_nvg。
> 下表保留作运行时版本对账依据。

2026-09-19 从实例 `E:\misc\TrainTripWorld\mods` 原样复制到 `./libs`（字节级一致，SHA-256 已核对）。
CDG 1.3.15 除外：2026-09-20 起 B 板块对齐最新版，jar 由同级源码
`E:\misc\Create-Diesel-Generators`（1.21.1 分支，`mod_version=1.21.1-1.3.15`）
`build/libs` 构建产物复制而来（旧 1.3.11 jar 已移出 `libs/`，如需回退从实例 mods 目录取回）。
运行实例同步确认（2026-09-20 核实）：`E:\misc\TrainTripWorld\mods` 内已为
`createdieselgenerators-1.21.1-1.3.15.jar`（与 `libs/` 同构建产物，`IEngine#getFuelThrottle`
经 javap 确认存在）——B 板块编译基线与运行实例一致，无版本错配。
`libs/` 整体不入库；新环境编译前按同名从实例 mods 目录复制回来（CDG 1.3.15 按上述来源）：

| jar | 模组 | 版本 |
|---|---|---|
| `copycats-3.0.4+mc.1.21.1-neoforge.jar` | Create: Copycats+ | 3.0.4 |
| `createaddition-1.5.10.jar` | Create: Crafts & Additions | 1.5.10 |
| `createdieselgenerators-1.21.1-1.3.15.jar` | Create: Diesel Generators | 1.3.15（同级源码构建） |
| `simurail-1.21.1-0.0.0-a+ecd2dd3.jar` | Create Simurail | 0.0.0-a（本地构建） |
| `sable-neoforge-1.21.1-2.0.5_cui-modded.jar` | Sable | 2.0.5（cui-modded 定制构建） |
| `fluid-2.0.1.jar` | Create: Fluid | 2.0.1（CurseForge 包，无公开 Maven；从运行实例 mods 目录原样复制，与实例一致） |
| `hardcore_nvg-1.1.2-neoforge-1.21.1.jar` | Hardcore Night Vision Goggles | 1.1.2（MCreator 模组，无公开 Maven；2026-10-23 从正式包 `D:\Downloads\TrainTripWorld_1.21.1-NeoForge_21.1.249\mods` 原样复制——注意 dev 实例 `E:\misc\TrainTripWorld\mods` 历史装的是 basic_nvg（无限夜视版），已对齐换为 hardcore 版） |

依赖形态：`compileOnly files(...)`（编译可见、不进 runtimeClasspath、不进 POM）；
需要运行测试时按需把相关 jar 临时提升为 `localRuntime`。CDG 已固化为开关：
`./gradlew :dimblend-experience:runGameTestServer -PwithCdg`（build.gradle 按 `withCdg` 属性加
localRuntime，默认不加，dev 运行仍与实例对齐）。

| 轮次 | 决策点 | 结论 |
|---|---|---|
| 1 | 第三方依赖来源 | 模组均开源：clone 源码到同级目录；编译对准运行实例 jar |
| 1 | 电动马达身份 | Create: Crafts & Additions |
| 1 | SimRail 身份 | Create-Simurail（源码在同级目录） |
| 1 | A2 重生点 | 参考 dimblend 模组语境：字面 (死亡x, 66, 5) |
| 1 | A3 生命上限下限 | 最低保留 1 点（半颗心） |
| 1 | A6 生成拦截口径 | 仅拦自然生成，玩家/机器间接生成放行 |
| 1 | A7 信标范围 | 信标所在维度全体玩家 |
| 1 | A8 输入方式 | 命令为主 |
| 2 | A1–A6 生效维度 | 仅 `dimblend:rotating` |
| 2 | 刷怪笼归类 | 玩家侧，放行 |
| 2 | B4 过载恢复 | 不自动重启，需重新加油 |
| 2 | E1 保护对象 | Simurail 自带方块 |
| 2 | 音效素材 | 用户提供，我接入 |
| 3 | 虚空 band 扣上限 | 与 Voidscape Insanity 共存（各动各的 modifier id），不禁用虚空侧 |
| 3 | 伪装硬度 | 全体 copycat 方块（含 Create 本体三件套）统一黑曜石硬度；创造变体仍生存不可破坏 |
| 3 | 伪装板基底 | Create 本体的 CopycatPanelBlock（非 Copycats+ Board） |
| 3 | 组装器禁摆范围 | 生存禁、创造放行 |
| 3 | 车钩红石 | 仅车钩，贯通框不动 |
| 3 | E2 音量 | 固定满音量，只做 pitch 映射+淡入淡出 |
| 4 | HUD 换位整条 | 忽略（bossbar 保持顶部原位，band 条不动） |
| 4 | v1.3 z256 HUD | bossbar 方案作废，改常驻护甲列进度条（见 z256-bar-requirement.md）；band 条是否重叠留实机确认 |
| 4 | 松闸音效 | 保留，维持原参数 |
| 4 | A4/C1"未实现"标注 | 开发侧已实现，记"已实现待实测"，以实机为准 |


## 12. 板块 K：Create 创造模式传动件（dimblend-blocks，v1.11 新增）

> 来源：2026-09-29 用户粘贴的 dimblend-blocks 批次 + 当日拍板澄清（「创造传动杆 =
> 只有创造能放置的传动杆；生存只能套壳、扳手拆壳、扳手互转，不能调方向不能拆除，
> 也不能变成蒸汽引擎的一部分」）。

- K1 创造模式传动杆（`creative_shaft`）：行为同 `create:shaft`，差异：
  - 生存完全不可破坏（硬度 -1 + 阻爆 3600000 + 无战利品表），仅创造可放置/破坏
    （`CreativeOnlyBlockItem` + `getStateForPlacement` 创造门 + `EntityPlaceEvent`
    兜底守卫，与板块 C 同套纪律）。
  - 扳手不可拆（生存）；旋转/互转/拆卸按模式分派（**2026-09-29 两轮拍板**）：
    **创造**恢复 Create 原版扳手语义——普通右键先拆支架否则绕点击面旋转轴、
    潜行右键拆卸不掉落（创造可拆可放，无需互转补偿手势）；
    **生存**不能调方向不能拆——裸轴普通右键 ⇄ 变 `creative_cogwheel`
    （同轴同含水，`switchToBlockState` 保 BE/转速/支架），潜行右键无操作。
  - 可套壳（安山/黄铜）：`EncasingRegistry` 注册自有 encased 变体；**encased 状态
    潜行扳手拆壳**回 `creative_shaft`；encased 普通右键创造=旋转、生存=无效。
    Create 原生套壳不消耗壳物品、拆壳不返还壳（6.0.10 字节码核实，issue #6309
    关为既定行为），照搬对称。
  - **永不与蒸汽引擎连接（零代码成立，勿加拦截代码）**：引擎→轴的判定全是注册身份
    判定——`SteamEngineBlock.isShaftValid` 用 `AllBlocks.SHAFT.has(state)`、引擎 BE
    用 `instanceof PoweredShaftBlockEntity` 认领——自定义方块天然不被认领，引擎只是
    不工作、无任何副作用；`pickCorrectShaftType` 同样不会把本方块换成 powered_shaft。
  - 金属横梁套壳分支已剔除（原版会把轴换成普通 girder-encased-shaft，丢失创造属性）；
    PoleHelper 连放谓词扩展认本方块（照 C 板块 helper 模式）。
- K2 创造模式齿轮（`creative_cogwheel`）：同 `create:cogwheel`（仅小号，不做大齿轮），
  约束同 K1；生存裸齿轮扳手右键 → 变 `creative_shaft`（K1 的互转另一半）。
  齿轮物品继承 `CogwheelBlockItem` 保留对角/嵌入放置 helper UX（谓词 instanceof 判定），
  另加创造门。
- K3 识别色（2026-09-29 拍板，同日改色）：旋转本体渲染统一改用自有贴图模型
  （`dimblend_blocks:block/creative_shaft|creative_cogwheel`）——**乌木齿轮**
  （齿环 texel ×(0.58, 0.46, 0.38) 深暖褐）+**黑铁杆**（轴杆 texel
  ×(0.50, 0.56, 0.66) 深青灰），创造版与原版一眼区分；blockstate/物品模型/
  visual partial 同步指向；套壳外壳保持 Create 原色（开口内轴件为识别色）；
  BER 回退路径沿用 Create 渲染件不染色（目标实例 Flywheel 常开，可接受）。
- encased 变体 4 个（`creative_andesite_encased_shaft` 等）：无物品形态（与 Create 一致）；
  encased 齿轮的 `TOP_SHAFT`/`BOTTOM_SHAFT` 翻转与普通右键旋转一并禁用。
- BE 类型自有（白名单注册期固定，同 C 板块理由）：`creative_bracketed_kinetic`
  （BracketedKineticBlockEntity，轴+齿轮，支架行为继承即满足）、`creative_encased_shaft`
  （KineticBlockEntity）、`creative_encased_cogwheel`（SimpleKineticBlockEntity）。
  渲染：Flywheel visual + BER fallback 双注册复用 Create 渲染件（齿轮不能复用
  `BracketedKineticBlockEntityVisual::create`——其小齿轮分支是 `AllBlocks.COGWHEEL`
  身份判定，自定义齿轮会被画成轴，故轴/齿轮用自写小工厂选 partial）。
- 蓝图取材一律 `ItemRequirement.NONE`（encased 类覆写）；应力影响/容量不注册
  （`BlockStressValues` 查不到即返回常量 0.0，javap 核实，与 Create 轴的 setNoImpact 同效）。
- encased 变体外壳无 CT 连贯（Create 的 EncasedCogCTBehaviour 绑定其自有 BE 类型 + CTModel
  包装 + CasingConnectivity 三条本板块均不接入，相邻套壳件外壳贴图不连通；纯视觉，勿误判为 bug）。
- 放置守卫按快照旧状态判别「放置 vs 互转/拆壳」（`CreativeKineticGuard`）：NeoForge 服务端
  `ItemStack.useOn`（`CommonHooks.onPlaceItemIntoWorld`）会对物品 use 期间的全部 setBlock
  补拍快照并补抛 EntityPlaceEvent——扳手 `switchToBlockState` 同格替换自家方块也会落入。
  快照旧状态是本板块方块 → 同格转换（放行，生存可用的扳手操作）；旧状态是空气/他物 →
  新放置（非创造一律取消并回滚）。**v1.11 实测缺陷修复在案**：旧版只看放置物一刀切，
  把扳手互转/拆壳误判为放置，生存下回滚 + FAIL（GameTest 复现钉死）；
  服务端 GameTest `CreativeKineticsGameTest`（11 条：生存互转双向、生存潜行不拆、
  生存潜行拆壳、守卫负向分支、创造旋转×3、创造潜行拆卸、encased 齿轮创造端面翻转、
  生存 encased 无效）常驻防回归。

## 13. 板块 L：列车汽笛（2026-10-21 新增）

> 来源：2026-10-21 用户追加需求 + 两轮拍板澄清（电池组答②按 CCA 1.5.10 实际注册
> 更正为电动马达+模块化电池；多引擎方向优先级「都可以」→ 定 前→后→左→右→上→下；
> 范围仅静止世界+Sable 结构；距离 256 全音量/512 衰减至零、立体声、无视遮挡；
> 音符粒子方案；忽略延长管长度，音阶默认 小 1.5/中 1.0/大 0.5（小笛高音、大笛低音）；无功能开关、9 键 pitch 独立配置）。

- L1 触发语义（2026-10-24 修订）：蒸汽笛六面邻接引擎方块时，**不管所附储罐有无热源**，
  红石上升沿播放对应音效一次，持续高电平不重播，片段自然播完即静默。实现挂
  `WhistleBlock.neighborChanged` TAIL（`WhistleHornBlockMixin`）对比 POWERED 前后值：
  上升沿起播，下降沿立即清除服务端 BE 锁定并广播停止；播放中断电则约 4 tick 音量
  淡出，重新供能可立即从头播放。锁定存纯内存字段（`TrainHornStateAccess`，卸载/重进
  清零，不落盘）。
- L2 引擎映射：Create 蒸汽引擎 → `train_horn_steam`；CDG 三种柴油机
  （普通/巨型/组合，instanceof 三类）→ `train_horn_diesel`；CCA 电动马达/模块化
  电池 → `train_horn_elec`（CCA 1.5.10 旧 `accumulator` 已从注册表移除，字节码
  核实，电动组仅此两种）。多引擎邻接按方向优先级取第一个命中：以汽笛朝向为基准
  正面→背面→左→右→上→下（附着储罐一面永不命中）。不考虑 Create: Sound of Steam。
- L3 播放表现：客户端窗口内汽笛张嘴（`WhistleHornClientMixin` 驱动
  `LerpedFloat animation.chase(1.0, …)`）+ 音符粒子沿汽笛朝向锯齿推进前冲
  （每 12 tick 一脉冲、脉冲内前移 0.12/tick；NoteParticle 第一槽为颜色参数，
  按音效种类配色 steam 0.6/diesel 0.08/elec 0.35）。原始 POWERED 且邻接引擎的整个
  特殊供电期间，`tickAudio` HEAD 无条件取消 Create 原生循环汽笛、chiff 与蒸汽粒子；
  判定直接读客户端方块与邻接，避免开始包到达前漏原声。
- L4 声音路由：服务端自定义 payload（`train_horn`，pos+kind+pitch+start，协议版本 2）
  按 Sable 投影后世界坐标广播 528 格内玩家；客户端按位置保存
  `TrainHornSoundInstance`，停止包或客户端断电对账触发淡出，重新起播前精确停止旧实例，
  登出清理全部句柄。声音使用 Attenuation.NONE（OpenAL 不做距离衰减但保留世界坐标声像=
  立体声方向性），每 tick 手算音量曲线：≤256 格全音量、256→512 线性衰减、≥512 静音
  （初始 512 外不起播）；无视遮挡。**在案口径**：广播只在动作发生瞬间按距离过滤——
  触发时刻 528 格外的玩家中途走近不补播残段（3 秒一次性音效，持续追踪玩家 proximity
  的代价不成比例）。素材已降混单声道（立体声源 OpenAL 不做空间化，同 D4 先例）。pitch
  服务端权威（SERVER 配置 9 键随包下发，客户端不读本地配置，无双端漂移），引擎钳 [0.5,2.0]。
- L5 距离/时长常量：锁定时长=素材实测（ffprobe 单声道版 steam 2.60s→55 tick、
  diesel 3.04s→64、elec 3.43s→72），换素材须同步改 `TrainHorn.HornKind`。
  （2026-10-22 elec 换正确素材：用户补供 `TrainHorn_Elec (1).ogg` 3.43s，
  原 `TrainHorn_Elec.ogg` 3.04s 为发错的素材，已降混单声道入库替换。）
- L6 范围：静止世界 + Sable 结构（声音按结构当前世界位置路由，粒子/声像每 tick
  重投影跟随）；Create contraption（活塞/轴承上的汽笛）不管。**在案限制**：Sable
  结构旋转（yaw）后音符粒子的「前冲」方向取 plot 坐标系的汽笛 FACING，不随结构
  姿态旋转（位置投影正确、方向不投影）——需求口径只对声音承诺结构路由，粒子
  方向不在承诺内；如实机观感明显再取 subLevel 姿态旋转向量。
- 运行验证：`TrainHornGameTests`（create 组：蒸汽触发+kind 断言/无引擎静默/断电立即
  清锁并重启/持续高电平不重播）、`TrainHornPayloadTest`（start/stop codec）、
  `TrainHornCdgGameTests`（-PwithCdg：三种柴油引擎各自触发、方向优先级 背面>左侧 /
  正面>背面）、`TrainHornCcaGameTests`（-PwithCca：电动马达、模块化电池各自触发）。

## 14. 板块 M：夜视仪低电量渐褪（2026-10-23 新增，同日修订为渐褪）

> 来源：2026-10-23 用户追加需求——夜视仪电量低于 50% 时给低电量视觉反馈（原始
> 提案为白噪点，密度随 50%→0% 增多，10% 时严重干扰视线）；同日用户实测噪点版
> 拖 FPS 且伤眼，拍板改为「50% 起逐渐褪去夜视效果」。
> 用户确认包内装的是 hardcore_nvg（有电量版，非 basic_nvg 无限夜视版）。

- M1 口径：夜视开启（`NightVision=true`）且剩余电量 `Percent<50` 时，客户端在
  世界画面上叠一层随电量线性加深的**黑色整屏遮罩**，模拟夜视亮度逐渐褪去——50%
  起透明，0% 时不透明度 85%（10% 时≈上限的 80%，对齐原噪点版的「严重干扰」
  参考增量）；负电量按 0% 钳制。整屏一次 fill，无逐像素绘制（噪点版每帧数千次
  单像素 fill 是 FPS 拖累根因）；仅客户端渲染，不回写任何状态；无功能开关。
- M2 读数来源：hardcore_nvg 的玩家附件 `hardcore_nvg:player_variables`
  （`HardcoreNvgModVariables.PLAYER_VARIABLES`）——`Percent`（double 0–100）为剩余
  电量（该 mod 电量即物品耐久，夜视生效期间每 tick 耐久 -1），`NightVision`
  （boolean）为夜视开关；服务端每 tick `syncPlayerVariables` 同步到本人客户端，
  图层直接读本地附件，无自定义网络包（反编译核实，证据 /tmp/hnvg/）。
- M3 图层顺序：`NvgBatteryFadeLayer` 经 `RegisterGuiLayersEvent#registerBelowAll`
  挂在全部 HUD 层之下——只压暗世界画面，准星与快捷栏等 HUD 保持原亮度。hardcore_nvg 缺席时 `ModList` 守卫不
  注册图层，lambda 的 invokedynamic 永不链接，类加载安全。
- 运行验证：`NvgBatteryFadeLayerTest` 单测（≥50% 不遮罩、10% 锚点=上限 80%、
  50%→0% 单调加深、负值钳 0%）；`:dimblend-experience:test` 全绿。
  实机观感校准待用户（见「游戏内运行验证清单」M 板块实机项）。

## 15. 板块 N：分液池（Create Item Drain 扩展，2026-10-23 补录）

> 来源：功能本体早于单仓（2026-09-25 随 8bae78f 合入，此前无 spec 条目、javadoc 为
> 唯一文档源——即复核闭环表登记的 spec 文档债，本板块补录销账）；2026-10-10 补
> 护目镜/Jade HUD（18879c2）；2026-10-22 催熟候选收窄为作物型 + 无候选倒计时停走
> （6cca7d3/cb404a5）；2026-10-23 催熟抽水探头保数据组件修复（3e76931）；
> 2026-10-26 新增 N6 下界群系蒸发（只蒸水，50 mB/s 默认）。
> 四个子功能全部全局生效、不限维度。

- N1 保湿（`itemDrainIrrigation`，默认开）：水箱只要有水（**无最低水量门槛、不耗
  水**）即持有 NeoForge `FarmlandWaterManager` AABB 票据，覆盖以池为中心、边长
  2r+1 的**同层**方形（y..y+1，r=`itemDrainCoverageRadius` 默认 3 即 7x7）；原版
  `FarmBlock.randomTick` 的 `isNearWater` 认票据，范围内耕地维持 MOISTURE=7。建票
  即把范围内耕地主动拉满一次（写盘 flag 与原版 randomTick 一致），其余时间走原版
  randomTick 节奏；空水箱/非水摘票，回水后下一拍自动重建；票据为内存态，区块卸载
  由管理器按 master chunk 自清。只认水；主动拉满只识别原版 FARMLAND（第三方
  FarmBlock 子类走票据被动保湿）。驱动：`ItemDrainIrrigationMixin` 挂
  `ItemDrainBlockEntity.tick` HEAD（服务端、10 tick 降拍）+ `invalidate` TAIL 摘票。
- N2 催熟（`itemDrainGrowth`，默认开）：存水 ≥ `itemDrainGrowthMinMb`（默认 200）
  时，每 `itemDrainGrowthIntervalSeconds`（默认 30 秒）在覆盖柱体 **y 与 y+1 两层**
  随机挑一株候选走原版骨粉三段式（`isValidBonemealTarget` → `isBonemealSuccess` →
  `performBonemeal`）；**生效才扣** `itemDrainGrowthCostMb`（默认 20mb，0=免费），
  扣水门 = `isBonemealSuccess` 通过。候选限**作物型** `BonemealableBlock`
  （2026-10-22 收窄：草方块/草丛/苔藓/菌岩/缠根泥土/海草/粉红色花簇等蔓延/装饰型
  不算——户外农田恒有草，算候选则「无作物停走」永不触发、抽中草白扣 20mb 零可见
  变化）。范围内无可催熟作物时**倒计时停走**（不上弦/击发点卸弦），作物出现后下一
  拍自动上弦；刚启动/复位后先上弦满一个周期才首催（防区块重载刷催熟）。生效时发
  原版 1505 成长粒子+骨粉音效；不走 `BonemealEvent`（靠骨粉事件联动的第三方模组
  感知不到本催熟）。扣水走 DOWN 面公开 capability（SIMULATE 先验，不足本拍不催不
  扣），**探头派生自实存水栈保数据组件**（2026-10-23 根因修复：`FluidTank.drain`
  按类型+组件匹配，YAT 纯度组件水对裸探头失配 →「cannot drain 10 mb」，倒计时照走
  但不催不扣、仅新存档犯病）；扣水后手动 `setChanged`+`sendData`（capability 排液
  不走 behaviour 的 updateFluids 同步）。已知限制：个别 `performBonemeal` 零世界变化
  仍计生效扣水（竹子顶端被挡/树苗长成失败），严格前后快照检测覆盖不全，不采用。
- N3 管道回注（`itemDrainPipeRefill`，默认开）：放开原版分液池两点限制——① 水箱
  `forbidInsertion`（管道只能抽不能灌）：`ItemDrainPipeRefillMixin` 每 tick 双向
  置位（开→`allowInsertion`/关→`forbidInsertion`），热切换两向下一拍生效；
  必须逐 tick 自愈的原因：`continueProcessing` 每次倒空流体物品后都会复位
  `forbidInsertion`，一次性放开会被物品加工悄悄关回。② 正上方无流体接口：
  `RegisterCapabilitiesEvent` 追加仅 UP 面生效的 provider（`BlockCapability` 查询
  「首个非 null 生效、null 落穿」，对 Create 自己的注册无影响），返回与侧面/底面
  同一 handler 实例。已知限制：加工流体物品的当拍存在 1-tick 关窗（同拍收尾复位
  晚于本头置位，下一拍自愈）；热切换不调 `invalidateCapabilities`，缓存「UP 面
  接口存在性」的消费者（如 Create 管道）热开启后顶面注水可能需一次方块更新才开始。
- N4 HUD（护目镜 + Jade，无独立开关，随 N1/N2 开关出行）：`ItemDrainHud.hudLines`
  双端共用同口径文案——灌溉行（水量>0「灌溉中」绿/否则「无水」灰）；催熟行
  （水量<阈值「水量不足 x/y mb」黄 / 计时中「下次催熟 X 秒」青，向上取整 /
  无候选停走不出行）。倒计时以节拍对齐的绝对击发 gameTime 经
  `ItemDrainHudSyncMixin`（`write`/`read` TAIL 注入，仅 clientPacket 写键、不污染
  存档）捎进 BE 同步包，客户端按 gameTime 差值平滑显示；上弦/击发/复位各分支均补
  `sendData`（含扣水/免费/无目标，防 HUD 停在旧值）。护目镜走
  `ItemDrainGoggleMixin`（`addToGoggleTooltip` RETURN，client 数组）；Jade 走
  `@WailaPlugin("create")` 插件（Create 缺席类不加载）+ server data provider 仅
  注视时同步，UID 即 Jade 设置开关项；水量不自建同步（Create `sendDataLazily`
  已 ≤8 tick 节流）。
- N5 YAT 纯度保留（无开关，create + yet_another_thirst 双在场才应用）：
  `WaterBottleDrainPurityMixin` 以 WrapOperation 包 `continueProcessing` 内
  `GenericItemEmptying.emptyItem` 调用——水瓶倒空产出的水附加 YAT 纯度组件
  （Create 内置药水倒空路径先于配方执行、原本丢弃纯度）。**这正是「新存档组件水」
  的来源**：生存模式水瓶倒水入池 → 池内水带纯度组件 → 旧裸探头抽不出（N2 根因）。
- N6 下界蒸发（`itemDrainNetherEvaporation`，默认开）：分液池位于**下界群系**时，
  池内存水按 `itemDrainNetherEvaporationMbPerSecond`（默认 50 mB/s，范围 0–1500，
  0=不蒸发）持续蒸发到完全排空；**只蒸发水**（`stored.is(Fluids.WATER)`，与保湿/
  催熟同一判据），岩浆等其它流体原样保留；管道回注与蒸发可同时进行（回注补多少
  扣多少，互不阻断）。驱动：`ItemDrainNetherEvaporationMixin` 挂
  `ItemDrainBlockEntity.tick` HEAD（服务端、10 tick 降拍），每拍结算
  速率×10/20 mB（默认 25 mB）；不足 1 mB 的余数跨拍累加，长期平均恰为配置速率
  （1 mB/s 每两拍扣 1 mB）；单拍最多扣当拍实存水量，排空即止、不会为负。扣水走
  DOWN 面公开 capability（SIMULATE 先验、探头派生自实存 stack 保数据组件、EXECUTE
  后 `setChanged`+`sendData`，与 N2 同口径）。群系判定 `level.getBiome(pos)`：
  原版 `#minecraft:is_nether` 或 NeoForge `#c:is_nether`；Sable 结构上的池子先经
  `SableWorldPosition.projectBlock` 投影到世界坐标（世界坐标幂等），叠加 Sable 本体
  `biome_projection`——列车开进旋转维度下界带时按当前世界位置判定并蒸发。
  关开关/离开下界群系立即停扣、余数清零（不补算离开期间的水量）。
- 运行验证：`IrrigationMathTest` 纯函数单测（覆盖边界/节拍换算/阈值/秒数向上
  取整）；GameTest——`ItemDrainPipeRefillGameTests`（默认批次
  `pipeRefillAcceptsFillOnAllFaces`：DOWN/侧面/UP 三面注水全收、同箱累加 1000mb；
  `pipe_refill_toggle`：`switchOffRestoresVanilla` 热关恢复原版——三面拒灌且 UP 面
  不再暴露流体接口）、`ItemDrainGrowthFireGameTests`
  （`item_drain_fire_grow`：注水击发扣默认 20mb（1000→980）且作物 age 增长；
  `item_drain_fire_components`：带 CUSTOM_NAME 组件的水击发恰扣默认 20mb——对旧代码
  必红的回归用例；`item_drain_grass_no_candidate`：仅草方块时倒计时停走）、
  `ItemDrainHudSyncGameTests`（`item_drain_hud_armed` 上弦写键值域 /
  `item_drain_hud_disabled` 关开关写 -1 / `item_drain_hud_no_target` 无目标写 -1 /
  `item_drain_hud_disarm` 击发点无目标卸弦）。
   `ItemDrainNetherEvaporationGameTests`（`item_drain_nether_evaporation` 批次：
   下界群系水按 25 mB/拍扣、主世界不扣、下界岩浆不扣、100 mB 小池排空不为负；
   群系用 `GameTestBiomes.overwrite` 改写并在 finally 恢复）+
   `ItemDrainNetherEvaporationSableGameTests`（`item_drain_nether_evap_sable`：池子组装到
   Sable 子层级后世界侧改下界群系，plot 上的池子按投影世界群系照样蒸发；create+sable 双在场）+
   `ItemDrainNetherEvaporationTest` 纯函数单测（默认 50→25/拍、1 mB/s 长期均值、
   余数跨拍、0/1500 边界）。

## 16. 板块 O：createfirefightingadd 缺失战利品表补齐（2026-10-23 数据注入）

> 来源：2026-10-23 用户报告「汲水器挖掘不掉落、扳手拆卸会消失」。反编译
> createfirefightingadd-0.2.1-beta.jar 核实：jar 内
> `data/createfirefightingadd/loot_table/blocks/` 缺 5 张方块战利品表——
> `water_intake`（汲水器）、`high_pressure_pump`、`cone_nozzle`、`flat_nozzle`、
> `bucket_controller`。挖掘与 Create 扳手拆卸（`IWrenchable.onSneakWrenched`）
> 都走 `Block.getDrops` → 空表 → 不掉落，属对方 mod 的发布缺漏。

- O1 口径：以本模组数据包路径同名补齐（`data/createfirefightingadd/loot_table/blocks/
  *.json`），格式照抄 jar 内现有 `fire_hose.json`——单 pool、掉自身物品、
  `survives_explosion` 条件；物品 id 与方块同名（jar 内注册名核实）。数据注入与
  对方日后补表不冲突（同名数据包资源按优先级覆盖，对方补了也只是同义替换）。
- O2 范围说明：用户只报了汲水器；同 bug 的另外 4 个方块（高压泵/锥形喷嘴/扁平
  喷嘴/水桶控制器）一并补齐——同一缺漏同源同修法，留着是已知坑。
- 运行验证：数据包加载即生效（无需代码路径）；实机待验——生存挖掘 5 方块各掉
  自身、扳手潜行拆卸返还物品（见「游戏内运行验证清单」）。
