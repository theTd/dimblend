# 2026-11-10 死亡保护、车钩启动范围：实现交接

状态：独占范围内代码审查、修复和测试代码已完成；**本轮未启动 Gradle、编译器或游戏，尚未编译/执行这些新增测试，也未完成实机验收**。先前第一组 compileJava 通过的记录不覆盖本轮改动。

## 范围与文件

本轮只在 `E:\codexworktrees\supplement-20261110\dimblend` 内编辑：

- `dimblend-experience/src/main/java/dimblend/experience/exploration/BackpackTotemProtection.java`
- `dimblend-experience/src/main/java/dimblend/experience/exploration/DeathRules.java`
- `dimblend-experience/src/main/java/dimblend/experience/mixin/ServerPlayerTotemDeathMixin.java`
- `dimblend-experience/src/main/java/dimblend/experience/compat/simurail/CouplerCharge.java`
- `dimblend/src/main/java/dimblend/api/RegionLanes.java`
- 新增 `dimblend-experience/src/main/java/dimblend/experience/gametest/BackpackTotemGameTests.java`
- 新增 `dimblend/src/test/java/dimblend/worldgen/SupplementSurfaceLaneMathTest.java`
- 本文。

同属独占范围的 `FoodRecordDeathMixin.java`、`AutomaticCouplerWrenchMixin.java` 和 `SupplementTooltips.java` 已审查，保留第一组的逻辑。共享入口、mixin JSON/plugin、语言文件、metadata、Gradle、其它单元文件未由本轮修改。没有 stage/commit/push、ACL 修改、持久授权规则、构建、游戏进程或子代理。

## 本轮修复

### 经验与保护标记

死亡被 NeoForge 接受后，`begin` 保存 level/progress/total 三个独立值，扫描 `Inventory.items` 的 36 格并只兑换一枚图腾。缺真实 `kaleidoscope_grilling:totem_powder` 时不建立标记、不消耗、不提供部分保护。不扫描装备、副手和容器内容；手持图腾的原版复活发生在 `die` 之前。

`DeathRules.onClone` 优先恢复受保护快照，绕过旋转维度的经验缩放。受保护死亡在所有维度取消经验球掉落；普通死亡仍保留已有的旋转维度规则。经验掉落监听改为 LOWEST，避免一般优先级的掉落数量改写绕过本规则。

第一组只在 Clone 监听器恢复经验，后续监听器仍能改写新玩家经验。本轮在 `ServerPlayer.restoreFrom` RETURN 最终调用 `finishClone`：死亡克隆再次恢复死亡快照，然后清理旧、新两个实体上的标记。这样，尸体后来获得/改变经验、玩家坐标移动、后续 Clone 监听器重写经验，均不改变保护结果；其它 mod 如果复制所有 persistent data，也不会把标记带进下一条命。`keepEverything=true` 的非死亡克隆不恢复死亡快照。

标记在所有 Clone 监听器执行期间保持可见，S.O.L 的监听器不依赖本模组监听器的注册先后。如果克隆抛出异常，没有执行 RETURN，旧标记仍保留，重试不会再消费一枚；完成克隆后才清理。重复进入 `die` 也不会重新消费。

### 粉末服从死亡掉落规则

普通单枚图腾原槽位换成粉末，由原生 `dropEquipment` / `keepInventory` 处理。

另修复了堆叠图腾加满背包的边界：第一组在 `Inventory.add` 失败时提前调用 `Player.drop(stack, boolean)`。这个重载走 ItemTossEvent，且此时死亡物品收集尚未开始，可能绕过 LivingDropsEvent 的取消规则。本轮改为在死亡标记中暂记一枚待落粉末；`DeathRules.onDeathDrops` 在 HIGHEST、接收已取消事件时，把该粉末加入本次真实死亡掉落集合。

具体复用 `captureDrops(event.getDrops())` 和三参数 `ServerPlayer.drop(stack, true, false)`，在 finally 恢复之前的收集容器；不直接生成替代物品、不绕过原生掉落位置/运动/拾取延迟。取消 LivingDrops 时粉末随其它掉落一起取消。满背包情况下即使 keepInventory 开启，额外且无法放入物品栏的粉末也走同一溢出掉落集合；已有背包物品不受影响。

二轮审查修复（P2，创造模式满包）：NeoForge patched `Inventory.add` 在无容量且 `hasInfiniteMaterials()` 为真时会清空传入 stack 并返回 true，不能把其返回值或清空后的余量当作确实存入。现改为先用 `getSlotWithRemainingSpace` 找真实合并容量，再用 `getFreeSlot` 找主物品栏空位；产物固定只有一枚，因此直接向已确认有容量的 stack 增加一枚，或存入空位，保留原版 popTime=5。无容量时只记录 `overflow_powder`，由既有死亡掉落集合处理；不调用 `Inventory.add`，不临时切换创造能力，不提前抛出物品。合并位仍沿用原版选择顺序，包含可合并的副手粉末，但图腾扫描范围仍只有主 36 格。

创造满包的 `/kill` 在 keepInventory=false/true 两种设置下均只兑换一次；满包保留背包时，放不下的额外粉末仍由可取消的死亡掉落集合处理，不挤掉已有物品。本轮仅修改本文件、`BackpackTotemProtection.java` 和 `BackpackTotemGameTests.java`；共享 build/注册入口继续冻结。

### S.O.L

保留 `FoodRecordDeathMixin` 对 `CapabilityHandler.onClone` 内 `shouldResetOnDeath()` 返回值的单次覆盖：只有旧玩家带受保护标记时返回 false，不修改全局配置。继续由 S.O.L 自己复制 FOOD_ATTACHMENT、在 PlayerRespawnEvent 同步食物列表并更新额外生命上限。下一次无图腾死亡恢复原配置的清空行为。

该注入目标是 static 方法，所以 handler 必须为 private static；没有新增非 private static mixin 成员或 mixin 包内 helper。

### 车钩

只在从未充能切到充能时调用核心 API。服务端用已有 `SableWorldPosition.projectBlock` 投影车钩中心，再交给 `RegionLanes.isSurface`；API 同时要求真实 `dimblend:rotating` ServerLevel、真实 RotatingChunkGenerator 和 surface lane，复用世界生成使用的带宽、正负区域与随机种子布局，不按 Y、高度或玩家位置猜区域。

`RegionLanes` 改为复用已有 `BandLayout.isSurfaceOverworld`，experience 不导入 worldgen 实现。核心 mod 存在的检查之后才进入独立内部类 `CoreRegions`，将核心类的解析边界明确隔离；无核心时拒绝启动，已充能仍可手动关闭。

服务端继续权威修改并 `sendData`；客户端只确认交互，不更改充能。拒绝启动返回 FAIL，挡住原生 `cycleType`。已充能跨区不会被地区门重新判断；`reconcile` 沿用原有伙伴加载检查和附近 2 格扫描，不因跨区自动清空。原有 Simurail `isPowered` 解锁链路保持复用。

### 文案

已核对现有中文语言键和 tooltip 分流，四条与用户口径逐字一致：

- totem：`放进背包里可保护经验和额外生命值`
- Create wrench，按住 SHIFT：`对车钩使用时：可在安全区域临时解除车钩一次`
- `aeronautics:levitite_blend_bucket`：`用于列车时 可有效减重 且不产生阻力`
- 原版玩偶集合 tag：`它也会是称职的仓库管理员`

未新增其它文案。

## 本机源码证据

以下 NeoForge 源码均从 `E:\misc\dimblend\build\moddev\artifacts\neoforge-21.1.249-sources.jar` 只读读取；行号为 zip 内文件行号，未解包写入主仓库。

| 来源 | 核实内容 |
| --- | --- |
| `net/minecraft/server/level/ServerPlayer.java:685–725` | `die` 先 post CommonHooks.onLivingDeath，取消直接 return；之后才 dropAllDeathLoot。ModifyExpressionValue 命中最终取消结果，晚优先级取消也不会消费。 |
| 同文件 `1437–1488` | `keepEverything` 和 gamerule keepInventory 是不同分支；原生经验/物品复制之后在 1485 调 onPlayerClone；RETURN 位于整个 Clone 分发之后。 |
| 同文件 `1898–1915` | 三参数 drop 在 captureDrops 非 null 时只加入收集集合，否则才 addFreshEntity。 |
| `net/minecraft/world/entity/player/Player.java:693–698` | `dropEquipment` 仅在 keepInventory=false 时清背包并掉落。 |
| `net/minecraft/world/entity/player/Inventory.java:52–66,189–225,252–288` | 合并位检查完整组件、可堆叠及容量，空位只查主物品栏；addResource 设置 popTime=5。add 的创造分支会吞掉未插入物品并返回 true，故单枚粉末改为先查容量再明确转移。 |
| 同文件 `724–766` | 二参数 drop 进入 CommonHooks.onPlayerTossEvent；三参数 drop 创建标准玩家掉落物。 |
| `net/minecraft/world/entity/LivingEntity.java:1462–1489` | 死亡先开启 captureDrops，随后物品和经验掉落，恢复容器后分发 LivingDropsEvent，未取消才 addFreshEntity。 |
| `net/neoforged/neoforge/common/CommonHooks.java:362` | onLivingDeath 返回实际事件的最终取消状态。 |
| `net/neoforged/neoforge/event/EventHooks.java:399–404,593–595,922–924` | 取消经验事件返回奖励 0；Clone/respawn 的原生事件分发。 |
| `net/minecraft/server/players/PlayerList.java:456–505` | 正式重生先 restoreFrom，再移动新玩家、发送经验包、最后 firePlayerRespawnEvent；测试调用同样的 restoreFrom 和 respawn 事件路径。 |
| `net/neoforged/neoforge/common/extensions/IEntityExtension.java:70–81` | captureDrops 可更换/恢复容器，persistent data 会保存。 |

原版伤害路径核实：`E:\misc\mcsrc-1.21.1\net\minecraft\world\entity\LivingEntity.java:1191,1235–1269`，手持图腾先于 die，bypasses_invulnerability 跳过手持图腾；同源码根 `net/minecraft/data/tags/DamageTypeTagsProvider.java:41` 将 GENERIC_KILL 放入该 tag。测试以 genericKill 隔离背包扫描与副手复活。

S.O.L 本机查找未发现 SOLCarrotConfig.java / CapabilityHandler.java 或独立源码副本，随后只读 `javap -p -c` 核对 `E:\misc\TrainTripWorld\mods\solcarrot-1.21.1-1.16.6.jar`：

- `CapabilityHandler.onClone(Clone)` 偏移 0–13：wasDeath && shouldResetOnDeath 时 return；14–35：从旧玩家 FoodList.get，写入新玩家 FOOD_ATTACHMENT。
- `CapabilityHandler.onPlayerRespawn` 调 `syncFoodList`；`syncFoodList` 同步后调用 MaxHealthHandler.updateFoodHPModifier。
- `MaxHealthHandler.onPlayerClone` 复制已有食物血量 modifier；updateFoodHPModifier 根据当前食物里程碑重新计算，所以只保 modifier、不保 FoodList 会在重生同步时失效。
- FoodList.get/addFood/hasEaten，Server 配置字段和 ProgressInfo API 已核对；测试反射只用于可选 mod 的 fixture，生产代码无此反射。

车钩与投影：

- `E:\misc\Create-Simurail\src\main\java\com\crystaelix\simurail\content\automatic_coupler\AutomaticCouplerBlock.java:133–138`：原生扳手 cycleType、播放原生音效、SUCCESS。
- 同目录 `AutomaticCouplerBlockEntity.java:226,332–355,413–458`：isPowered 的既有解除/拒绝挂接路径与查找；本轮不重写物理。
- `E:\misc\sable\common\src\main\java\dev\ryanhcode\sable\ActiveSableCompanion.java:187–204`：projectOutOfSubLevel 按包含的子层级姿态转换；无子层级直接返回位置。
- 本工作树 `RotatingChunkGenerator.delegateForBlockX` / `bandIndexBlock` 复用实际 layout；`BandLayout.regionOfBlockX` 使用 floorDiv，lane 分类包含 surface 与 underground 的区别。
- `E:\misc\Create\src\main\java\com\simibubi\create\content\equipment\wrench\IWrenchable.java:79–80`：playRotateSound 复用 Create 的 playOnServer；保留其现有调用。

## 新测试及意义

GameTest 主类现为 12 项，外加 `WithoutPowder` 负向类 1 项；其中二轮新增的两项仅编写并静态复核，未执行。测试不直接调用生产 begin/restore，不伪造注册粉末；玩家复用 BedRestGameTests.player 的真实 ServerPlayer fixture，执行 hurt/die 与织入后的 restoreFrom，再通过 EventHooks 发正式 respawn 事件。

| 测试 | 覆盖 |
| --- | --- |
| everyMainInventorySlotProtectsOneActualDeath | 36 格逐格覆盖（测试快捷栏时选中另一格）；真实死亡不复活、粉末进入死亡掉落、三项 XP 原值、无经验球、两实体标记清理。 |
| keepInventoryConsumesOnceEvenIfDeathIsReentered | keepInventory=true、两枚独立图腾、重复 die 仍仅兑换一次、原生背包复制。 |
| fullInventoryStackedTotemPowderObeysCancelledDeathDrops | 合法 MAX_STACK_SIZE 组件模拟可堆叠图腾，主背包及副手填满；保留一枚、粉末进入可取消的死亡掉落集合，不在世界泄漏。第一组提前 ItemToss 的实现会失败。 |
| creativeFullInventoryStackedTotemPowderObeysDeathDropRules | 创造模式主 36 格及副手填满，堆叠两枚图腾；以 genericKill 执行实际死亡并重入 die，分别覆盖 keepInventory=false/true，断言只消费一枚、死亡集合中恰好一枚粉末、取消后无世界泄漏、能力保持创造、克隆 XP 与物品规则正确。旧 add 实现会丢失粉末，无法满足死亡集合断言。 |
| creativeStackedTotemPowderUsesAvailableInventorySpace | 创造模式 keepInventory=true，分别提供主物品栏空位、主物品栏可合并粉末、副手可合并粉末；通过真实死亡/克隆断言准确插入一枚，无溢出掉落且物品保留，避免把有容量的情况一律改成溢出。 |
| cancelledDeathDoesNotSpendATotem | LOWEST 优先级取消实际 LivingDeathEvent，图腾/经验/标记均不改变。 |
| heldVanillaTotemRevivesWithoutBackpackExchange | 主手、副手原版复活各一次，额外背包图腾不 double-trigger。 |
| armorOffhandAndNestedTotemsDoNotProtect | genericKill 绕过手持复活，装备/副手/shulker 内容全部排除，原有死亡缩放继续生效。 |
| snapshotWinsAfterMovementCorpseChangesAndLateCloneListeners | 在旋转维度外测试保护；尸体 XP 改动、移动、LOWEST Clone 改写 XP 和复制 persistent data；最终值仍为死亡快照且清理两端标记。第一组只清 marker 的 RETURN 实现会失败。 |
| nonDeathCloneDoesNotRestoreADeathSnapshot | keepEverything=true 的 WasDeath=false 克隆保持原生当前经验，不强行恢复死亡快照。 |
| solRecordAndExtraHealthSurviveBothResetSettings | S.O.L reset=false/true 都保食物记录和真实新增 HP；全局配置未被生产逻辑改写。 |
| nextUnprotectedDeathResetsSolAndXpIncludingKeepInventory | 两种 keepInventory 下先保护一次，再无图腾死亡；记录和 extra HP 按原配置清空、原有 XP 缩放恢复。 |
| WithoutPowder.missingOptionalPowderLeavesTotemAndOrdinaryDeathRulesAlone | 真正省略 Grilling 的运行，保留原图腾、不提供部分保护，XP 与 S.O.L 正常清空。 |

`SupplementSurfaceLaneMathTest` 新增 3 项：正负带边界（尤其 -2049/-2048）、可配置带宽、两侧远端随机区域按 seed 变化；delegate 顺序故意打乱，避免把 index 0 误认为地表。只验证现有布局数学，不替代 Sable 投影、真实 ServerLevel API 和 mixin 路径的运行验收。

测试逐次恢复修改过的 gamerule、experience 配置和 S.O.L 配置，并注销临时监听器。物品监听取消正常死亡掉落，防止 fixture 污染世界。这些测试同步执行，不留跨 tick 的全局配置窗口。

二轮合并场景使用的真实粉末已定点核对 `kaleidoscope_grilling-1.1.1-neoforge1.21.1.jar`：`ModItems` 将 totem_powder 经 `ingredient(String)` 注册，`lambda$ingredient$37` 使用默认 `new Item(new Item.Properties())`，没有独立限堆叠设置。本机未发现对应 Java 源，才读取该 jar 字节码；没有创建替代粉末。

## 共享接入交接与当前状态

以下入口由 codex-89bee536 后续完成并冻结，二轮已只读确认；本单元未编辑共享文件。具体开发 fixture 与完整运行闭包见 `supplement-integration.md`，该文记录的死亡主类 10 项是二轮补测前数量，当前实际为上文 12 项。

1. mixin 列表保留 `ServerPlayerTotemDeathMixin` 和 `compat.solcarrot.FoodRecordDeathMixin`；plugin 对 `.compat.solcarrot.` 使用 `isLoaded("solcarrot")`。当前工作树第一组已存在这些内容，本轮未再次修改。
2. 客户端现有 `SupplementTooltips::onItemTooltip` 注册和四条语言键当前已存在，集成时保留。无需新增文案。
3. `DimBlend.onRegisterGameTests` 中仅在 solcarrot 存在时注册；存在 Grilling 注册主类，否则注册 `WithoutPowder`。建议仅在 withSupplementDeath 的独立运行配置启用注册（系统属性作门），避免其它 GameTest 因未提供模板而误加入。

```java
if (Boolean.getBoolean("dimblend.test.supplementDeath") && ModList.get().isLoaded("solcarrot")) {
    if (ModList.get().isLoaded("kaleidoscope_grilling")) {
        event.register(dimblend.experience.gametest.BackpackTotemGameTests.class);
    } else {
        event.register(dimblend.experience.gametest.BackpackTotemGameTests.WithoutPowder.class);
    }
}
```

4. 将已有 `src/main/resources/data/dimblend_sleep/structure/empty.nbt` 逐字节复制为 `src/main/resources/data/dimblend_supplement_death/structure/empty.nbt`。该共享资源不在本单元写清单内，已由集成者创建；二轮只读确认与来源逐字节相同（204 字节）。
5. `build.gradle` 增加 `-PwithSupplementDeath` 的独立 gameTestServer 配置：systemProperty `dimblend.test.supplementDeath=true`；enabledGameTestNamespaces=`dimblend_supplement_death`；使用独立 `build/supplement-death-gametest-run` gameDirectory。不要混到其它 GameTest 的持久世界。
6. runtime 依赖用显式传入实例目录的 `-PsupplementModsDir=...`，只选择以下真实已安装 jar，不把整个 mods 目录放入 dev，也不添加伪造粉末。测试通过反射访问 S.O.L，因此无需为这些测试新增 compileOnly 依赖。

| 运行 | 必要 localRuntime |
| --- | --- |
| 正向，withSupplementDeath | `solcarrot-1.21.1-1.16.6.jar`；`kaleidoscope_grilling-1.1.1-neoforge1.21.1.jar`；`[森罗物语：厨房] kaleidoscopecookery-1.4.1-neoforge+mc1.21.1.jar`（Grilling 必需依赖）。已有 Sable dev 运行依赖保持项目原配置。 |
| 负向，withSupplementDeath + 建议开关 supplementNoPowder | 仅以上 S.O.L jar，真正省略 Grilling；可一并省略 Cookery。注册 WithoutPowder。 |

依赖事实来自这些 jar 的 `META-INF/neoforge.mods.toml`：Grilling 1.1.1 要求 kaleidoscope_cookery>=1.4.1；S.O.L 1.16.6 没有额外第三方强制依赖。运行不需要核心维度 mod；世界相关车钩验收另在整合包执行。

集成后由主代理检查 CPU/内存等资源，串行安排 JDK 21 的 compileJava、compileTestJava 和定向单测，再执行两次 GameTest。建议命令（**本轮未执行**）：

```text
gradlew.bat :dimblend:test --tests dimblend.worldgen.SupplementSurfaceLaneMathTest
gradlew.bat :dimblend-experience:runGameTestServer -PwithSupplementDeath -PsupplementModsDir=E:\misc\TrainTripWorld\mods
gradlew.bat :dimblend-experience:runGameTestServer -PwithSupplementDeath -PsupplementNoPowder -PsupplementModsDir=E:\misc\TrainTripWorld\mods
```

## 尚需运行验证与风险

- 本轮只有只读源码/API 核对、UTF-8/空白检查；没有测试通过数字，不能宣称本轮 compile 或 runtime 已通过。新测试反射签名已对实际 jar 查证，仍需运行确认实际注册、配置、网络 payload 和 mixin 应用。
- 真实车钩验收必须在 TrainTripWorld：载具局部坐标与投影所在区域不同；在地表成功启动后跨到地下/下界/其它区仍保持（附近另钩仍在 2 格内），区外可手动关闭、不可重新启动；无其它车钩后按既有 lazyTick 自动清空。边界以车钩中心投影所在方块为准。
- 单独加载 experience + Simurail/Sable、缺核心 dimblend 时验证拒绝启动且不产生 NoClassDefFoundError；已有充能状态可关闭。该场景没有运行过，内部类隔离仅完成代码审查。
- 需要实际走死亡/重生及 S.O.L 心数、经验包、物品掉落路径；单位测试或启动菜单不能验证 mixin 应用与客户端显示。还应在实机看四种 tooltip，按 SHIFT 检查扳手。
- 按用户独占文件约束不回写外部 dimblend-dev skill；本轮证据与可复用注意事项集中在本文，交主代理决定是否另行同步。
