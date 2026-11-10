# 补充需求：指南针、牵引效率与浮空水晶

状态：独占文件代码及测试代码已实现；共享入口尚待集成。未运行 Gradle、编译、单测、GameTest 或游戏，不宣称实测完成。未 stage / commit / push，未改主工作树、ACL 或全局规则。

## 实现范围

- `compat/compass/OriginCompass.java`：固定识别 `dimblend:rotating`，不借用仅供探索规则使用的可配置维度。普通指南针目标为 `BlockPos.ZERO`，导航台沿用上游 `getCenter()`，两条路径的水平目标一致。
- `mixin/OriginCompassMixin.java`：只接管原版 static `CompassItem.getSpawnPosition(Level)`；其他维度继续执行原方法。handler 因目标方法为 static 而必须是 **private static**，没有额外合并成员。
- `mixin/compat/simulated/NavigationCompassMixin.java`：只包导航台的 `Level.getSharedSpawnPos()` 调用，`require=1, allow=1`。通过参数捕获取得指南针，不链接 Simulated 类。磁石子层级投影的提前返回分支完全保留；原版 `LODESTONE_TRACKER` 或 Simulated 追踪组件存在时保留原回退，包括失效绑定。
- `compat/simurail/TractionEfficiency.java`：`abs(m/s) / (abs(rpm) / 4 - 1) * 100`。`abs(rpm)<=4` 为 `--`；其他数值两位小数，允许超过 100%；非有限遥测为 `--`，避免把 NaN/Infinity 送到显示器。
- `TractionEfficiencyDisplaySource.java`：继承 Create `SingleLineDisplaySource`，保留原生 Label 文本框与配置保存流程。第二行用原生单选组件提供唯一的“百分比”格式；复用 Create 既有“百分比 / 显示格式”翻译。名字只出现在数据源选择器，结果只含玩家标签和数值。默认刷新周期沿用上游。
- `TractionDisplaySources.java`：DeferredRegister 注册 `dimblend_experience:traction_efficiency`，common setup 的 enqueueWork 把它加入 `DisplaySource.BY_BLOCK_ENTITY[SimurailBlockEntities.PHYSICS_BOGEY]`。此类只可在 Create + Simurail 同时在场时解析。
- `client/ponder/SupplementPonderPlugin.java`：把 `simurail:physics_bogey` 加入 Create `DISPLAY_SOURCES` 思索标签。
- `data/aeronautics/floating_materials/levitite.json`：只把四项 friction 置零；上游 `prevent_self_lift=true`、`scale_friction_with_gravity=true`、`lift_strength=10`、`transition_speed=3` 保留。没有触碰视觉、粒子或渲染代码。
- `data/dimblend_experience/tags/block/lava_melt_immune.json`：保留旧 optional `#create:non_breakable`，追加两个 optional 水晶方块 ID。

## 本机证据

### 指南针

原版源码：`E:/misc/mcsrc-1.21.1/net/minecraft/world/item/CompassItem.java:25`，`getSpawnPosition(Level)` 只在 `dimensionType().natural()` 时返回出生点，否则 null。

`E:/misc/mcsrc-1.21.1/net/minecraft/client/renderer/item/ItemProperties.java:166` 先读取 `DataComponents.LODESTONE_TRACKER` 并直接使用其 target，只有组件不存在才调用上述 static 方法。因此不改绑定指南针的渲染目标；失效的原版绑定仍保持原版失效表现。

本机 `E:/misc` 没有 Simulated/Aeronautics 源码仓库，使用已提取的 1.3.0 nested jar 核实，目录为：

`C:/Users/cui/.codex/visualizations/2026/11/10/01a1c3b1-b12b-7a73-aac3-3fdd654b944d/`

- `dev.simulated_team.simulated.simulated-neoforge-1.21.1-1.3.0.jar` 的 metadata 确认 modId 为 `simulated`。
- javap：`dev.simulated_team.simulated.content.navigation_targets.CompassNavigationTarget#getTarget(dev.simulated_team.simulated.content.blocks.nav_table.NavTableBlockEntity, ItemStack): Vec3`。字节码 offset 5–56 先查自有绑定、查映射并返回 projectedPos；57–64 是唯一的 `Level.getSharedSpawnPos().getCenter()`。
- `SimDataComponents` 的 static 初始化确认 `LODESTONE_COMPASS_SUBLEVEL_TRACKER` 实际注册路径为 **`lodestone_compass_tracker`**（不是字段名称的直译），值类型为 UUID。helper 经原版组件注册表访问 `simulated:lodestone_compass_tracker`。
- `SimBlocks` static 初始化确认方块 ID `simulated:navigation_table`。

注意：Simulated 1.3.0 原来的这段代码并不单独读取原版磁石 target。对于仅有原版绑定、没有 Simulated 自有绑定的物品，仍保留其原先的出生点回退；本单元不改写上游磁石追踪机制。有效的 Simulated 子层级绑定仍优先返回投影位置。

### 牵引效率与 Create API

- `E:/misc/Create-Simurail/src/main/java/com/crystaelix/simurail/content/bogey/PhysicsBogeyBlockEntity.java:883`：`getMovementSpeed()` 返回前后 axle.speed 的均值；与工作树 `dimblend/libs/simurail-1.21.1-0.0.0-a+ecd2dd3.jar` 的 javap 字节码一致。
- 同目录 `PhysicsBogeyAxle.java:268`、`:551`：相对轨道速度投影到转向架方向得到 speed，而非总三维速率。
- `E:/misc/sable/common/src/main/java/dev/ryanhcode/sable/api/SubLevelHelper.java:109` 起的速度接口说明明确单位 `[m/s]`，不乘每秒 20 tick。
- `E:/misc/Create/src/main/java/com/simibubi/create/content/kinetics/base/KineticBlockEntity.java:290`：`getSpeed()` 返回有效输入转速；过载/冻结时为零。本单元按用户指定接口读取。
- 本地 Create 源码 `gradle.properties` 为 **6.0.11**，仅作机制说明；实际签名已用 `E:/misc/TrainTripWorld/mods/[机械动力] create-1.21.1-6.0.10.jar` 核对。
- `SingleLineDisplaySource` 的 `provideLine(DisplayLinkContext, DisplayTargetStats)`、`allowsLabeling(DisplayLinkContext)`、`initConfigurationWidgets(DisplayLinkContext, ModularGuiLineBuilder, boolean)` 与 6.0.10 签名一致。
- `E:/misc/Create/src/main/java/com/simibubi/create/api/behaviour/display/DisplaySource.java`：`getName()` 使用 `<namespace>.display_source.<path>`；`getAll` 合并 BY_BLOCK 和 BY_BLOCK_ENTITY，因此注册 BE 类型即可被连接器识别。
- `PercentOrProgressBarDisplaySource#formatNumeric` 会转整数并 clamp 0–100，本实现不继承它。
- 6.0.10 jar 核实 `ModularGuiLineBuilder.addSelectionScrollInput(int,int,BiConsumer,String)`、`SelectionScrollInput.forOptions(List)`、`ScrollInput.titled(MutableComponent)` 与 `CreateRegistries.DISPLAY_SOURCE`。
- 6.0.10 `assets/create/lang/zh_cn.json` 已有 `create.display_source.fill_level.percent=百分比`、`.display=显示格式`。
- Ponder 1.0.82（Create 6.0.10 的 nested 版本，也是模块现有依赖）javap 确认 `PonderPlugin#registerTags(PonderTagRegistrationHelper<ResourceLocation>)`、`addToTag(ResourceLocation)`、`PonderIndex.addPlugin(PonderPlugin)`；本地 CDG `ponder/CDGPonderPlugin.java` 有向 Create 标签追加组件的既有范例。

### 浮空材质与覆盖顺序

- `dev.eriksonn.aeronautics.aeronautics-neoforge-1.21.1-1.3.0.jar`：`data/aeronautics/tags/block/levitite.json` 同时包含普通、珠光水晶；`physics_block_properties/levitite.json` 将该 tag 映射到 `sable:floating_material=aeronautics:levitite`。只需覆盖一个材质文件。
- `E:/misc/sable/common/src/main/java/dev/ryanhcode/sable/physics/config/FloatingBlockMaterialDataHandler.java:37`：reload listener 从 `floating_materials` 加载，按资源 ID 建表。
- 同仓 `physics/floating_block/FloatingBlockMaterial.java:13` 起：codec 正是这四个 slow/fast horizontal/vertical friction 字段；`FloatingBlockController.java:307`、`:310` 用四值构建阻力矩阵，浮力路径另读 liftStrength，故保留浮力参数。
- `dimblend-experience/.../compat/sable/SableLavaMelt.java:228`、`:329` 已先判断 `lava_melt_immune`，追加 tag 即复用现有免疫路径。
- 运行实例 `launch.args.txt` 确认为 FML **4.0.44**；本机 Gradle cache `net.neoforged.fancymodloader/loader/4.0.44/.../loader-4.0.44-sources.jar` 中 `ModSorter.java:162` 从拓扑排序结果产生 modFiles，`:178` 的 AFTER 建立 target → self 边；`ModLoader.java:101` 将此有序列表传给 ModList。
- NeoForge **21.1.249** sources jar 中 `net/neoforged/neoforge/resource/ResourcePackLoader.java`（本机展开本 `E:/misc/dimblend/build/mcsrc/...`）`:81` 起按 `ModList.get().getModFiles()` 写入 LinkedHashMap；`:94` 起按同序收集 hidden packs；`:195` 起将它们作为 mod_data 子包；`:250` 起展开时保持子包顺序。
- 原版 `FallbackResourceManager#getResource` 从末尾向前查资源；`SimpleJsonResourceReloadListener#scanDirectory` 使用资源管理器选中的文件。因此 **experience 声明可选 AFTER aeronautics** 可保证本 mod 的同 ID 数据覆盖上游，无需另加物理 hook。玩家更高优先级的数据包仍能自行覆盖。

## 共享文件：由集成者串行接入

### `DimBlend.java` 构造函数

在 Create + Simurail 门控内、注册阶段调用一次（不要放到 commonSetup 才挂 DeferredRegister）：

```java
if (ModList.get().isLoaded("create") && ModList.get().isLoaded("simurail")) {
    dimblend.experience.compat.simurail.TractionDisplaySources.register(modEventBus);
}
```

### `client/DimBlendClient.java` 的 onClientSetup

```java
if (net.neoforged.fml.ModList.get().isLoaded("create")
        && net.neoforged.fml.ModList.get().isLoaded("simurail")) {
    event.enqueueWork(() -> net.createmod.ponder.foundation.PonderIndex.addPlugin(
            new dimblend.experience.client.ponder.SupplementPonderPlugin()));
}
```

### `resources/dimblend_experience.mixins.json` 的 common mixins 数组

```json
"OriginCompassMixin",
"compat.simulated.NavigationCompassMixin"
```

### `mixin/DimBlendMixinPlugin.java` 的 shouldApplyMixin

```java
if (mixinClassName.contains(".compat.simulated.")) {
    return isLoaded("simulated");
}
```

OriginCompassMixin 为 vanilla 目标，无额外 mod 门控。Simulated 使用 `@Pseudo` + string target，无需新增其编译依赖。

### `resources/assets/dimblend_experience/lang/{zh_cn,en_us}.json`

```json
"dimblend_experience.display_source.traction_efficiency": "牵引效率"
```

```json
"dimblend_experience.display_source.traction_efficiency": "Traction Efficiency"
```

标签、百分比格式和格式标题复用 Create，无其他新翻译。

### `src/main/templates/META-INF/neoforge.mods.toml`

```toml
[[dependencies.${mod_id}]]
    modId="aeronautics"
    type="optional"
    versionRange="[1.3.0,)"
    ordering="AFTER"
    side="BOTH"
```

无需改 build.gradle：Create、Ponder、Simurail 都已有相应依赖；Simulated/Aeronautics 不新增编译依赖。

### GameTest 注册（在专用完整兼容验证环境）

`SupplementCompassGameTests` 不加自动注册注解，避免默认 dev 环境缺旋转维度/导航台时假绿或无意义报红。按主编排的专用测试开关接入 `onRegisterGameTests`，且至少门控：

```java
if (ModList.get().isLoaded("dimblend") && ModList.get().isLoaded("simulated")) {
    event.register(dimblend.experience.gametest.SupplementCompassGameTests.class);
}
```

模板复用现有 `dimblend_chassis:empty`。只有这一个 GameTest 同步修改出生点数据，finally 恢复；不调用 setDefaultSpawnPos，以免创建出生点 tickets。它直接调用已混入的 vanilla / Simulated 方法，使用真实维度和真实导航台 BE（反射仅用于避免编译依赖），覆盖普通、原版绑定、自有失效绑定、其他维度、非普通物品。无缺依赖时直接 succeed 的跳过路径。

## 验证交接

本单元已做：文件读取复核、API/源码/字节码取证，以及 JSON/空白等静态检查。以下测试均**已编写、未执行**：

- JUnit `TractionEfficiencyTest` 共 7 项：括号与单位（2m/s、12rpm →100%；31.5m/s、256rpm →50%）；四种正负组合；正负 4rpm 边界；紧邻阈值；>100% 不截断；四舍五入到两位；停驶/未输入差异；非有限遥测。
- GameTest `SupplementCompassGameTests` 共 1 项，依赖上述完整运行环境及共享注册。

集成后由主编排串行安排：

1. JDK 21 下编译和定向 JUnit，再按完整兼容运行环境执行指南针 GameTest。
2. TrainTripWorld 实机：出生点设在非原点，旋转维度手持、快捷栏、物品栏普通指南针与导航台都指向原点；其他维度与有效/失效磁石绑定保持上游行为。有效 Sable 子层级磁石移动后仍追踪投影位置。客户端渲染优先级与有效移动磁石不由本 GameTest 覆盖。
3. 显示连接器首次放下、打开原生界面，确认数据源名/自填 Label/唯一百分比三项，提交后输出；检查 0、±4、±5rpm、反向行驶、>100%、两位小数，并确认标签与配置重载后保留。查看显示来源思索 tag 内的物理转向架。
4. 进世界确认 `aeronautics:floating_materials/levitite.json` 实际选中的 sourcePackId 来自 experience，四个 friction 均为 0（可用资源管理器查询）；普通/珠光水晶都有浮力、视觉和零额外材质阻力；载具浸入岩浆后两种方块不熔毁。其他材质阻力与其他方块熔毁仍沿用原行为。

技能经验：本次取证属于该需求的版本化机制，已记录在本文；无需回写通用 dimblend-dev 技能，且未越出独占文件修改它。
