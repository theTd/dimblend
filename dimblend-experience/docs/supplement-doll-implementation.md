# 玩偶仓库管理员：实现交接（2026-11-10）

状态：独占文件代码、资源和 8 个 GameTest 已编写；共享接线尚未修改，未编译、未运行测试、未启动游戏。不能据此宣称功能已通过验收。没有 stage、commit 或 push。

## 实现

- `compat/dolls/DollKeeperSeats.java`：16 色 `SeatBlock` 子类、一个共享 BE 类型；不注册 BlockItem，不新增配方。方块 ID 为 `dimblend_experience:<color>_doll_keeper_seat`；BE ID 为 `dimblend_experience:doll_keeper_seat`。
- `DollStockKeeperInteraction.java`：通过 `RightClickBlock` 双端处理安装与菜单交互。只接受现有 `VanillaDollItems.contains` 所判定的 `kaleidoscope_doll:vanilla_dolls`，并只转换 Create 自己注册的 16 色坐垫。安装严格检查同高度四向水平邻居中恰好一个 StockTicker；邻区块未加载时不安装，不发起加载。生存消耗一个，创造不消耗。安装时驱逐旧乘客并删除旧 SeatEntity；`EntityMountEvent` 阻止后续通过直接 `SeatBlock.sitDown` 等路径骑乘管理员坐垫。
- `DollKeeperSeatBlockEntity.java`：`copyWithCount(1)` 保存完整 ItemStack，包含全部数据组件；存档和更新包都写入 `Doll`。配对以方块 `FACING` 保存相邻方向，不保存世界绝对位置；方块旋转/镜像时同步转换方向。标准 BE NBT 迁移适配 Sable 装配。邻居通知即时检查原配对 ticker，另每 20 tick 对账；只在 ticker 所在区块已加载且 ticker 确实消失时 `destroyBlock(..., true)`，不把卸载当破坏。
- `DollKeeperSeatBlock.java`：保留原色、水状态、Create 碰撞形状与座椅模型。覆盖手持交互和跌落后自动落座入口。选择框包含上方玩偶，碰撞/遮挡仍使用原坐垫形状。中键取样得到原色 Create 坐垫，不产生可携带的管理员方块。正常掉落只走 `getDrops`，返回原色 Create 坐垫和一个完整玩偶；`onRemove` 不生成掉落，避免玩家破坏与 ticker 连带销毁重复计算。创造破坏遵守原坐垫不掉坐垫物品的行为，但单独取出并掉落一个玩偶。
- `client/dolls/DollKeeperSeatRenderer.java`：使用保存的实际 ItemStack 解析上游已有库存模型，NONE 变换，面向 ticker。读取已烘焙模型底部高度，把模型底部落在 Create 坐垫 y=8/16 顶面；无新模型/贴图，无活体实体。
- `client/dolls/DollStockKeeperScreenSupport.java` 与 `mixin/client/DollStockKeeperRequestScreenMixin.java`：仅在 ticker 邻接合法玩偶座椅时跳过 `containerTick` 中唯一的 `Player.closeContainer()`，库存刷新、JEI 同步、搜索、滚动等其余 tick 照常运行。`renderBg` 尾部在原管理员所在左下区域，以 2 倍缩放渲染实际玩偶库存物品（16×16 → 32×32）。不会阻止发送订单后的原生关闭路径。
- `assets/dimblend_experience/blockstates/*_doll_keeper_seat.json`：16 份直接引用 `create:block/<color>_seat`。`getDrops` 覆盖掉落生成，因此不需要新增 16 份 loot table。

交互调用严格是 `StockTickerInteractionHandler.interactWithLogisticsManagerAt(player, level, tickerPos)`。没有自建菜单、订单网络包或权限逻辑；手持订货单仍走 Create 自己的订货单分支。

安装后新增第二个相邻 ticker 不会直接销毁座椅；唯一性条件暂不满足时它不再是合法菜单管理员。原配对 ticker 消失则销毁座椅，绝不自动换配对。

## 集成者需要串行修改的共享入口

以下均未由本单元编辑。

### 通用注册

在 `DimBlend` 构造器、Create 在场分支中调用一次：

```java
dimblend.experience.compat.dolls.DollKeeperSeats.register(modEventBus);
```

该方法同时注册 Blocks、BE 类型以及游戏总线上的 RightClickBlock/EntityMountEvent 监听。只需要 Create 门控；生产代码没有直接引用玩偶 mod 或 Sable 的 Java 类型，玩偶 mod 缺席时空 tag 自然阻止安装。不能在每次世界加载时重复调用。注册属性供应器解析 Create 的原坐垫，metadata 的 Create 可选依赖应使用 `ordering="AFTER"`，保证其注册顺序。

### 客户端 renderer

在已有 `DimBlendClient`（Dist.CLIENT / MOD bus）加入：

```java
@net.neoforged.bus.api.SubscribeEvent
public static void onRegisterDollRenderers(
        net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
    if (net.neoforged.fml.ModList.get().isLoaded("create")) {
        event.registerBlockEntityRenderer(
                dimblend.experience.compat.dolls.DollKeeperSeats.BLOCK_ENTITY.get(),
                dimblend.experience.client.dolls.DollKeeperSeatRenderer::new);
    }
}
```

没有 Flywheel visual 注册，不应禁用此 BE 的普通 renderer。

### Mixin 清单与门控

在 `dimblend_experience.mixins.json` 的 `client` 数组添加唯一一项：

```json
"client.DollStockKeeperRequestScreenMixin"
```

在 `DimBlendMixinPlugin.shouldApplyMixin` 中对后缀 `.client.DollStockKeeperRequestScreenMixin` 返回 `ModList.get().isLoaded("create")`。不要添加 common mixin。本 mixin 只有 private 实例注入方法及 private shadow 字段；`require=1, allow=1` 限定两处注入匹配数。所有 helper 在正常的 `client/dolls` 包中。

### GameTest

`DimBlend.onRegisterGameTests` 在 Create、Sable、`kaleidoscope_doll` 同时在场时添加：

```java
event.register(dimblend.experience.gametest.DollStockKeeperGameTests.class);
```

测试引用现成 `dimblend_compat_batch:empty` 模板，无新增测试资源。需启用 `dimblend_compat_batch` 命名空间，并提供真实玩偶 mod 到 GameTest runtime（本机运行基线为 `kaleidoscopedoll-1.4.1-neoforge+mc1.21.1.jar`）。若集成者增加定向 Gradle 开关，推荐 `-PwithDollStockKeeper -PdollModJar=<真实 jar>`，将该 jar 加入 localRuntime，并设置 `neoforge.enabledGameTestNamespaces=dimblend_compat_batch`；这些构建文件改动留给集成者，不要求把本机绝对路径提交到仓库。

8 个用例：完整组件/蓝色/含水/朝向/存档与更新 tag；非 tag、零/高一格/两个 ticker 拒绝；生存正常破坏掉落；ticker 移除去重及管理员失效；创造不消耗及玩偶回收；原生菜单类型和禁止人/生物/直接 sitDown；原生网络锁与订货单处理；Sable 装配后的数据及相对配对。

注意 Create 6.0.10 服务端 `StockKeeperRequestMenu.create` 返回的是 `StockKeeperCategoryMenu`，但 MenuType 为 `AllMenuTypes.STOCK_KEEPER_REQUEST`。测试按 MenuType 断言，不能把服务端的 Java 类型误判为错误菜单。

### 数据与语言

既有玩偶 tooltip 不变，不重复添加。若统一数据生成/标签层需要保留斧头的采掘加速，把全部 16 个新方块加入 `minecraft:mineable/axe`（原坐垫通过 Create 的 axeOnly 注册标签；Properties 复制不会复制标签）。所有颜色为 `white, orange, magenta, light_blue, yellow, lime, pink, gray, light_gray, cyan, purple, blue, brown, green, red, black`，完整 ID 按前述模式生成。若为调试/Jade 增加方块译名，key 模式为 `block.dimblend_experience.<color>_doll_keeper_seat`；不影响既定玩偶 tooltip。

## 本机源码证据

Create 源码根：`E:/misc/Create/src/main/java/com/simibubi/create/`；已用整合包 `[机械动力] create-1.21.1-6.0.10.jar` 的 javap 对照构造器、方法和字段，并确认 containerTick 恰好一次 `Player.closeContainer:()V`。

| 行为 | 源码位置 |
| --- | --- |
| SeatBlock 构造器、落座、颜色 | `content/contraptions/actors/seat/SeatBlock.java:55,89,132,193,204` |
| 权限、原生菜单和订货单分支 | `content/logistics/stockTicker/StockTickerInteractionHandler.java:60,63,73,85,193` |
| 无生物/blaze 时关闭菜单 | `content/logistics/stockTicker/StockKeeperRequestScreen.java:401,456–459` |
| 原生管理员左下预览区域 | 同上 `:472,503,518` |
| 原生 request MenuType 的服务端实现 | `content/logistics/stockTicker/StockKeeperRequestMenu.java:29`；运行 jar javap 同样返回 CategoryMenu |
| 网络锁 | `content/logistics/packagerLink/GlobalLogisticsManager.java:34` |
| 原座椅几何顶面 y=8/16 | `E:/misc/Create/src/main/resources/assets/create/models/block/seat.json` |

MC 本机源：`E:/misc/mcsrc-1.21.1/net/minecraft/`。`world/level/Level.java:267` 在方块被替换前执行一次 Block.dropResources；`server/level/ServerPlayerGameMode.java:239` 在移除前捕获原 BE，生存通过 playerDestroy 生成掉落，创造不会进入该掉落路径。`client/renderer/entity/ItemRenderer.java` 渲染时把模型坐标平移 `(-.5,-.5,-.5)`，因此 renderer 的中心高度为 `1 - 模型底部 y`。

NeoForge 21.1.249 补丁源：`E:/misc/dimblend/build/moddev/artifacts/neoforge-21.1.249-sources.jar`。`net/neoforged/neoforge/common/extensions/IBlockEntityExtension.java:35,49` 默认均以 loadWithComponents 接收更新包/更新 tag。`net/minecraft/world/entity/Entity.java:2114` 在挂载前调用可取消的 mount 事件，包括 force=true 的 startRiding。

补丁版 `ServerPlayerGameMode.destroyBlock` 还确认了：先发可取消的 `CommonHooks.fireBlockBreak`，未取消才捕获 BE 并调用 playerWillDestroy；创造模式走 removeBlock 后直接返回，生存只在 canHarvestBlock 与实际移除均成功时执行 playerDestroy。`IBlockExtension.onDestroyedByPlayer` 服务端默认调用 level.removeBlock。创造回收不绕过破坏事件的取消。

Sable 源码根：`E:/misc/sable/common/src/main/java/dev/ryanhcode/sable/`。`api/SubLevelAssemblyHelper.java:385,415,436,452` 先保存完整 BE NBT、改坐标后 loadWithComponents，全部目标块就位后发通知，最后移除原方块；状态转换包含旋转。`mixin/plot/ServerChunkCacheMixin.java:89` 的 hasChunk 对 plot 查已有 chunk，不触发生成；所以配对查询使用父 Level 的 plot 坐标及 hasChunkAt 即可。

本机未找到玩偶 Java 源码；仅检查整合包 `kaleidoscopedoll-1.4.1-neoforge+mc1.21.1.jar` 内资源：`data/kaleidoscope_doll/tags/item/vanilla_dolls.json`、`assets/kaleidoscope_doll/models/item/doll_2.json`、对应 block model 与 blockstate。库存模型直接继承原有三维模型，朝向以 north=0/east=90/south=180/west=270 定义。

## 验证边界与实机清单

本单元遵守禁令，没有调用 Gradle、javac、游戏、安装/部署或任何重任务。已做源码/API 和运行 jar 定点对照、资源 JSON/模型引用的只读核对；编译、测试数字及截图均待主编排串行安排。

静态核对结果：本单元新增 25 个文件（8 Java、16 JSON、1 文档）；全部 UTF-8 解码通过；16 份 blockstate JSON 均可解析，引用的 Create 原模型均存在；无尾随空白，`git diff --check` 无报错。8 个 GameTest 只是代码数量，不是已通过数量。既有 `VanillaDollItems.java` 复用且未修改。

共享入口接好后至少验证：

1. 模块编译和 8 个定向 GameTest，记录真实结果；生产代码无需新的编译依赖。
2. TrainTripWorld 实机分别从主手/副手安装，16 色、含水、带名字/自定义组件的玩偶；非原版集合拒绝，零/两个/高一格 ticker 拒绝，生存恰耗一个。
3. 空手、手持其他物品、shift、牵引生物及跌落生物不能坐下、染色或拆出玩偶；可对上方可见玩偶和坐垫点击打开请求界面。
4. 无任何 living keeper/blaze 时 GUI 持续打开超过 20 tick，库存刷新/搜索/滚动/JEI 同步继续；实际玩偶图标为 32×32。验证网络锁、锁拥有者、订货单、下单及原生应关闭的路径。
5. 打破座椅、打破配对 ticker、爆炸、存档重进；生存回收原色坐垫和一个带完整组件的玩偶，创造取回一个玩偶。邻居更新与 BE 对账不产生第二份掉落。
6. Sable 装配、移动/转动、拆解、存档重进：相对朝向、数据同步、交互、模型姿态/光照和回收；检查 ticker 与坐垫跨区块边界的卸载/重载，未知区块不能误触发销毁。
7. 缺少玩偶 mod 时安装自然禁用；缺少 Create 时不注册这些类型且客户端 mixin 被 plugin 跳过。检查本 mod 重复日志，不能出现持续刷错。

其中 GUI mixin、实际模型朝向及 Sable 渲染必须真的走功能路径；启动或单测通过不能替代这一步。
