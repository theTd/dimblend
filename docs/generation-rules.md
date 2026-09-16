# DimBlend 生成规则（北极星文档）

> 本文档是维度拼接生成规则的**目标规格（north star）**。所有世界生成相关的
> 开发、重构与验收都以此为准；实现进度直接勾选对应条目。
> 区域（region）= 沿 X 轴宽度 **2048** 格的生成带。

## 一、区域布局规则

### 1.1 固定区域

每个区域宽度 2048。正负两侧镜像同一序列。

| 区域 | 纬度类型 |
|---|---|
| 0 | 出生点 · 地表 |
| 1 | 地表 |
| 2 | 地下 |
| 3 | 地表 |
| 4 | 地表 |
| 5 | 地下 |
| 6 | 地下 |
| 7 | 地狱 |
| 8 | 地表 |
| 9 | 地表 |
| 10 | 地下 |
| 11 | 地狱 |
| 12 | 地狱 |
| 13 | 地下 |
| 14 | 地表 |
| 15 | 地表 |
| 16 | 天域 |
| 17 | 暮色 |
| 18 | 星光 |
| 19 | 深暗 |
| 20 | 深渊 |
| 21 | 地表 |
| 32 | 末地 |

### 1.2 随机区域

| 区域范围 | 随机池 |
|---|---|
| 22–31 | 全部纬度（不含末地）：地表 / 地下 / 地狱 / 天域 / 暮色 / 星光 / 深暗 / 深渊 |
| 33 以上 | 末地随机：全部纬度（含末地） |

### 1.3 随机约束

- **随机区段**相邻不是同一 delegate。固定 0–21 允许连续同类型（如 8/9 地表、5/6 地下、11/12 地狱）。
- 地表具有 **3 倍随机权重**。
- 每个随机窗口内，当前池里的**每一种地形**（按 delegate，暮色/星光/天域等分开计）至少出现一次：22–31，以及 33 起每 16 区一段。正负两侧窗口独立保底。窗口末尾若尚未凑齐，强制抽取未出现过的类型。
- 布局算法变更不会重写已生成 chunk。继续探索旧世界会在新旧交界处错位；全图一致需要新世界。

## 二、全局规则

- [x] 矿石以矿堆为计，数量减半，每堆规模不变（`OrePileRules`：劫持 `ConfiguredFeature.place`，对矿物 `OreConfiguration` 堆 50% 取消；`size` 不改，门控 RNG 与塑形 RNG 分离）
- [x] 每次尝试生成矿物时 50% 概率整堆替换为安山岩（存活堆再掷一次，目标 RuleTest / size / 暴露丢弃率保持原样）
- [x] 轨道硬度和基岩一样（等效实现：挖掘进度归零 + 破坏/扳手事件取消 + 防爆，见 `CorridorTrackProtector` / `BlockBehaviourMixin`）
- [ ] 取消轨道碰撞箱
- [x] 阻止玩家用扳手拆除轨道（Create 扳手与手拆同走 `BlockEvent.BreakEvent`，一并取消；创造模式放行，同基岩）
- [x] 每个区域之间生成隔墙（`RegionBoundaryWall`：不同 `laneName` 边界整列墙；同名边界保持开放，如连续地表 0/1、连续地下 5/6。mod 纬度按具体名区分，暮色/星光/天域等相邻仍建墙。墙体生存不可挖、抗爆、原版活塞推不动，创造手拆放行，同基岩/轨道；谓词含边界列，不误伤末地带 BOP End Corruption 的 `null_block` 树干。见 `RegionBoundaryWallProtector` / `BlockBehaviourMixin`）
- [x] 有墙边界两侧各 16 chunk 禁结构/建筑类 feature（`RegionBoundaryNoStructureZone`：与走廊同宽；仅 `laneName` 不同的隔墙两侧生效，同名连续带如地表 0/1、地下 5/6 无墙不禁。结构 start 按 origin/AABB 丢弃，`BuildingLikeFeatures` 同步驱散）
- [x] 轨道洞穴大小的区域填充「折越门」方块，覆盖铁轨和地基（`WarpGateBlock` 填充走廊截面，含轨道与路基格）
- [x] 折越门对未授权实体是墙、对 Sable（含列车）是空气（`WarpGateBlock.getCollisionShape` 按 CollisionContext 分流；生存玩家需站在 Sable 结构上，创造/旁观/Create 车厢放行；`WarpGatePassageGuard` 只处理卡进门板）
- [x] 左下角物品栏左侧显示当前区域序号 + 以玩家当前位置为准的行进进度（`BandProgressHud`：护甲行上方的进度条，区域序号居中，`BandInfoSync` 推送 band_size）

## 三、分纬度规则

### 地表（Overworld）

- [x] 生成轨道：**桦木宽轨**，有路基（`CorridorTrackProfile.SURFACE`）
- [x] 时间恢复正常流逝
- [x] 天气走原版
- [x] 生成区间改为 **Y0 以上**（`OverworldSlice.SURFACE`：源 Y0–320，offset 0；底封在 Y-1）
- [x] 仅生成 煤 / 铜 / 铁 / 金 / 锌 五种矿石（地表 lane 上其它矿物堆直接取消；锌走 `c:ores/zinc` + 方块 id）
- [x] 取消晶洞类 feature（地表 lane 在 `ConfiguredFeature.place` 拦截 `GeodeConfiguration`，原版紫水晶及走同一配置的模组晶洞一并取消；地下及其它 lane 不拦。原因：地表切片 Y0–320，原版晶洞会在下层冒出来）

### 地下（Underground）

- [x] 生成轨道：**深色橡木宽轨**，有路基（`CorridorTrackProfile.UNDERGROUND`）
- [x] 时间锁定 22000
- [x] 天气锁定晴（雨雪是维度级的：客户端按玩家纬度遮罩，服务端只在这些列上当晴天处理）
- [x] 替换轨道上方 15 格宽内所有流体为玻璃（`OakTrackCorridor.replaceFluidStrip`：地下 lane 在 Z[-7,+7]、Y=64 到切片顶把流体换成玻璃；水/岩浆用蓝/红染色玻璃，其余流体用普通玻璃。八边形 1 格壳 `sealVaultShell` 仍保留，但不替换路基（Y=63）及以下）
- [x] 阻止海洋生物群系及其变种生成（地下 slice 的 delegate 生成器换装 `OceanFilteredBiomeSource`：`minecraft:is_ocean` 标签 + 蘑菇岛统一回退平原；chunk 群系填充与海洋结构校验同源生效，`possibleBiomes` 同步滤除）
- [x] 将地下全部生物群系显示为「地下」（显示层方案：Biome Notifier 兼容 mixin 给群系名追加「地下」后缀，如 平原 → 平原地下；未装 Biome Notifier 时无此提示，真实 id 不变）

### 下界（Nether）

- [x] 生成轨道：**黑石宽轨**，无路基（`CorridorTrackProfile.NETHER`）
- [x] 时间锁定 18000
- [x] 天气走原版
- [x] 阻止下界生物僵尸化（dimension_type 全局 `piglin_safe: true`）

### 末地（End）

- [x] 生成轨道：**幻纱宽轨**，无路基（`CorridorTrackProfile.END`）
- [x] 时间锁定 18000（末地/星光/深渊取「时间锁定」备选；客户端按玩家锁定，服务端世界时间照流）
- [x] 天气走原版

### 暮色（Twilight Forest）

- [x] 生成轨道：**标准宽轨**，有路基（`CorridorTrackProfile.TWILIGHT`）
- [x] 时间锁定 12950–13050（对齐 TF 1.21.1 `fixed_time: 13000` 的永暮亮度；区间内缓慢随机游移。不可用 12600–12700：那一段仍在日落亮侧，lightmap 约为 13000 的 1.5 倍，地形会看起来像白天）
- [x] 天气走原版
- [x] 恢复生物群系 shader（`dimblend:rotating` 自定义 DimensionSpecialEffects：相机所在群系属于 twilightforest 时逐帧切到 TF 委托——永暮星空/无日月晚霞/TF 雾色曲线/低空与黑森林浓雾；`TwilightBandFog` 移植 TF FogHandler 雾距平滑）

### 星光（Eternal Starlight）

- [x] 生成轨道：**标准宽轨**，取消路基（`CorridorTrackProfile.STARLIGHT`）
- [x] 时间锁定 14000（取「时间锁定」备选）
- [x] 天气走原版
- [x] 阻止星光传送门结构生成（覆写 5 个 `eternal_starlight:has_portal_ruins_*` 群系 tag 为空，见 `docs/starlight-portal-structure.md`）

### 深暗（Deeper & Darker Otherside）

- [x] 生成轨道：**标准宽轨**，无路基（`CorridorTrackProfile.OTHERSIDE`）
- [x] 时间锁定 18000
- [x] 天气锁定晴

### 天域（Aether Skylands）

- [x] 生成轨道：**无枕木宽轨**，无路基（`CorridorTrackProfile.AETHER`）
- [x] 时间锁定 4000
- [x] 天气锁定晴

### 深渊（Voidscape）

- [x] 生成轨道：**无枕木宽轨**，无路基（`CorridorTrackProfile.VOIDSCAPE`）
- [x] 恢复 shader，或时间锁定 18000（取「时间锁定」备选）
- [x] 天气锁定晴
