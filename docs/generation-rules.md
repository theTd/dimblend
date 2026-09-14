# DimBlend 生成规则（北极星文档）

> 本文档是维度拼接生成规则的**目标规格（north star）**。所有世界生成相关的
> 开发、重构与验收都以此为准；实现进度直接勾选对应条目。
> 区域（region）= 沿 X 轴宽度 **2048** 格的生成带。

## 一、区域布局规则

### 1.1 固定区域

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
| 32 | 末地 |

### 1.2 随机区域

| 区域范围 | 随机池 |
|---|---|
| 8–15 | 地表 / 地下 / 深层地下 / 地狱 |
| 16–31 | 8–15 池 + mod 纬度（暮色、星光、深暗、天域、深渊 等） |
| 33 以上 | 全部纬度 |

### 1.3 随机约束

- 相邻的两个区域不会相同。
- 地表具有 **3 倍随机权重**。

## 二、全局规则

- [x] 矿石以矿堆为计，数量减半，每堆规模不变（`OrePileRules`：劫持 `ConfiguredFeature.place`，对矿物 `OreConfiguration` 堆 50% 取消；`size` 不改，门控 RNG 与塑形 RNG 分离）
- [x] 每次尝试生成矿物时 50% 概率整堆替换为安山岩（存活堆再掷一次，目标 RuleTest / size / 暴露丢弃率保持原样）
- [x] 轨道硬度和基岩一样（等效实现：挖掘进度归零 + 破坏/扳手事件取消 + 防爆，见 `CorridorTrackProtector` / `BlockBehaviourMixin`）
- [ ] 取消轨道碰撞箱
- [x] 阻止玩家用扳手拆除轨道（Create 扳手与手拆同走 `BlockEvent.BreakEvent`，一并取消；创造模式放行，同基岩）
- [x] 每个区域之间生成隔墙（`RegionBoundaryWall`：不同 `laneName` 边界整列墙；同名边界保持开放，如连续地表 0/1、连续地下 5/6。mod 纬度按具体名区分，暮色/星光/天域等相邻仍建墙）
- [x] 轨道洞穴大小的区域填充「折越门」方块，覆盖铁轨和地基（`WarpGateBlock` 填充走廊截面，含轨道与路基格）
- [x] 折越门对未授权实体是墙、对 Sable（含列车）是空气（`WarpGateBlock.getCollisionShape` 按 CollisionContext 分流；生存玩家需站在 Sable 结构上，创造/旁观/Create 车厢放行；`WarpGatePassageGuard` 只处理卡进门板）
- [ ] 左下角物品栏左侧显示当前区域序号 + 以玩家当前位置为准的行进进度

## 三、分纬度规则

### 地表（Overworld）

- [x] 生成轨道：**桦木宽轨**，有路基（`CorridorTrackProfile.SURFACE`）
- [x] 时间恢复正常流逝
- [ ] 生成区间改为 **Y0 以上**
- [x] 仅生成 煤 / 铜 / 铁 / 金 / 锌 五种矿石（地表 lane 上其它矿物堆直接取消；锌走 `c:ores/zinc` + 方块 id）

### 地下（Underground）

- [x] 生成轨道：**深色橡木宽轨**，有路基（`CorridorTrackProfile.UNDERGROUND`）
- [x] 时间锁定 22000
- [ ] 替换轨道上方 15 格宽内所有流体为玻璃
- [x] 阻止海洋生物群系及其变种生成（地下 slice 的 delegate 生成器换装 `OceanFilteredBiomeSource`：`minecraft:is_ocean` 标签 + 蘑菇岛统一回退平原；chunk 群系填充与海洋结构校验同源生效，`possibleBiomes` 同步滤除）
- [ ] 测算完成后将地下全部生物群系 id 改为「地下」

### 下界（Nether）

- [x] 生成轨道：**黑石宽轨**，无路基（`CorridorTrackProfile.NETHER`）
- [x] 时间锁定 18000
- [x] 阻止下界生物僵尸化（dimension_type 全局 `piglin_safe: true`）

### 末地（End）

- [x] 生成轨道：**幻纱宽轨**，无路基（`CorridorTrackProfile.END`）
- [x] 时间锁定 18000（末地/星光/深渊取「时间锁定」备选；客户端按玩家锁定，服务端世界时间照流）

### 暮色（Twilight Forest）

- [x] 生成轨道：**标准宽轨**，有路基（`CorridorTrackProfile.TWILIGHT`）
- [x] 时间锁定 12600–12700（模拟原本的抽搐黄昏效果，区间内缓慢随机游移）
- [x] 恢复生物群系 shader（`dimblend:rotating` 自定义 DimensionSpecialEffects：相机所在群系属于 twilightforest 时逐帧切到 TF 委托——永暮星空/无日月晚霞/TF 雾色曲线/低空与黑森林浓雾；`TwilightBandFog` 移植 TF FogHandler 雾距平滑）

### 星光（Eternal Starlight）

- [x] 生成轨道：**标准宽轨**，取消路基（`CorridorTrackProfile.STARLIGHT`）
- [x] 时间锁定 14000（取「时间锁定」备选）
- [x] 阻止星光传送门结构生成（覆写 5 个 `eternal_starlight:has_portal_ruins_*` 群系 tag 为空，见 `docs/starlight-portal-structure.md`）

### 深暗（Deeper & Darker Otherside）

- [x] 生成轨道：**标准宽轨**，无路基（`CorridorTrackProfile.OTHERSIDE`）
- [x] 时间锁定 18000

### 天域（Aether Skylands）

- [x] 生成轨道：**无枕木宽轨**，无路基（`CorridorTrackProfile.AETHER`）
- [x] 时间锁定 4000

### 深渊（Voidscape）

- [x] 生成轨道：**无枕木宽轨**，无路基（`CorridorTrackProfile.VOIDSCAPE`）
- [x] 恢复 shader，或时间锁定 18000（取「时间锁定」备选）
