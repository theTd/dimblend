# 玩偶仓储管理员兼容

状态：代码与回归用例已写；尚未运行编译、GameTest 或整合包实机。不可把下述验收项当作已通过。

## 玩家行为

主手拿 `kaleidoscope_doll:doll_entity_item`，不潜行，右键紧邻唯一仓储发报机的空 Create 坐垫：原玩偶物品生成并消耗一次，生成的 `kaleidoscope_doll:doll` 由 Create 的 `SeatBlock.sitDown` 入座。模型/持有物等原实体数据走玩偶模组原有读取路径。侧面点击也将生成位置放到坐垫上方，但原物品的点击面权限检查仍执行。

用户已于 2026-11-11 确认玩偶入座仅支持主手：副手点击目标坐垫消费本次交互，但不放置玩偶、不消耗物品，包括空坐垫。占用的目标坐垫同样不消耗物品、不生成第二个玩偶、不把乘客顶掉。潜行、假玩家、其它物品、没有唯一相邻发报机的座椅仍走原行为。

右键该玩偶使用 Create 原菜单、物流网络权限与请求逻辑。主手在服务器真正打开此发报机的菜单后，广播一次 `kaleidoscope_doll:block.duck_toy`；权限拒绝、购物清单处理、客户端预测、副手不播这次开菜单提示音。玩偶不会赋予玩家额外的网络管理权限。

请求界面单独保留玩偶及原座椅的弱引用；玩偶存活、仍坐原座椅且仍唯一指向本发报机时，抑制原 `containerTick` 的“没有管理员”关闭调用，其余刷新逻辑继续执行。离座、删除、换座或相邻发报机变成两个后恢复原关闭。不给原 `WeakReference<LivingEntity>` 填入玩偶，也不改管理员装饰/帽子渲染。

## 取证版本与边界

- 运行 jar：`[机械动力] create-1.21.1-6.0.10.jar`。本机 `E:/misc/Create` 源码是 6.0.11，只用于阅读，关键签名及调用点已回 6.0.10 `javap -p -c` 核对。
- `StockTickerInteractionHandler.getStockTickerPosition(Entity)` 中 `LivingEntity INSTANCEOF` 唯一且之后无 `CHECKCAST`；只将该表达式扩成原结果或精确注册类型 `kaleidoscope_doll:doll`。原 Seat、PACKAGE 排除、水平四向及 y=0/1 扫描、唯一发报机判定不变。
- `StockTickerBlockEntity.isKeeperPresent()` 原本检查 `SeatEntity.isVehicle()`，无需 Mixin。
- `StockKeeperRequestScreen` 的 `stockKeeper` 是 `WeakReference<LivingEntity>`；`init`、`containerTick`、`renderBg` 都取出后强转，不能把非 LivingEntity 玩偶塞入它。
- 2026-11-11 独立复核实际 6.0.10 字节码：`StockKeeperRequestScreen.<init>` 有两个 `RETURN`，偏移 687 为发现 `BlazeBurnerBlockEntity` 后的早退，700 为正常结束。Mixin 0.8.7 的 `BeforeFinalReturn.find`（`TAIL`）只收集最后一个返回点；`BeforeReturn.find`（无 ordinal 的 `RETURN`）收集全部返回点。因此构造器初始化改为 `@At("RETURN"), require=2, allow=2`，两条路径都查找玩偶；每次构造只经过其中一个返回点。`findDoll` 仍仅查找并赋同一组独立弱引用，可重复调用，不修改原 LivingEntity 字段。此前只用 TAIL 会漏掉燃烧室路径，导致玩偶与燃烧室共存时，移除燃烧室后仍有效的玩偶菜单错误关闭。
- `interactWithLogisticsManagerAt` 权限拒绝仍返回 `true`；实际 `ServerPlayer.openMenu(MenuProvider, Consumer)` 返回 `OptionalInt`。本实现比较真实 `player.containerMenu` 前后身份，并经 `MenuBase<?>.contentHolder instanceof StockTickerBlockEntity` 核对目标。不能仅凭布尔返回，也不能猜具体菜单类：Create 的 Request/Category 菜单是兄弟类型，服务器 request provider 实际返回 CategoryMenu。
- 玩偶 jar：`kaleidoscopedoll-1.4.1-neoforge+mc1.21.1.jar`。`DollEntity` 直接继承 `Entity`；`DollEntityItem.useOn` 原本只在点击面旁生成实体；`DollClickEntityEvent` 只处理点实体时的挂载，不能替代点击坐垫方块的入口。
- 入座桥只改变 `DollEntityItem.useOn` 中唯一 `BlockPos.relative(Direction)` 的结果，并包装唯一 `Level.addFreshEntity(Entity)`；原生成、数据读取、可放置性检查、消耗及创造模式恢复继续由上游执行。只在原添加实体成功后用公开 `SeatBlock.sitDown` 挂载。
- 本机 Sponge Mixin 0.8.7 不含 `INSTANCEOF` 原生注入点；使用已在编译/运行依赖中的 MixinExtras 0.5 `@Definition`、`@Expression("? instanceof LivingEntity")`、`@ModifyExpressionValue(at = @At("MIXINEXTRAS:EXPRESSION"))`，不升级基础依赖。所有 handler 为 private，静态目标的 handler 为 private static；除请求界面构造器两个返回点为 `require=allow=2` 外，其余注入点均 `require=allow=1`。

## 共享注册清单

整合者需在 experience mixin 配置的 common 列表添加：

- `compat.create.stockkeeper.DollStockTickerInteractionMixin`
- `compat.create.stockkeeper.DollSeatBlockInteractionMixin`
- `compat.create.stockkeeper.DollEntityItemSeatMixin`

client 列表添加 `compat.create.stockkeeper.DollStockKeeperRequestScreenMixin`。插件对整个 `compat.create.stockkeeper` 包同时检查 `create` 与 `kaleidoscope_doll`。没有新增玩偶编译依赖，兼容类通过精确注册 ID 与字符串 Mixin 目标保持可选加载。

Create 在场时注册 `DollStockKeeperGameTests`。它使用现有 `dimblend_experience:copycat_obsidian` 模板；若缺玩偶模组，明确记录 `[doll-stockkeeper-test] SKIPPED ... no runtime coverage`，此情形只代表跳过，不能算功能验证。要执行真实用例需为测试运行装入实际玩偶 jar；禁止把本机绝对路径新增到构建依赖。

## 验证清单

GameTest 已覆盖但未运行：

1. 主手真实 `gameMode.useItemOn` 从物品入座，侧面点击、原模型数据、一次生成/一次消耗、玩家未抢座、重复点击/副手/已有乘客保护。
2. 坐垫及唯一相邻 ticker；第二个 ticker、未入座；原 LivingEntity 管理员正常、PACKAGE 仍排除。
3. 原座椅引用失效、换座、删除后界面存活条件撤销。
4. 原服务器交互真实打开菜单，只响一次；副手、购物清单和权限拒绝无提示音。

整合包必须再实测：主手放置 → 右键打开 → 停留至少 40 tick → 库存/搜索刷新 → 下单 → 离座/删除关闭；锁定网络非所有者拒绝；同 Y 与高一格发报机；普通管理员、帽子路径和屏幕渲染不崩。GameTest 不创建客户端屏幕，不能代替此验收。

新增客户端待验路径（2026-11-11）：在同一仓储发报机相邻范围内同时放置有效坐垫玩偶与烈焰人燃烧室，打开请求界面后移除燃烧室，确认玩偶仍有效时界面持续打开且库存/搜索刷新正常；再移除或使玩偶离座，确认界面正常关闭。现有四个服务器 GameTest 不创建客户端屏幕，不能覆盖构造器早退或此路径；本次只完成字节码与注入语义核对，未运行编译、GameTest 或客户端验收。
