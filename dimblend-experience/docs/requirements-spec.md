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

**生效范围（用户拍板）**：A1–A6 全部仅在 `dimblend:rotating` 维度生效；其他维度走原版逻辑。A7/A8 为全局功能，不受此限。

### A1 死亡保留物品、清空经验
- 在 rotating 维度死亡：主背包 + 盔甲 + 副手全部保留（等同强制 keepInventory，无视 gamerule）
- 经验等级、进度、总量全部清零；**经验球掉落同步取消**（v1.2"不能找回"：原版经验球走
  `LivingExperienceDropEvent`，与物品掉落的 `LivingDropsEvent` 是两条路径，只拦后者
  经验照掉不误——字节码语义核实后的补漏）
- A4（v1.2 备注）：用户标"未实现"，但开发侧已实现（tier 0 摘修饰符 + happy 粒子），
  记"已实现待实测"，以实机为准

### A2 重生点规则
- 有床（且可使用）→ 正常床重生
- 床遗失/被挡/失效 → 绝不落在世界出生点；改为在 `(死亡x不变, y=66, z=5)` 附近寻找安全落点重生
- 设计语境：dimblend 旋转维度的铁路走廊位于 Z=0、Y=64，该规则 = 回到走廊旁但保留 X 向进度

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

### A5 z256 常驻进度条（v1.3：bossbar 方案作废）
- bossbar 方案（顶部条、最后 32 格、方向门控）已删除（`CurseBossbar` 移除），
  改为客户端常驻 HUD 条，详见 `docs/z256-bar-requirement.md`（显示唯一依据）
- 位置：护甲 HUD 列（81px 宽，包裹 `ARMOR_LEVEL` 层绘制，读 `leftHeight` 无漂移）；
  常驻显示段内进度 `(|z| % 256) / 256`，数字为 `|z|` 取整
- 回程瞬时归零（实测修订，2026-09-22 锁存口径作废）：回程且段内位置严格 >128 的当刻
  强制 0%，转头前进立刻按公式恢复，无锁存记忆；回程前半段与去程一律按公式显示；
  颜色按最终进度：[0,60%) 绿、[60%,80%) 黄、[80%,100%] 红
- 数字隐藏（v1.3 新条目，代码已实现）：`|z|≤16` 时只画进度条、不画数字（`ZProgressHud.NUMBER_HIDE_RADIUS=16`，`absZ>16` 才绘制）
- 开关：复用 `curseBossbar`（Config 注释已同步）；与扣血逻辑互不读写，
  仅共用 128/256 常数
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

## 2. 板块 B：CDG 柴油机行为

- B1 加燃油启动：转速立即到 16rpm → 每 4 秒 +2rpm 阶梯爬升，直至额定转速
- B2 燃油烧尽 → 正常停机流程
- B3 达到额定后：在 80%~100% 额定区间随机跳变（实现拟每 1~3 秒取一次随机值，可调）
- B4 应力过载 → 爆机掉落（用户拍板变更，原"闩锁停机+重新加油重启"作废）：运转中过载当 tick 破坏方块、按战利品表掉成物品，油箱余油不返还；破坏失败（极端）回退闩锁逻辑
- B5 大型柴油引擎（v1.2 第二版新条目，用户标"未实现"）：热机爬梯 + 额定波动 + 过载损坏，
  与普通/组合式同口径（16/+2/4s，80%~100%/1~3s，爆机掉落余油不返还）。
  调速点 = 传给 `shaft.update(pos, dir, capacity, speed)` 的 speed 参数（ModifyArg）；
  过载信号源 = `shaft.isOverStressed()`（轴为 GeneratingKineticBlockEntity）；
  爆机范围 = **只炸引擎本体**（用户拍板），轴残留停转玩家自拆；状态复用 CdgEngineState 附件。
  原"巨型机不在覆盖内"的已知限制自本条起解除，进入实施。

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

## 4. 板块 D：CCA 电动马达

- D1 收到红石信号才开始工作，无信号停转
- D2 信号强度 1–15 线性映射目标转速：1 → 4rpm，15 → 额定（马达面板设定值）；
  **实时随动**（v1.2：原实现只在 4 个边沿传播点重算，稳态运行信号变化不跟——改每
  服务端 tick 重算，motorSpeed 变化超 ε 才调 `updateGeneratedRotation()` 传播，
  避免每 tick `sendData` 刷屏）
- D3 耗电下限：**面板 <4rpm 按 4 计**（v1.2 改回：纯线性保留，但 `|rpm|∈(0,4)→4` 钳定；
  无信号 0 转仍 0 耗）；`Math.max` 下限钳定向保留 bypass（4 的线性项仍低于原 8 下限）。
  映射侧不变：面板设定 ≤4rpm 仍按面板直通（如 2rpm→实际 2rpm，只耗电按 4 计）
- D4 设定转速 >64rpm 时，收到信号启动后循环播放运转声音（v1.1 口径：16→64）；
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
  `|speed|>0 且 |面板|>64`。接受边界：被更强动力源反拖时轴在转、循环照播；
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
  D3 钳定同步生效、无信号显示 0，自驱动下与服务端实扣完全一致（反拖场景
  显示跟轴速、实扣按 motorSpeed，同 D4 反拖边界）；② "应力量"行（Create
  `tooltip.capacityProvided`）原版即因客户端 `getGeneratedSpeed()` 恒 0
  （active/motorSpeed 不同步）而折算系数 ×0、恒显示 0 su，现对马达实例把
  折算读数替换为实际转速 → 显示 MAX_STRESS/256 × |实际映射转速|，与
  KineticNetwork 实际入网容量一致。两处均挂 `electricMotorBehavior`
  （关=透传原值）；client 侧 mixin（`ElectricMotorGoggleMixin` +
  `ElectricMotorGeneratorStatsMixin`，后者目标为 Create
  `GeneratingKineticBlockEntity.addToGoggleTooltip`，handler instanceof
  限定马达）。实际转速一律经 public `getTheoreticalSpeed()` 读取
  （mixin 继承字段 @Shadow 不受支持——findAliasedField 只解析目标类自
  声明字段，父类字段 shadow apply 期抛 InvalidMixinException，运行库
  sponge-mixin 0.15.4+mixin.0.8.7 fork 字节码核实）

## 5. 板块 E：Simurail

- E0 架构结论（已读源码确认）：列车以 **Sable sub-level 为单位整体运作**，转向架 `PhysicsBogeyBlockEntity` 以物理轮轴挂载其上；音效按整车口径播放（E2 整车单循环、E3/E4 整车广播；仅 E6 宽轨粒子仍按转向架粒度，与现有 rumble 一致）
- E1 Simurail **自带方块**（转向架/贯通框/自动车勾/探针读取器/遥控器/轨道等）防爆防拆，仅创造模式可编辑（用户拍板）
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
  **v1.8：仅 |visualSpeed| &gt; 4 m/s 时播放**（等于 4 不播；沿基线仍更新，
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
  世界坐标脚下（y-1）是幻纱宽轨（`railways:track_phantom_wide` 精确匹配，其他宽轨不播）时，在其 3x3 平面生成下落的末地烛粒子
  （`END_ROD` 加下坠初速，密度每 tick 每格约 20% 实施时调）；动静都播；
  与行进声同粒度（按转向架）；新开关 `wideGaugeParticles`（默认开）；
  宽轨方块 id 以运行实例 railways jar（0.2.0-beta.2）注册表核实为准（2026-09-23 用户条目：仅幻纱宽轨）
- E7 离结构传送（仅 rotating）：同维度任意车架 |visualSpeed| &gt; 4 m/s 时，
  每 10 秒（200 tick）以玩家世界坐标为中心探测半径 32 格内是否有任意 sable 子层级
  （`Sable.HELPER.getAllIntersecting` + 点到 AABB 欧氏距离 ≤32；先
  `projectOutOfSubLevel`）。都无则传送回重生位置——有效床/锚点且落在 rotating
  内走原版站立点（`findRespawnPositionAndUseSpawnBlock(keepInventory=true)`
  不耗锚点充能）；缺失/被挡/其他维度改走 A2 走廊旁，绝不弹世界出生点。
  **走廊条带 |z|≤16 视为安全落点**（dimblend 预生成 z ∈ [-16, 15]，无床落点
  z=5）：闸门仍开且附近无 sable 时下一周期不再 `teleportTo`，避免无床玩家
  每 10 秒抽搐/下坐骑。创造/旁观豁免；simurail 或 sable 缺席 fail-open。
  速度闸复用 `TrainBrakeBroadcast` 成员登记（与 E3/E4 同表，过期窗 40 tick）。
  新开关 `offStructureTeleport`（默认开）
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
- A9 虚空共存（用户拍板）：rotating 内 voidscape band 里 Voidscape `Insanity`
  的生命上限修饰符保留；我方 A3 只读写自有 id
  `dimblend_experience:far_curse_max_health`（代码已确认天然共存），叠加效果留实机验证；
  不做禁用虚空侧的 mixin

## 6. 横切约定

- 每个功能挂模组 Config 开关（默认开启）
- 所有 mixin 按目标模组存在性条件加载，单独安装本模组不崩
- **C 板块例外说明**：创造模式伪装方块为内容方块，注册期无法受 Config 控制，以 **Copycats+ 存在性**（ModList 守卫）作为加载条件；无配方、无战利品表，生存不可破坏
- 开发顺序：A → C → B → D → E
- 编译依赖对准运行实例 mods 内的实际版本 jar；参考源码 clone 到 `E:\misc` 同级目录
- **版本差注意**：clone 到的 CCA（1.21.1 分支 HEAD=1.7.1）新于运行 jar（1.5.10），且无对应 tag。写 mixin 前必须以运行 jar 的实际字节码为准（必要时反编译核对），clone 源码仅作结构导览；**CDG 例外**——B 板块已整体对齐到最新版 1.3.15（同级源码构建 jar，mixins 按源码口径编写，mods.toml 下限 `[1.3.15,)`），不再以旧运行 jar（1.3.11）字节码为准
- **构建环境**：本机默认 JAVA_HOME 为 JDK 11，每条 gradle 命令需显式 `$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`
- 音效素材由**用户提供**（用户拍板），我负责接入；素材到位前相关功能用占位实现，代码先行
- **已知接受风险（A1/A2 L4）**：第三方 mod 在本规则捕获后取消死亡事件会残留快照（onDeath 归一化已缓解大部分）；取消 LivingDropsEvent 会连带取消其他 mod 在该事件追加的掉落（墓碑类需组合实测）；附件序列化失败时掉落已取消，物品蒸发概率与原版存档损坏同级且有 ERROR 日志

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

## 10. 板块 G：新条目四系统（v1.5，一律仅 rotating 维度）

### G1 村民大师单次交易
- 村民生成时已有职业、或初次获得职业时：转职瞬间打新鲜戳（`setVillagerData`
  HEAD，定级不打、NONE 不打、开关关闭不打），rotating 内扫描只收有戳者；
  2026-09-24 新口径：rotating 内新生（刷怪蛋 / /summon / 繁殖 / 治愈 / 自然 /
  结构）由 `FinalizeSpawnEvent` 在转职前统一打戳——新生无业者扫描时随机指派
  职业（不含无业/傻子）后再收编；读档加载不走此事件，存量村民天然无戳、不追溯；
  傻子（nitwit）永不收编、婴儿跳过（长大后凭新生戳收编）
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

### G3 孤立源水降级（2026-09-23 新口径，旧人为水 SavedData 方案已移除）
- rotating 内凡经 `Level#setBlock` 写入的纯水源（water8 = `Blocks.WATER` 且
  `LEVEL=0`；桶/发射器、Create 管道、冰融化、流体 tick 自然成池均收敛至此），
  若其水平四邻中纯水源格数 <2 则改写为流动 water7（`LEVEL=1`，amount 7 非下落）；
  ≥2 才保留源水
- 含水方块既不降级（布尔含水无法表示 water7）也不计入四邻；世界生成
  （`WorldGenRegion`）不走此路径，天然水体不受影响；已存在源水不回扫
- 新开关 `isolatedWaterDowngrade`（默认开）

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

## 8. 实现覆盖对账（实现完毕时点）

### Config 开关清单（server 端 `dimblend_experience-server.toml`）

| 开关 | 板块 | 默认 |
|---|---|---|
| deathRules | A1/A2 | true |
| depthCurse | A3/A4 | true |
| curseBossbar | A5 | true |
| safeZone | A6 | true |
| globalBeacon | A7（全局功能） | true |
| nickname / nicknamePermission | A8（全局功能）/ 命令权限等级 | true / 0 |
| dieselEngineBehavior | B | true |
| electricMotorBehavior | D（含 D4 自定义音效替代 + D6 护目镜实际转速显示） | true |
| alternatorIdleDrain | D5（交流发电机无输入自放电） | true |
| simurailProtect | E1 | true |
| couplerRedstone | E5（true=红石信号不再断开车钩） | true |
| assemblerGuard | F1 | true |
| trainSounds | E2/E3/E4 | true |
| wideGaugeParticles | E6（纯客户端视觉） | true |
| offStructureTeleport | E7（离结构传送，仅 rotating） | true |
| trainLateralForce | E8（车架随机横向力，全维度） | true |
| villagerMaster | G1 | true |
| structureBed | G2 | true |
| isolatedWaterDowngrade | G3 | true |
| portalBan | G4 | true |
| rotatingDimensionId | A1–A6 作用域维度 id | "dimblend:rotating" |

### Mixin 清单（`dimblend_experience.mixins.json`，plugin 按模组存在性过滤）

| mixin | 目标 | 板块 | 条件（DimBlendMixinPlugin） |
|---|---|---|---|
| BeaconBlockEntityMixin | 原版信标 BE | A7 | 无条件（原版目标） |
| compat.cdg.DieselEngineRampMixin | CDG 普通/组合柴油机 BE | B | createdieselgenerators 在场 |
| compat.cca.ElectricMotorMixin | CCA 电动马达 BE | D | createaddition 在场 |
| compat.cca.ElectricMotorSoundClientMixin | CCA 电动马达 BE | D4 | createaddition 在场（client 数组） |
| compat.cca.AlternatorIdleDrainMixin | CCA 交流发电机 BE | D5 | createaddition 在场 |
| compat.cca.ElectricMotorGoggleMixin | CCA 电动马达 BE | D6 能耗行 | createaddition 在场（client 数组） |
| compat.create.ElectricMotorGeneratorStatsMixin | Create GeneratingKineticBlockEntity | D6 应力量行 | createaddition 在场（client 数组；handler instanceof 限定马达） |
| compat.simurail.PhysicsBogeyBrakeSoundMixin | Simurail 物理转向架 BE | E3/E4 | simurail 在场（simurail 硬性依赖 sable，蕴含 sable 在场） |
| compat.simurail.PhysicsBogeyLateralForceMixin | Simurail 物理转向架 BE（`tick` + `sable$physicsTick`） | E8 | simurail 在场 |
| compat.simurail.PhysicsBogeyTrackSoundMixin | Simurail 物理转向架 BE | E2 | simurail 在场（client 数组） |
| compat.simurail.PhysicsBogeyWideGaugeParticleMixin | Simurail 物理转向架 BE | E6 | simurail 在场（client 数组） |
| compat.simurail.AutomaticCouplerRedstoneMixin | Simurail 自动车钩方块 | E5 | simurail 在场 |
| compat.copycats.CopycatsObsidianHardnessMixin | Copycats+ 两根基类 | C0 | copycats 在场 |
| compat.create.CreateCopycatObsidianHardnessMixin | Create CopycatBlock 根 | C0 | create 在场 |
| BlockPropertiesAccessor | 原版 Properties（硬度豁免判据） | C0 | 无条件（原版目标） |
| VillagerMasterMixin | 原版村民（补货/掉职业拦截） | G1 | 无条件（原版目标） |
| VillagerAccessor | 原版村民（交易补全入口） | G1 | 无条件（原版目标） |
| MerchantOfferAccessor | 原版交易条目（maxUses 改写） | G1 | 无条件（原版目标） |
| WaterSourceDowngradeMixin | 原版 Level#setBlock（孤立源水改写位） | G3 | 无条件（原版目标） |
| compat.dimblend.WarpGateBlockMixin | dimblend 折跃门（字符串目标） | G4 | dimblend 在场 |
| client.ItemStackNicknameMixin | 原版 ItemStack | A8 | 无条件（原版目标，客户端侧） |

事件处理器（非 mixin）：DeathRules（A1/A2，含 PlayerRespawnPositionEvent）、DepthCurse（A3/A4）、CurseBossbar（A5）、SafeZoneSpawnGuard（A6）、SimurailBlockGuard（E1）、TrainOffStructureRules（E7）、PhysicsAssemblerGuard（F1）、CreativeCopycatCreativeTab（C）、NicknameCommand/NicknameSync（A8）、VillagerMasterRules（G1，含职业记录附件/restock取消/掉职业恢复）、StructureBedGuard（G2）、PortalBan（G4）。

### 依赖接线（C 板块例外为版本级对齐）

| 目标 | 对齐方式 |
|---|---|
| Create 6.0.10 | maven.createmod.net `6.0.10-281`（版本级对齐） |
| Copycats+ 3.0.4 / CCA 1.5.10 / Simurail 0.0.0-a / Sable 2.0.5 | 运行实例 jar 原样复制（字节级对齐，compileOnly） |
| CDG 1.3.15 | 同级源码 `E:\misc\Create-Diesel-Generators`（1.21.1 分支 HEAD=1.3.15）构建 jar（compileOnly） |

### 已知限制（在案）

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

### 游戏内运行验证清单（待实机，当前仅构建通过）

- A 板块：A3-A5 冒烟 ①-⑨；A1/A2 三条 H1 场景 + A2 落点实测（窗口 61~71）+ 墓碑类 mod 组合；A6 worldgen 实测（含 tag 补强——钻头/锯/机械臂拦截实测）；A8 tooltip/命令实跑
- B：点火爬梯实测（16→+2/4s→额定→波动 76.8~96）；过载闩锁与重新加油触发；双开关矩阵（depthCurse 关 + curseBossbar 开）
- C：粱蓝图打印、物品栏显示、生存不可破坏、扳手只撕材质
- D：红石 1/8/15 档转速映射实测、能耗纯线性（4~7rpm 不再按 8 计）、无红石停转、负面板对称映射、CC setRPM 路径
- E1：生存玩家交互全拦、爆炸不毁、创造可编辑、机器钻头 tag 拦截实测
- **E2-E4/D4 音效**：列车行驶循环随速度、刹车/松闸触发音、马达三态音实机听音验证（启动验证已完成：三个新 mixin 应用成功、0 ERROR 到主菜单、6 ogg 全进 jar）
- v1.1 增补实机项：C0 全体伪装硬度（挖原版/创造变体手感+爆抗）、C3 伪装板（蓝图/物品栏/生存不可破坏/扳手撕材质）、F1 组装器（生存禁摆无提示+创造可摆）、E5 车钩红石不断开（贯通框仍断开）、D4 64rpm 阈值、E2 新映射听感（2/10/16 三点音调+5秒淡入淡出）、A9 虚空 band 内诅咒与 Insanity 叠加上限显示
- 模型装饰器实机视觉、列车移动 BE 同步、mixin 运行时应用（启动日志确认）、mixin plugin 过滤（卸载 CCA/CDG 后日志确认）
- 启动验证（2026-09-19，TrainTripWorld 实例实测两轮）：mod 加载、common setup、全部 mixin 应用（defaultRequire:1 下存活即实证）、0 ERROR/FATAL 到主菜单；期间修复 NeoForge 版本范围硬阻塞（[21.1.251,)→[21.1.249,) 解耦）与 example 脚手架残留
- 启动验证（2026-09-23 v1.7 批次，dev runClient + CCA 临时 localRuntime）：createaddition 1.5.10 加载、CCA Initialized、Sound engine started 到主菜单，0 ERROR/FATAL/InvalidMixin；本批 4 个 mixin（SoundClient 改版 + AlternatorIdleDrain + Goggle + GeneratorStats）随 CCA BE 注册完成 apply；dev 环境 mixin 库为 sponge-mixin 0.15.2+mixin.0.8.7（fabric fork）。实机听音/看数仍在待办

### 复核闭环状态（截至第 19 轮）

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

**审计链更正**：D 板块的"最小正确结构 6 条"提炼自 B 复核报告的经验教训（name-only target、ServerLevel 守卫前置、跃迁 setChanged、附件 codec 等），不宜记作复核侧"D 板块指引"；D 第 1 轮验证载荷曾误发 B 复核 agent（未达），已重发至 D 复核 agent（`adcc6814`）并确认送达。

复核教训沉淀：① javap 缩写注释（无 owner 前缀的 Methodref）≠ invokeinterface——@At target 写 owner 前需 -v 常量池实读；② SERVER 配置在专用客户端不加载，任何双端方法读 SERVER config 前必须 ServerLevel 守卫前置；③ 转速映射类逻辑的"当前值 vs 原始额定"判据必须用原始额定（首轮抽取后当前值恒低于额定）；④ 无 serializer 的附件不跨区块卸载——需要跨卸载的状态必须 serialize；⑤ **父类声明的 BE 方法不能用类级 HEAD @Inject 注入（Mixin 0.8.5 仅遍历目标类 methods）——必须调用点级 INVOKE 注入**；⑥ panel 类映射的源必须是权威设定值（ScrollValueBehaviour.getValue），不能是已映射的运行值（getRPM 读 motorSpeed 会反馈坍缩）。

## 7. 决策记录

### libs jar 清单（5 个 addon 编译依赖）

> 2026-09-25 单仓更新：开源三件（Copycats+ / CCA / CDG）与 sable-companion 已改走
> Maven 定点版本（见 `dimblend-experience/build.gradle`、`dimblend-blocks/build.gradle`），
> 不再需要本地 jar；`libs/` 仅剩无公开 Maven 的 simurail / sable cui-modded。
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

依赖形态：`compileOnly files(...)`（编译可见、不进 runtimeClasspath、不进 POM）；
需要运行测试时按需把相关 jar 临时提升为 `localRuntime`。

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
