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
- [x] 时间锁定 18000
- [x] 天空套用末地天空盒（与末地相同：`ClientBandLane.endSky()` → `SkyType.END` / `end_sky.png`）
- [x] 天气锁定晴（雨雪是维度级的：客户端按玩家纬度遮罩，服务端只在这些列上当晴天处理）
- [x] 替换轨道上方 15 格宽内所有流体为玻璃（`OakTrackCorridor.replaceFluidStrip`：地下 lane 在 Z[-7,+7]、Y=64 到切片顶把流体换成玻璃；水/岩浆用蓝/红染色玻璃，其余流体用普通玻璃。八边形 1 格壳 `sealVaultShell` 仍保留，但不替换路基（Y=63）及以下）
- [x] 阻止海洋生物群系及其变种生成（地下 slice 的 delegate 生成器换装 `OceanFilteredBiomeSource`：`minecraft:is_ocean` 标签 + 蘑菇岛统一回退平原；chunk 群系填充与海洋结构校验同源生效，`possibleBiomes` 同步滤除）
- [x] 将地下全部生物群系显示为「地下」（显示层方案：Biome Notifier 兼容 mixin 给群系名追加「地下」后缀，如 平原 → 平原地下；未装 Biome Notifier 时无此提示，真实 id 不变）

### 下界（Nether）

- [x] 生成轨道：**黑石宽轨**，无路基（`CorridorTrackProfile.NETHER`）
- [x] 时间锁定 18000
- [x] 天空套用末地天空盒（与末地相同：`ClientBandLane.endSky()` → `SkyType.END` / `end_sky.png`）
- [x] 天气走原版
- [x] 阻止下界生物僵尸化（dimension_type 全局 `piglin_safe: true`；Voidscape `voidscape:nether` 刷怪表直接刷僵尸猪灵/僵尸疣猪兽，rotating 里在 finalizeSpawn 换成猪灵/疣猪兽）

### 末地（End）

- [x] 生成轨道：**幻纱宽轨**，无路基（`CorridorTrackProfile.END`）
- [x] 时间锁定 18000（末地/星光/深渊取「时间锁定」备选；客户端按玩家锁定，服务端世界时间照流）
- [x] 天气走原版
- [x] 恢复末地天空（`RotatingDimensionEffects`：玩家 `endSky` 纬度（end / underground / nether / deeperdarker）时 `SkyType.END`，原版画 `end_sky.png`，无主世界日月星；雾色/无云/forceBrightLightmap 对齐 `EndEffects`。18000 锁只管昼夜读数，不管天空盒。voidscape 走自己的 shader，见下）
- [x] 服务端刷怪按纬度锁夜（`NaturalSpawner` 推 `ServerBandTime`，`getSkyDarken` 按 18000 计算；末影人不会因全局白天补不上而消失。岛面仍有 skylight，密度接近主世界夜晚而非原版末地无天空光）

### 暮色（Twilight Forest）

- [x] 生成轨道：**标准宽轨**，有路基（`CorridorTrackProfile.TWILIGHT`）
- [x] 时间锁定 12950–13050（对齐 TF 1.21.1 `fixed_time: 13000` 的永暮亮度；区间内缓慢随机游移。不可用 12600–12700：那一段仍在日落亮侧，lightmap 约为 13000 的 1.5 倍，地形会看起来像白天）
- [x] 天气走原版（服务端列天气仍随维度；客户端暮色带遮罩主世界雨/雷，避免天空盘、星空、薄荷雾被共用降水压暗）
- [x] 恢复生物群系 shader（`dimblend:rotating` 自定义 DimensionSpecialEffects：相机所在群系属于 twilightforest 时逐帧切到 TF 委托——永暮星空/无日月晚霞/TF 雾色曲线/低空与黑森林浓雾；`TwilightBandFog` 只移植 1.21.1 FogHandler 的雾距平滑，不二次乘 dusk 雾色。极光 sheet 与 `isFoggyAt` 的绝对 Y 随 +64 抬升）

### 星光（Eternal Starlight）

- [x] 生成轨道：**标准宽轨**，取消路基（`CorridorTrackProfile.STARLIGHT`）
- [x] 时间锁定 14000（取「时间锁定」备选；只管昼夜读数，不管天体位置）
- [x] 天气走原版
- [x] 恢复星光天空（`RotatingDimensionEffects`：相机所在群系属于 eternal_starlight 时委托 `ESSkyRenderer`——死星/自定义星场/`SkyType.NONE`/云高 160；ES 天空把死星钉在 12500，与 14000 时间锁解耦）
- [x] 阻止星光传送门结构生成（覆写 5 个 `eternal_starlight:has_portal_ruins_*` 群系 tag 为空，见 `docs/starlight-portal-structure.md`）

### 深暗（Deeper & Darker Otherside）

- [x] 生成轨道：**标准宽轨**，无路基（`CorridorTrackProfile.OTHERSIDE`）
- [x] 时间锁定 18000
- [x] 天空套用末地天空盒（与末地相同：`ClientBandLane.endSky()` → `SkyType.END` / `end_sky.png`）
- [x] 天气锁定晴

### 天域（Aether Skylands）

- [x] 生成轨道：**无枕木宽轨**，无路基（`CorridorTrackProfile.AETHER`）
- [x] 时间锁定 4000
- [x] 天气锁定晴

### 深渊（Voidscape）

- [x] 生成轨道：**无枕木宽轨**，无路基（`CorridorTrackProfile.VOIDSCAPE`）
- [x] 恢复 Voidscape shader（`RotatingDimensionEffects` 在 voidscape lane 委托 `voidscape:void` 的 `DimensionSpecialEffects` / `VoidSkyRenderer`）；时间锁定 18000 只管昼夜读数。`isInVoidDimension` 经 mixin 把 rotating 深渊 lane 视作虚空（光照/雾/灌注/死亡/刷怪）
- [x] 天空光恒为 0（真维度 `has_skylight: false`：光引擎没有天空层，读恒 0、也没有天光数据可存。rotating 必须给其它 lane 保留天空层，故按 X 列屏蔽：深渊 lane 内 `SkyLightSectionStorage.getLightValue` 读 0，`LayerLightSectionStorage.getDataLayerData` 交回全 0 空天光层——存档不写 `SkyLight`、光照包按「空层」发（客户端排队全 0 层），Sodium 等直读数据层的消费者同样读 0（返回 null 会被 Sodium 当成天空光 15，反而把洞穴/岛内照亮）；方块光与其它 lane 不受影响。随之与真维度一致：深渊带地表亮度、刷怪暗度、作物不再靠天光生长、岛面不再日晒。末地带仍按「末地」条目显式保留 skylight）
- [x] 天气锁定晴
- [x] 下界层疣猪落地刷怪：原版疣猪兽无 SpawnPlacements（NO_RESTRICTIONS），3D 群系柱半空刷出后会被换成疣猪从天上掉；rotating 里 SpawnPlacementCheck 要求 ON_GROUND，对齐 Voidscape 自己维度的 PositionCheck
- [x] 地形相对原维度下移 64（`dimblend:y_shifted` 包 `voidscape:void`，`y_offset: -64`；走廊仍在 Y=64，相对岛面抬高 64。密度/群系走与暮色相同的 `YShiftedDensity`，保留 Voidscape 自己的 chunk generator）
- [x] 反尖塔恢复生成（`SpireFeature.checkForRoom` 的 `p.getY() <= 0` 是 Voidscape 自己维度的世界底；地形下移 64 后反尖塔层（群系界 Y32 → 平移后 Y-32，向下到世界底）整层落在 Y<0，原判定在候选列的第一个方块就返回 false，深渊带里一根都不长。`SpireFeatureMixin` 改按 `level.getMinBuildHeight()` 判定：原维度世界底仍是 0、逐位不变；rotating 里世界底是 -64，反尖塔重新生成。同种子探针实测：rotating 同区 2 tips/49 chunk（对照 `voidscape:void` 3 tips/49 chunk），多区抽样 1 tips/75 chunk 对对照 3 tips/50 chunk；流体面随平移后同区为 3 tips）
- [x] 深渊带流体面随平移（vanilla `NoiseBasedChunkGenerator.createFluidPicker` 的岩浆面与阈值都是绝对值：`FluidStatus(-54, LAVA)` 与 `y < min(-54, seaLevel) ? lava : defaultFluid`；`YShiftedChunkGenerator.applyShift` 原传 `seaLevelOverride = null`，picker 与未平移时逐字节相同，于是平移后频带 Y≤-55 全变岩浆、`getSeaLevel()` 仍报 0。现按暮色同款传 `seaLevelOverride = seaLevel + y_offset`（0 + (-64) = -64）：阈值 `min(-54,-64) = -64` 落到 rotating 的世界底，世界内取不到 lava 分支，频带恢复 Voidscape 自己的无岩浆空洞，delegate `getSeaLevel()` 报 -64。注意 -54 常量本身没被移动，只是不可达。同种子探针实测（7x7 chunk、统计 y ∈ [各自世界底, +32)、同区同种子前后对比）：频带 lava 47343 → 0（`highestLavaY=none`），bandAir 93400 → 140651，bandBedrock 260422 → 260469（多出的一根反尖塔本体），深渊 delegate `seaLevel=-64 yOffset=-64`；对照 `voidscape:void` 逐项不变（seaLevel 0、lava 0、tips 3、bandBedrock 222355）。反尖塔 tips 同区 2 → 3 与对照 3 同量级，但 n=1 区、Poisson 不显著，**不作为密度结论**。限制：`applyShift` 的幂等早退按落盘的派生 settings 判断，而旧世界 `level.dat` 已冻结 `sea_level: 0`（`RegistryFileCodec` 对 direct holder 内联写），此类世界重载后新生成区块仍有岩浆且 `getSeaLevel()` 仍为 0 —— 与 §1.3 的同类约定一致（生成类改动不回溯旧世界；§1.3 只覆盖已生成 chunk，本条另含生成器配置被 level.dat 冻结）；若要连旧世界一起修，需让 wrapper 不落盘派生状态（照 `YShiftedNoiseChunkGenerator` 的 codec 存源 settings/显式 `sea_level` 后每次解码重推））
- [x] 禁用 rotating 内的 Voidscape 传送（`LevelUtilMixin.getDimensionForTeleport` 在 rotating 返回空：`PortalBlock.entityInside` 不再武装 `Insanity.inPortal`，站在虚空门里既不传送也不累计被吸入虚空的读数，门方块/粒子/音效保留。不修的话 `isInVoidDimension` 已把深渊 lane 认作虚空，「虚空」一侧的目的地是 `minecraft:overworld`，门会把区段里的玩家扔进真主世界；另一侧则进独立 `voidscape:void`。`InsanityMixin.canTeleport` 在 rotating 恒 false：基岩入虚空的判定按维度自己的世界底（`minBuildHeight + 15`，rotating 是 -49）。地板封得比 -49 高的区段（地表/地下带底封 Y=-1）够不着，正是缺口报告里那条「不可达」；地板基岩低到 -49 的区段（深渊带的基岩岛体一路到 -64，但那条 lane 已算虚空、走不到这个分支）则会把人送去独立维度。故 rotating 内一律关闭：不启动倒计时，跨维度携带进来的倒计时也会被取消。深渊带与其它区段一样靠走廊通行；外部维度（真主世界 ↔ 独立 void 维度）的门行为不变。见 `VoidscapeMixinContractTest.rotatingBansVoidscapeTeleports`）
