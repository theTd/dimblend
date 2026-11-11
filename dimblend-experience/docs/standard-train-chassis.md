# 标准列车底盘

## 已确认玩法

- 仅 `dimblend:rotating` 启用。驾驶台只用于生成底盘，不保留驾驶用途；必须放在天然主线的宽轨上。其它世界不启用此规则。
- 两种底盘总长45格、宽5格，车板长41格。驾驶台在第23排正中间；图纸01→45排沿X负→正方向，全部方块与驾驶台同高。所有伪装件保持无材质。
- 普通伪装板位于方块上沿，与上半砖顶面齐平；对应Create方块状态为 `facing=down`，其形状占Y=13/16…1。`facing=up` 实际位于下沿，不用于底盘。
- 动力底盘：4个可见小型宽轨标准转向架、2个隐形转向架和12个创造传动杆，分为两组。
- 转向架默认参数（2026-10-31 新增；6 个车架按 X 从大到小 = +17/+14/+11/−11/−14/−17）：第 1、6 个（±17）驱动强度 32.0；第 2–5 个（±14、±11）转向架框位移「允许」、驱动强度 0.0。车厢底盘两个转向架全部驱动强度 0.0，位移保持默认「禁止」。只影响新打印的底盘。
- 车厢底盘采用最终更正版：只有第06、40排两个可见转向架；04、05、07、08、38、39、41、42排中间为创造模式洁净伪装台阶；第09、37排为“半 净 净 净 半”。所有“净”共14块，使用 `dimblend_blocks:creative_clean_copycat_slab`，状态 `axis=y,type=top`，占Y=1/2…1。没有隐形架。“不可接入动力”仅作为描述，不做额外改装限制。改动仅影响新生成的底盘，已有底盘不自动替换。
- 站场侧线底盘与出生点动力底盘（2026-11-02 起）：外观用车厢底盘图纸，装配时以 `driveEnds=true` 配置转向架——两个 ±17 车架驱动强度 32.0，牵引力与动力底盘相同。打印出的车厢底盘仍全部 0.0，不受影响。只影响之后新建的底盘，已有底盘不自动替换。
- 场地检查为X±24、Y±1、Z±3，包含边界，共49×3×7格。忽略起点驾驶台和轨道，其余世界方块必须为空气；Sable实际方块的投影也不能占入。使用已有 `VoidFitVoxelizer` 的旋转保守覆盖口径。
- 不合规驾驶台破坏并掉落；全服在线玩家收到快捷栏上方消息“需要放置在宽轨上”或“附近不空旷 不安全”。合规时广播“右键选择底盘类型”。
- 右键归属（2026-11-10，阶段1）：主手空手或 Create 扳手**普通右键**打开选择页；潜行一律放行（扳手拆卸仍归 Create）；副手永不触发；其他物品放行。适用条件（驾驶台、`dimblend:rotating`、天然主线、8 格内、玩家可交互）不满足时不取消事件，避免吞点击；服务端只有真正建立会话后才取消（2026-11-11 复核：物品→触发器映射收进 `ChassisInteraction.menuTrigger`，避免映射散落在事件体里被改成“非空即触发”而吞掉放置/使用）。
- 客户端适用条件（2026-11-10，C4）：不猜 `TrackBlock`；用服务器推送的当前 band lane + band size 反推与服务端 `CorridorRails` 相同的 per-delegate 轨道 id。只有**驾驶台与玩家同一 band region**且 lane 已知时才取消；跨 region、`unknown` 或未同步时返回 false（不取消，服务端决定），避免用过期 lane 吞掉一个本应放行的放置。
- 右键打开两种选项。第一次点击后提示“该操作不可逆 请谨慎考虑 稍候”、选项禁用5秒，再显示“再次点击 确认打印”。可随时退出；重开重新选择、重新计时。服务端验证会话、距离、冷静期及场地；多人重复确认只成功一次。
- 性能优先：每6 tick铺一排，约13.5秒完成，伴随捡物品音效；每tick施工预算约2ms，最多8处同时施工。服务器卡顿可延长工期。只组装图纸中的方块，不收集轨道或周围建筑；整车通过Sable持久强制加载。
- 施工进度落盘。区块暂未加载时暂停并请求临时加载；重启后继续。施工期间遇到障碍或图纸方块被改动，清除仍与图纸一致的已生成方块并退回一个驾驶台；不覆盖玩家新放的障碍。

## 仅新世界的出生端设施

通过首次创建世界的出生点事件登记世界版本；旧世界无标记，不补墙、不删轨道。

- X=-16、Z=-7…7、Y=64…70：15×7×1基岩墙。
- (-16,64,0)：点亮的玫瑰石英灯；其上一格**留空**（原箱子位置）。
- 出生点**不再提供**装列车驾驶台的箱子：R7 取消箱子，`StarterDepot.cellAt(1)`/`cellAt(2)` 恒为 AIR，仓库里没有任何发放逻辑（2026-10-31 曾随 R6 暂缓恢复，11-01 随 R6 定稿再次取消）。老存档不回溯。
- 主线X=-256…-17不生成轨道；X=-16由墙/灯占据，-257及更负方向保留原有主线。

## 配方

旋转维度内、真实世界方块坐标 `|Z|>32` 的铁道部件挖掘掉落时，原方块物品替换为1个列车机壳，并禁止潜行扳手拆卸；普通扳手旋转不受影响。`Z=±32` 及以内、其它世界保持原行为。Sable 方块先投影回世界坐标判定，不使用内部储存区坐标。创造模式仍按原版不产生挖掘掉落，容器额外内容不替换。

清单按当前整合包实际使用列车机壳的方块配方核对：驾驶台、车站、信号机、列车侦测器、列车门、列车活板门、汽鸣铁道便携燃料接口与轨道车钩。由 `dimblend_experience:railway_casing_recoverable` 方块标签维护，可由数据包扩展；两个汽鸣铁道条目为可选依赖。原本没有合法方块掉落的情况不额外生成机壳，避免双格门或无掉落破坏方式重复产物。

在 `dimblend-craft` 既有配方编辑器内删除以 `create:railway_casing` 为原料的所有配方，包含标签原料、机械加工和序列组装内部原料；**例外：`create:crafting/kinetics/train_door` 与 `create:crafting/kinetics/train_trapdoor` 两条按配方 ID 豁免、保留原配方**（2026-10-31，R10）。不因产物是机壳而删除配方。原料判定在本轮标签绑定后的服务端事件执行，沿用真实 `Ingredient` 谓词，保留差集/交集语义。全游戏生效，启动及数据包重载走同一规则。

## Rapier 世界锚探针（阶段1，未实机验证）

目的：在**测试存档**里对指定的标准车架验证“null 端映射 ground”的真实世界锚与关节帧，再决定是否把“锁垂向/pitch/roll、保留 xz/yaw”的锁接入生产自动 tick。阶段1只提供手动探针与 GameTest 入口，**不接入生产自动 tick**。

**只在测试存档、只在指定的测试车上使用**；`hold` 会短时真的约束那台车，不要在正式存档或载客列车上试。

命令（权限 2，目标用 UUID 或 `nearest` 取 32 格内最近的一台）：

```text
/dbx chassis probe start <uuid|nearest> [seconds] [read|free|hold]
/dbx chassis probe status
/dbx chassis probe dump
/dbx chassis probe stop
```

- `read`：不建 joint，只按 10 tick 一次采样，记录物理引擎（Rapier）真姿态、logical pose、plot/world anchor、实际高度与 yaw/pitch/roll、轮 pivot 高度、body runtime id、句柄有效性。
- `free`：建一个无锁定轴的 generic joint，验证 `bodyA == null` 到 Rapier ground 的映射与关节帧方向，不约束运动。
- `hold`：建一个锁 `LINEAR_Y, ANGULAR_X, ANGULAR_Z` 的 generic joint——这是“锁垂向/pitch/roll、保留 xz/yaw”的候选帧；用短时 verticalError / pitch / roll 漂移验证世界锚（xz 与 yaw 自由，`anchorDrift` 的水平分量含正常行驶）。它是探针，不是生产锁。
- `free`/`hold` 在停用、超时（最长 60 秒）、目标移除、句柄失效或服务器停止时销毁 joint；`isValid()` 仍为真则进入每 tick 重试。**只有 `isValid() == false` 才记 `jointRemoved=true`**；单独记 `jointRemoveAttempted`，不把“调用过 remove”伪称已移除。
- 失败重试保留，但日志指数退避：2/4/8/…/1024 tick，之后每 1200 tick（约 60 秒）一条 ERROR；不会每 10 秒永久刷 ERROR。
- 采样限频，不做全局每 tick 扫描；目标只在启动时解析一次。

源码取证（本机）：

- `E:\misc\sable\sable_rapier\src\main\java\dev\ryanhcode\sable\physics\impl\rapier\RapierPhysicsPipeline.java:543-573`：`addConstraint(bodyA, bodyB, configuration)`，注释明确 `bodyA == null` 表示把第二体约束到世界。
- `...\constraint\generic\RapierGenericConstraintHandle.java:27-57`：`bodyA == null ? -1 : getID(bodyA)`，锚点/帧原样下传。
- `E:\misc\sable\sable_rapier\src\main\rust\rapier\src\joints.rs:633-644`：`id_a == -1` 映射 `scene.ground_handle.unwrap()`。
- `joints.rs:660-665`：`GenericJointBuilder::new(locked_axes)` + `local_frame1/2.rotation`，锁定轴是**关节帧轴**；因此探针用真实发动机姿态求世界锚，关节帧取“只保留偏航”的世界帧，而不是把 `ConstraintJointAxis` 当世界轴。

限制与未验证：

- Simurail 的 `PhysicsBogeyBlockEntity#pivotPose` 没有公开 getter；探针用一次反射读取，失败时回退到转向架方块中心，并在记录里写 `source=pivotPose|blockCenter`。这条反射只用于诊断。
- `hold` 的关节帧在启动时按当时偏航建立。固定帧的 X/Z 轴在水平 yaw 下仍张成水平面；锁 `ANG_X/ANG_Z`、放 `ANG_Y` 的集合不随 yaw 改变，**不需要**每 tick 用 `setFrame1/2` 重锚，也不需要按区间重建。本阶段仍只验证静止/近直线测试车；phase1 的 `bodyFrame=inverse(currentBodyRot)*worldFrame` 仍有意保留初始 tilt，不能证明正式水平锁。
- 探针锚点取**当前** plot 中心方块中心，只用于验证机制；它不是阶段2 的轨高基准。阶段2 必须持久化正常轨高与蓝图地板基准，**不捕获已下沉的当前 pos**。
- **plot center 是装配 anchor，不是质心**：hold 关节的 joint impulses 带力臂，只能当“力臂观测”，不能当纯力；判断锁是否有效看 `verticalError` 与 pitch/roll 角度漂移。
- 阶段1 不改质量、驱动、应力、制动，也不每 tick teleport；脱轨/拆解时的释放属于阶段2 的正式锁，未实现。
- 车轮独立 pivot 与 dimblend 既有的 `PhysicsBogeyAxleLockMixin` 本阶段未改动；探针只记录轮 pivot 高度，穿轨/锁轮验收仍待阶段2 实机。
- 2026-11-10 仅完成代码与静态审查；**未编译、未跑单测/GameTest、未实机**。世界锚是否真实稳定、轮 pivot 读数是否正确，必须等测试存档里的 `hold` 探针结果。
- `ChassisLockGameTests` 属于阶段2，本阶段未创建。

## 标准车架身份登记（阶段2 纯数据前置，未接约束）

- 必要骨架识别 `ChassisSkeleton`：只认 `H/D/B/P/W/I`，忽略 `N/S/C`、上装与装饰；W/I 转向架位置与类型要求精确一致；绕 Y 试 0/90/180/270。蓝本在 180° 下对称，因此同一辆车通常返回 q 与 q+2 两个朝向，由调用方选端别，不猜死前端。输入坐标以装配 anchor（plot center）为原点。
- 身份登记 `ChassisRegistry` + `ChassisRegistryData`（SavedData `dimblend_chassis_registry`）：持久化 UUID、blueprint、**显式给出的校准标称高度**、生命周期。脱轨/卸载只改生命周期，身份与高度保留；只有真拆解才删。API 没有“当前位置”入参，**不从已下沉 pos 采样标高**；不建 joint、不改质量/驱动/应力/制动。
- 命令（权限 2，仅在 `dimblend:rotating`）：

  ```text
  /dbx chassis register <uuid> <POWER|CARRIAGE> <nominalHeight>
  /dbx chassis calibrate <uuid> <nominalHeight>
  /dbx chassis unregister <uuid>
  /dbx chassis registry
  ```

  `register`/`calibrate` 只写身份数据，成功回执明确写“未启用高度约束”；`calibrate` 只改标称高度并保留生命周期；`unregister` 只供真拆解，脱轨/卸载不要用。
- 边界：本阶段**没有**自动识别扫描、没有把登记接入生产高度约束、没有每 tick teleport；稳定 10 tick 建锁、旧车校准、世界锚验证门都仍是后续阶段。代码尚未编译/跑单测/实机。

### COM 修正（2026-11-11，仅 probe）

- **native 读回的是 pose.position，不是 rotationPoint 已补正的 anchor**：实际 `sable-neoforge-1.21.1-2.0.5_cui-modded.jar` 的 Rust `Rapier3D.getPose` 返回 `rb.translation() + origin`（`sable_rapier/src/main/rust/rapier/src/lib.rs:645-687`）；`createSubLevel` 把 body translation 设为传入的 `pose.position`（`lib.rs:796-805`），没有额外加 COM 或 plot 原点。`RapierPhysicsPipeline.add` 在添加时单独把 `logicalPose.rotationPoint` 设为 COM（`RapierPhysicsPipeline.java:206-209`），但 `readPose` 只 set position/orientation（`307-314`），不 set rotationPoint。
- **实际 jar 的 Pose3d**：`new Pose3d()` 的 rotationPoint 是零向量；`Pose3dc.transformPosition` 字节码为 `(p - rotationPoint) * scale` 后旋转再加 `position`。所以旧 `enginePose.transformPosition(rawPlotAnchor)` 实际是 `position + R*raw`，漏了 `-R*COM`。
- **native joint 的 body-local anchor**：Rust `joints.rs:109-126` 把 `pos_b - center_of_mass_2` 作为 local anchor；`pos_b` 是 raw plot 点。正确世界锚是 `position + R*(rawPlot - COM)`。这里只减一次 COM；因为 `getPose` 没有加 COM，不存在双减。
- **probe 修正**：`ChassisPose.anchor` 显式接收 COM；start 与每次 sample 的 worldAnchor 投影都走同一公式。COM 为 null 或非有限时 `invalid_com` fail closed，不猜零。Report/Sample 都记录 COM；采样时 COM 与 engine pose 在同一 server tick 读取，COM 变化只作为读时观测限制，不是跨帧回放。
- **回归测试**：`ChassisPoseTest.anchorSubtractsLargeNonZeroPlotCenterOfMassLikeNativeJoint` 用 raw `(0,128.5,0)`、COM `(0,128.0,0)`、bodyY=65 和非零旋转，断言结果等于 native 的 `R*(raw-COM)+bodyPos`；旧 no-COM 结果必须相差 >1 格（测试会红）。
- **边界**：这是 probe 诊断坐标的修正，不是正式水平锁；它**不能**证明 deck 标称 66，也不能把 phase1 的初始帧重合解释为水平锁。正式 frame2/nominal 仍等 `hold` 实机证据。

## 阶段2 设计（待 probe；未接生产 tick）

> 状态：本轮只做源码取证与设计，不改生产 Java/测试，不构建。正式登记接线、高度锁和生命周期钩子都必须等 `hold` 实机证据与串行 token；probe 通过前不接生产 tick。

### 已核源码事实

- **约束 frame 的语义**：`GenericConstraintConfiguration` 明确 `orientation1/2` 是“关节帧在第一/第二 sub-level 上的局部朝向”（`common/.../GenericConstraintConfiguration.java:16-17`）。`Rapier3D.addGenericConstraint` 的原生参数名为 `localAnchor*/localOrientation*`（`sable_rapier/.../Rapier3D.java:434-451`）；`joints.rs` 对第二体直接写 `local_frame2.rotation = rotation_b`（`joints.rs:664-665`），位置则用 `pos_b - center_of_mass_2` 求 local anchor（`joints.rs:112-126`）。所以 **frame2 是车体局部帧**，phase1 的 `bodyFrame = inverse(currentBodyRot) * worldYaw` 确实会让初始帧重合。
- **初始帧重合会保留初始俯仰**：令 `R_b = Ry(yaw)·Rx(pitch)·Rz(roll)`，phase1 取 `F_w = Ry(yaw)`、`F_b = R_b⁻¹·F_w = Rz(-roll)·Rx(-pitch)`。锁定后的目标关系 `R_b·F_b = F_w` 在任意初始 pitch/roll 下都成立，因此 **初倾斜被烘进 frame2 并保持**，不是水平锁。
- **正式 frame2**：标准世界装配的块状态不旋转（`AssemblyTransform` 用 `Rotation.NONE`；`SubLevelAssemblyHelper` 只在**已存在的 sub-level 内**装配时复制 `containingPose.orientation`，标准主线路径没有这一步；`.../SubLevelAssemblyHelper.java:75-101,107,538-546`）。因此 `B` 取**装配瞬间的标准蓝图/车体局部水平基**，不是当前沉车姿态；若标准路径的 pose 默认朝向为单位阵，则 `B=I`，该默认值需首次编译/探针确认，不能猜。取 `F_b=B`、`F_w=Ry(yaw)·B`，目标关系强制 `R_b·B = Ry(yaw)·B`，即 `R_b=Ry(yaw)`，pitch=roll=0。初始倾斜是误差，不是标准；不得把当前姿态乘进 frame2。
- **标称 deck 与 raw plot 点**：`ChassisConstruction.start` 要求控制台下方是 `OakTrackCorridor.TRACK_Y=64` 的天然轨道（`ChassisConstruction.java:37-42`；`OakTrackCorridor.java:26-27`），所以控制台 `center.Y=65`，蓝图块层在 65，顶面为 `TRACK_Y+2=66`（`ChassisBlueprint` 的 H/D/P 顶面与 `ChassisBlocks` 的 slab/panel 状态一致）。装配把世界 anchor 中心映射到 `plot.getCenterBlock()` 中心（`SubLevelAssemblyHelper.java:65-68,106-109`；`AssemblyTransform.apply` 为 `(world - anchorCenter) + plotAnchorCenter`，`538-546`）。因此某蓝图格 `(x,z)` 的**原始 plot deck 点**（块顶面）是：

  ```text
  p_deck = plotAnchor.getCenter() + (x, 0.5, z)
  world   = pose.transformPosition(p_deck)   // 与 probe 的 Pose3d 用法一致
  ```

  `getCenterBlock()` 的 Y 是 plot 建造高度中值（`LevelPlot.java:130-139`），**不是**车高；只能作为 raw plot 原点。绝对标称 deck 高度由 `TRACK_Y+2=66` 给出，绝不能读当前沉车/plot 的 Y 反推。
- **轮对轨道约束不属于本设计**：Simurail `PhysicsBogeyAxle` 继续拥有 track joint；`PhysicsBogeyBlockEntity.isDerailed()` 只由两端 `hasTrack()` 判定（`PhysicsBogeyBlockEntity.java:875-881`），`PhysicsBogeyAxle.updateTrack` 在无 segment 时自行 `removeJoint()`（`PhysicsBogeyAxle.java:270-275`）。轮对采样/验收单独做，不改 mass/drive/stress/brake，也不改该 mixin。

### 挂接点设计（未实现）

- **新装配登记**：`ChassisConstruction.assemble` 在 `assembleBlocks` 成功后、`finish` 前（当前 97-104 行）调用 identity service；`StationDepotAssembly.assemble` 在成功后（当前 319-334 行）同样调用。登记值：`car.getUniqueId()`、蓝图、`center.getY()+1=66` 的标称 deck；**不**读当前 pose。`SubLevelObserver.onSubLevelAdded` 也会覆盖载入/重载，但新装配必须有明确入口，不能只靠通用观察者猜。
- **生命周期**：`SubLevelObserver` 已有 `onSubLevelAdded` / `onSubLevelRemoved(subLevel, reason)` / `tick(container)`（`api/sublevel/SubLevelObserver.java:9-34`）；`SubLevelRemovalReason` 只有 `UNLOADED`（不丢占位）和 `REMOVED`（清占位）（`storage/SubLevelRemovalReason.java:8-18`）；`SubLevelContainer.addObserver` 公开（`SubLevelContainer.java:203-205`），移除前回调（`512-519`）。设计：
  - `onSubLevelAdded`：已知 UUID = 重载/再现，释放旧 constraint、标记 `UNLOADED`/待复核，**不立即建锁**；未知 = 入有界识别队列。稳定 10 tick 后的 ACTIVE/锁仍是后续门。
  - `onSubLevelRemoved(UNLOADED)`：释放临时/未来 constraint，保留身份与标称高度，标 `UNLOADED`。
  - `onSubLevelRemoved(REMOVED)`：释放 constraint，删除身份（真拆解/车已消失）；注意 `REMOVED` 也覆盖 mass-invalid/plot 被破坏，源码只有这两个 reason，不能区分“玩家拆解”与“引擎移除”；若身份必须保留，需要后续更细证据或手动 re-register，不能假装能区分。
  - 观察者按 rotating level 注册一次；启动时只对 `getAllSubLevels()`（仅已加载）做一次入队，之后只在 `onSubLevelAdded` 入队；禁止每 tick 全量扫 plot。
- **旧车有界识别**：每 N tick 至多处理一个已加载 plot；只读该 plot 的骨架格（`H/D/B/P/W/I`），不扫全 plot、不触发 chunk load。`ServerSubLevelContainer.getAllSubLevels()` 只含已加载项（`SubLevelContainer.java:476-478`）；plot 内块读取用已加载的 `LevelPlot`/embedded accessor，识别失败/歧义就保持未登记。
- **解除/脱轨**：Simurail 没有 derail 事件；未来锁控制器只能在已登记车的有界 tick 中采样 `bogey.isDerailed()`，false→true 立即移除世界锚 constraint。不得把轮轨 joint 的移除当世界锚释放，也不得改 Simurail 的轮对约束。
- **单参数 register fallback（未来）**：`/dbx chassis register <uuid>` 只接受已加载的 `container.getSubLevel(uuid)`；扫描该 plot，要求唯一骨架；蓝图匹配后使用 `TRACK_Y+2` 标称 deck；不要求 blueprint/height 参数。未加载、无骨架、两个蓝图歧义都 fail closed。当前三参数 `register <uuid> <blueprint> <height>` 只作过渡/调试，最终口径待 hold 后定；单参数不得在未加载 plot 上猜。
- **测试车 fixture（现有自动路径，不新增工具）**：
  - 新世界唯一 power chassis：`StationDepotGeometry.POWER_CHASSIS_X=32`、`POWER_CHASSIS_Z=OakTrackCorridor.CORRIDOR_Z=0`、`chassisY()=TRACK_Y+1=65`（`StationDepotGeometry.java:114-115,277-280`；`StationDepotAssemblyData.powerCenter()` `287-289`）。触发条件：`RotatingChunkGenerator.hasStarterDepot()` 为真（`StationDepotAssembly.handOutPowerChassis` `496-499`）、玩家距中心 `TRIGGER_RADIUS=64` 内（`StationDepotAssemblyRule.java:22,51-65`）、站点区块加载（`ChassisSite.loaded`）、`ChassisEvents.available()` 全依赖满足。每 tick 处理 2 个站点，先 BUILD 后 ASSEMBLE；无 `/dbx` 生成命令。
  - 可执行入口（先传送到安全侧，避免站在车体内；z=8 在 19 宽走廊内、骨架 z±3 外）：

    ```text
    /execute in dimblend:rotating run tp @s 32 66 8
    ```

    站几 tick 等自动 build/assemble；再用现有 `/dbx chassis probe start nearest 10 hold` 选最近车。若 `hasStarterDepot()` 为假、车已被取走或站点已 assembled，则此入口不再生成第二辆；不要猜 depot boundary。
  - 若 X32 没有 power 车，现有手工路径是在 `(32,65,0)` 由玩家放置 `create:train_controls`，等 `ChassisEvents.onPlace` 校验后右键选择 POWER；约 13.5 秒完成。不要用 `/setblock`，它不触发 `BlockEvent.EntityPlaceEvent`，不会进入现有装配流程。
  - 边界 depot 车厢（备选，不保证本世界有）：`sidingCenter(boundaryX)=(boundaryX,65,10)`（`StationDepotAssemblyData.java:280-284`；`carriageChassisX=boundaryX`、`carriageChassisZ=10`、`chassisY=65`）；要求 `boundaryX != 0`、`boundaryX % bandSize == 0`、相邻两个 region 都是 surface（`StationDepotSites.buildsDepot`），且 carriage budget 未满。bandSize/seed 布局决定哪个边界，不能无 seed 猜一个坐标。
- **既有 `/dbx` 命令证明**：`ChassisProbeCommand` 只注册 `dbx/chassis/probe` 的 start/status/dump/stop 和 register/calibrate/unregister/registry（`ChassisProbeCommand.java:35-75`）；`start` 只解析已有 sub-level（`ChassisWorldAnchorProbe.resolve`），`register` 只写 `ChassisRegistryData`；没有任何 build/assemble/depot 触发命令。其他 `/dbx` 根（census/tuning/nickname/transmitter）与此无关。故 live 只能传送触发 depot 自动装配，或手动放置驾驶台走现有选择流程，不能声称某条 `/dbx` 会生成测试车。

### 待 probe 的设计门

1. `hold` 必须实测：世界锚 joint 的 pos1/pos2 与 frame2 是否真按上述局部帧作用；初始沉车倾斜是被纠正还是被保留；轮 pivot 高度是否与轨道/轮对一致。
2. 验证 `TRACK_Y+2=66` 与实测 deck 的偏差；偏差未解释前不得改用沉车 Y。
3. 验证 `SubLevelObserver` 的 `UNLOADED`/`REMOVED` 在真实卸载、重载、拆解中的顺序与重复性；通过前不写生产生命周期钩子。
4. 验证 `isDerailed()` 轮询释放不会与 Simurail 的轮轨 joint 移除竞态；轮对采样单独验收。
5. 通过后再决定稳定 10 tick 门、旧车受控校准和 frame2 的实现文件；未通过不接生产 tick。

### 未来新增文件（只设计，不创建）

- `chassis/ChassisNominal.java`：纯 deck/raw plot 点变换 + 正式 frame2 公式；单测。
- `chassis/ChassisIdentityService.java`：新装配登记、重载复核、UNLOADED/REMOVED/拆解规则；复用现有 `ChassisRegistry`。
- `chassis/ChassisLoadedRecognition.java`：只读已加载 plot 的必要骨架，单 plot/有界队列。
- `compat/sable/ChassisSubLevelObserver.java`：`SubLevelObserver` 适配与 constraint 释放；无生产 tick 锁。
- `test/.../ChassisNominalTest.java`、`ChassisIdentityServiceTest.java`、`ChassisLoadedRecognitionTest.java`。
- 上述均等 hold 通过后再实施；本轮不新增/修改 Java 或测试。

### 冻结边界

- 不改 `mass/drive/stress/brake`，不改 Simurail 轮对约束与现有 `PhysicsBogeyAxleLockMixin`。
- 不新增选择询问；产品规则按已确认口径。
- 不构建、不进游戏、不新增工具。
- 正式高度锁仍等 `hold` 实机证据；probe 未过不接生产 tick。

### 2026-11-11 固定高度生产路径：旧阻塞记录（同日已按新口径实现，见文末“生产轨锁”）

> 以下 1–6 是“高度锁 + 探针”旧口径的阻塞记录。新口径改为“锁 Y/Z 平移与全部旋转、仅留
> 世界 X 平移”，由文末的 `ChassisRailLock` 接入生产；旧记录保留为证据缺口/历史，不代表
> 当前仍未实现。

用户已选定“固定高度平面轨道”概念，但当时**没有**接入生产高度约束，也没有加任何
per-tick teleport。原因不是工作量，而是当前证据不足以写出一个不会误伤用户列车的锁。
逐条列阻塞项，供下一轮决策；这些是“缺什么证据”，不是“实现难度”：

1. **`hold` 探针在本机从未运行过。** 现有记录只有静态审查：`qa/nine-fixes-20261110.md` §14.2
   明确“车架正式固定高度：未实现……没有生产 tick 接锁、没有每 tick teleport、没有脱轨/拆解释放”；
   `qa/live-nine-fixes-20261110.md` §World anchor acceptance 记录的是“Stage 1 hold preserves its
   starting Y and tilt”，即只说明了**没有实测**、并且说明即使拿到采样也只能证明 ground-joint
   行为，不能证明标称高度或水平化甲板。`docs` 里 `hold` 的验收门（本文件“待 probe 的设计门”）
   全部仍为未打勾状态。没有这一组读数，`LINEAR_Y + ANGULAR_X/Z` 的关节帧方向是推的，不是测的。
2. **frame2 的局部基 `B` 未确认。** 设计写的是“标准世界装配路径 pose 默认朝向为单位阵，故
   `B=I`”，但同一段自己也标注“该默认值需首次编译/探针确认，不能猜”。这是整个水平锁唯一
   的坐标前提：猜错就是把一辆本来水平的车按住一个固定倾角。探针的 `dump` 必须先给出装配
   瞬间的 `logicalPose.orientation` 读数。
3. **标称 deck `TRACK_Y+2=66` 未与实测对齐。** 66 是从 `OakTrackCorridor.TRACK_Y=64` 与蓝图
   顶面推出来的；`docs` 的设计门第 2 条要求实测偏差先解释，否则不得改用沉车 Y。目前既没有
   实测 deck 高度，也没有偏差解释，锁的目标高度就没有可信值。
4. **生命周期释放没有可依赖的“脱轨事件”。** Simurail 没有 derail 事件（本文件“解除/脱轨”
   段），只能轮询 `isDerailed()`；而 `SubLevelRemovalReason` 只有 `UNLOADED`/`REMOVED`，
   两者无法区分“玩家拆解”和“引擎因 mass 无效移除”。在没有实测顺序的前提下写释放钩子，
   最坏情况是把一次卸载当成拆解、删掉用户车的身份与高度校准。
5. **锁与既有轮对锁的叠加未验证。** `PhysicsBogeyAxleLockMixin` 已经锁住每个轮轴的
   `LINEAR_Y/LINEAR_Z`（`SimurailBogeyLockRules` 的 captive half-width）。再叠一个整车
   `LINEAR_Y` 世界锚，在同一自由度上有两个硬约束；谁先解、过弯时会不会互相顶，只有实机
   能回答。`docs` 的冻结边界也明确“不改 Simurail 轮对约束与现有 mixin”。
6. **“载重/视觉支撑”没有可核对的需求数值。** 本仓库与 docs 里找不到该条需求的载荷、
   下沉量或外观目标（grep `载重 / 承重 / 视觉支撑 / 下沉` 均无相关条目），只有用户口述的
   “改善载重/视觉支撑”。在不改 train mass / traction / stress / braking / drive 的前提下，
   能动的只剩“高度与姿态由谁决定”这一项，而那正是上面 1–5 的锁。没有目标值就无法判定
   做完没有，写出来的常量就是猜的。

因此本轮的处置是：**保持生产路径不变**，不改 `mass/drive/stress/brake`、不加 teleport、
不建 joint；把上面的门留在原地。等 `hold` 在测试存档跑出 `verticalError`、pitch/roll 漂移、
`logicalPose.orientation` 与实测 deck 四组读数后，再按“已核源码事实”一节的公式接
`ChassisNominal` + `ChassisIdentityService`。在那之前，任何“每 tick 把车 teleport 回 66”
的补丁都只会把用户列车压进轨道里，属于**已知有害**，不做。

## 局部验证

```text
gradlew.bat :dimblend:test --tests dimblend.worldgen.StarterDepotDataTest
gradlew.bat :dimblend-experience:test --tests "dimblend.experience.chassis.*"
gradlew.bat :dimblend-experience:runGameTestServer -PwithChassis -PchassisModsDir=<整合包mods目录>
```

后一命令仅选择 `dimblend_chassis` 命名空间的测试，加载实际Sable/Simurail/Copycats及dimblend组件，验证出生端、轨道缺口、场地检查、两种底盘组装与强制加载、玩家放置与冷静期、机壳原料过滤。测试专用flat预设添加rotating维度，保留原定义的四种基础地形生成器；测试地点均在地表出生带，不包含其它纬度生成验证。普通构建不包含该预设。

2026-10-18 验证结果：6项局部JUnit、5项GameTest全部通过。GameTest另确认所有转向架实际挂轨、完整Sable车体可保存、测试服务器正常关服保存。普通构建的三个jar均已检查不含测试flat预设。

2026-11-10 阶段1/C4/阶段2纯数据新增（**尚未编译、未运行**）：

- JUnit：`ChassisInteractionTest`（主手/潜行/副手/物品/适用条件组合）、`ChassisPoseTest`（YXZ 欧拉分解、只保留偏航的关节帧、车体局部帧、plot→world 锚点）、`ChassisLaneRuleTest`（lane→轨道 id 与服务端 profile 一致）、`ChassisSkeletonTest`（四朝向、上装忽略、W/I 布局、缺骨架/多转向架拒绝）、`ChassisRegistryTest`（显式标称高度、脱轨/卸载保留、真拆解删除、NBT 往返、坏条目跳过）。
- GameTest：`ChassisInteractionGameTests`（空手/普通扳手开菜单；潜行/副手/其他物品/够不到/无天然主线全部放行；2026-11-11 增补：可放置方块与可用物品必须走原版路径且不开会话、副手持扳手也不开菜单）、`ChassisWorldAnchorGameTests`（单方块 Sable 子层级上建 `hold` 探针，采样后按时销毁且 `isValid()` 为 false；空目标不建 joint）。
- 实机：在测试存档进入 `dimblend:rotating`，对指定车架执行 `/dbx chassis probe start <uuid> 10 hold`，再用 `dump` 取漂移/高度/轮 pivot 记录。**未执行前不得把正式锁接入生产 tick。**
- 2026-11-11 COM 修正（**尚未编译、未运行**）：`ChassisPoseTest.anchorSubtractsLargeNonZeroPlotCenterOfMassLikeNativeJoint` 覆盖 raw y128.5 / COM y128.0 / bodyY65 的非零 COM；probe start/sample 统一用 `position + R*(raw-COM)`，COM 无效 fail closed；Report/Sample 记录 COM。该修正只修诊断锚点，不证明 deck 66，也不改正式 frame2。

独立复核共3轮：首轮全量发现原料复合语义及验证覆盖问题；第2轮原agent增量发现嵌套标签展开遗漏及夹具限制；第3轮原agent增量验证全部关闭，通过，无误判排除。标签问题最终通过延后至真实标签绑定后求值解决，删除了标签JSON改写。

验证边界：没有打开客户端检查选择页面的实际画面；没有实际重启游戏进程验证中途施工继续，仅验证保存数据往返和实际服务端保存。

## 生产轨锁（2026-11-11 实现，未实机验证）

用户新口径：`dimblend:rotating` 的标准车架列车，车轮必须始终压在轨上，不得抬升、下沉、
脱轨或横向漂移；整台已装配刚体只允许沿世界 X 平移。即锁 `LINEAR_Y`、`LINEAR_Z` 与
`ANGULAR_X/Y/Z`，只留 `LINEAR_X`。归档证据：轨道块 y=64、标称 deck≈66、主线水平沿 X；
归档分析为 6 个已连接子层级 + 1 台站场车厢。

### 实现

- `chassis/ChassisRailLockRules`（纯规则）：自由度集合、0°/180° X 对齐目标、创建/保持
  窗口、稳定计数、轨道状态判定。单测不加载 Sable/Rapier。
- `compat/sable/ChassisRailLock`：登记扫描 + `SubLevelObserver` + Rapier
  `bodyA == null` 的世界 generic joint。每 `CHECK_INTERVAL_TICKS=10` tick 扫一次登记表；
  连续 `STABLE_CHECKS=3` 次资格通过才建约束。已建约束每 tick 复核。
- `chassis/ChassisAssemblyRegistration`：`ChassisConstruction.assemble` 与
  `StationDepotAssembly.assemble` 成功后写 `ChassisRegistry`，标称高度取
  `anchorBlockY + 1`（标准装配 65 -> 66），不读当前沉车 pos。已有登记不覆盖。
- `ChassisEvents`：rotating 维度 tick 调用 `ChassisRailLock.tick`；
  `ServerStoppedEvent` 与 `LevelEvent.Unload` 释放。
- `ChassisProbeCommand`：register/calibrate/unregister 触发 request/release；
  `/dbx chassis registry` 追加 `lock=LOCKED|STABILIZING(n/3)|UNLOCKED`。

### 约束口径

- 关节锚点 `pos2` = plot 中心块中心再 +`(0, 0.5, 0)`（deck 顶面）；`pos1` 的世界 Y =
  登记标称高度（标准 66），世界 Z = 中心下方 XO 轨道块中心，世界 X = 当前 deck X（自由轴，
  不产生初始误差）。
- `orientation1` = 目标 yaw（0° 或 180°）的绕 Y 世界帧，`orientation2` = 单位帧；
  锁定后 `R_body = R_y(targetYaw)`，pitch/roll 归零、yaw 固定。
- 只认 `dimblend:rotating` + `ChassisRegistry` 里 `POWER/CARRIAGE` 的 UUID；reward
  wagon、未登记车体、非标准转向架集合不进入。
- 资格：必要转向架 `W/I` 的 plot 块位置必须与登记蓝图精确一致（POWER 6 个、CARRIAGE
  2 个），全部 `getOptions().enabled`、`hasTrack()` 且 `!isDerailed()`；中心与所有已加载转向架下方必须是
  `TrackShape.XO` 且 `!HAS_BE`（排除隐藏 bezier/道岔），轨道块 Y = 标称 - 2；
  高度误差 ≤1.0、横向误差 ≤0.6、yaw/pitch/roll ≤10° 才建锁；保持窗口 1.0 / 0.75 / 15°。
- 释放：脱轨、轨道离开 XO、误差超出保持窗口、句柄失效、子层级 `UNLOADED/REMOVED`、
  注销登记、维度卸载/关服。释放只销毁 joint，不改质量、驱动、应力、制动。
- 不每 tick teleport、不改 pose、不碰 SimuRail `PhysicsBogeyAxle`/轮轨 joint、不改
  `PhysicsBogeyAxleLockMixin`。曲线/道岔/坡道/非 X 轨道/90° 朝向会保持未锁定或释放，
  保留 SimuRail 原有行为。

### 未验证 / 已知边界

- **未实机**：没有在测试存档建约束后观察 6+1 编组的 joint 漂移、轮 pivot 高度、deck 66
  对齐、过轨缝/道岔释放与重锁；也没有确认与 SimuRail 轮对 captive 锁叠加时的求解稳定性。
- **Sable 物理 recovery**：`SubLevelPhysicsSystem.recoverSubLevel` 会 remove/re-add body，
  Rapier 侧 joint 被静默移除，而 Sable 的 `isValid()`（只查自己的 joint_set）仍为 true。
  当前没有每 tick 读 joint 约束力的安全查询；靠“车体误差超出保持窗口 -> 释放 -> 下一轮稳定
  检查重锁”做有界自愈，不是瞬间重锚。极端情况下最多先漂 1 格/15° 再拉回。
- COM 变化不会重建关节局部锚点；货载长期变化可能带来小的高度偏差。
- 旧车必须先有登记：新装配自动登记；归档里的 6+1 若从未登记，需要
  `/dbx chassis register <uuid> <POWER|CARRIAGE> 66`（或对应实际标称高度）才会进入。
- 本路径没有新增 GameTest；纯规则测试覆盖 DOF/资格/窗口/生命周期判定，Sable/Rapier
  路径需另起非生产 GameTest 或实机。

### 本轮验证

`:dimblend-experience:test`：89 个 suite、**567 tests / 0 failures / 0 errors / 0 skipped**，
其中新增 `ChassisRailLockRulesTest` 13 项。未运行 GameTest、未启动客户端、未部署 jar。
