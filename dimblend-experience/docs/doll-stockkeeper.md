# 玩偶仓储管理员兼容

状态：代码与回归用例已写；尚未运行编译、GameTest 或整合包实机。不可把下述验收项当作已通过。

## 合并后的两套管理员（2026-11-11）

本文件与 `docs/supplement-doll-implementation.md`（上游代理坐垫方案）在合并 master 后收敛为同一套服务端规则、两个可并存的入口，二者不重复注册、不重复播放声音、不重复放置：

- **代理坐垫入口（上游）**：手持 `kaleidoscope_doll:vanilla_dolls` 标签内物品右键普通 Create 坐垫 → 整格替换成 `dimblend_experience:<color>_doll_keeper_seat`（原坐垫属性的 `SeatBlock` 子类 + `DollKeeperSeatBlockEntity` 存档完整 ItemStack）。没有实体、没有 SeatEntity。
- **真实玩偶入口（本批）**：手持 `kaleidoscope_doll:doll_entity_item` 右键紧邻唯一发报机的空 Create 坐垫 → 上游物品逻辑生成 `kaleidoscope_doll:doll` 实体，再由 `SeatBlock.sitDown` 入座。

两条入口都只允许主手放置（见下文"玩家行为"），都只认紧邻唯一 ticker，都复用 Create 的权限/锁定/订货单/菜单规则。服务端 ticker 侧原本只看"坐垫上有人类/生物实体"，代理坐垫没有实体，因此追加 `DollStockTickerBlockEntityMixin` 让 `isKeeperPresent()` 在邻接合法代理 BE 时同样为真（不改 Create 的权限判定）；`getStockTickerPosition` 的 `LivingEntity instanceof` 只对真实 DollEntity 放宽。

## 玩家行为

主手拿 `kaleidoscope_doll:doll_entity_item`，不潜行，右键紧邻唯一仓储发报机的空 Create 坐垫：原玩偶物品生成并消耗一次，生成的 `kaleidoscope_doll:doll` 由 Create 的 `SeatBlock.sitDown` 入座。模型/持有物等原实体数据走玩偶模组原有读取路径。侧面点击也将生成位置放到坐垫上方，但原物品的点击面权限检查仍执行。

用户已于 2026-11-11 确认玩偶入座仅支持主手：副手点击目标坐垫消费本次交互，但不放置玩偶、不消耗物品，包括空坐垫。占用的目标坐垫同样不消耗物品、不生成第二个玩偶、不把乘客顶掉。潜行、假玩家、其它物品、没有唯一相邻发报机的座椅仍走原行为。

右键该玩偶使用 Create 原菜单、物流网络权限与请求逻辑。主手在服务器真正打开此发报机的菜单后，广播一次 `kaleidoscope_doll:block.duck_toy`；权限拒绝、购物清单处理、客户端预测、副手不播这次开菜单提示音。玩偶不会赋予玩家额外的网络管理权限。

请求界面单独保留玩偶及原座椅的弱引用；玩偶存活、仍坐原座椅且仍唯一指向本发报机时，抑制原 `containerTick` 的“没有管理员”关闭调用，其余刷新逻辑继续执行。离座、删除、换座或相邻发报机变成两个后恢复原关闭。不给原 `WeakReference<LivingEntity>` 填入玩偶，也不改管理员装饰/帽子渲染。

代理坐垫入口共用同一个客户端 mixin（合并后唯一入口）：邻接合法代理 BE 时同样抑制该关闭，并在 `renderBg` 尾部按上游原位置 2 倍渲染存档玩偶物品；失败/失效时走原关闭。两套判据同一处求值，代理与真实玩偶同时存在时不会互相覆盖。

## 取证版本与边界

- 运行 jar：`[机械动力] create-1.21.1-6.0.10.jar`。本机 `E:/misc/Create` 源码是 6.0.11，只用于阅读，关键签名及调用点已回 6.0.10 `javap -p -c` 核对。
- `StockTickerInteractionHandler.getStockTickerPosition(Entity)` 中 `LivingEntity INSTANCEOF` 唯一且之后无 `CHECKCAST`；只将该表达式扩成原结果或精确注册类型 `kaleidoscope_doll:doll`。原 Seat、PACKAGE 排除、水平四向及 y=0/1 扫描、唯一发报机判定不变。
- 真实 DollEntity 入座时 `StockTickerBlockEntity.isKeeperPresent()` 无需 Mixin：它本来只查相邻 `SeatEntity.isVehicle()`，玩偶坐上去即满足；但代理坐垫入口没有 SeatEntity（`DollStockKeeperInteraction.install` 会把该格 SeatEntity 全部 eject+discard），`isKeeperPresent()` 会返回 false，导致 Create 自己的 `StockTickerBlock` 右键回 `stock_ticker.keeper_missing`、`TableClothBlockEntity` 认定无管理员。2026-11-11 复核 6.0.10 字节码后追加 `DollStockTickerBlockEntityMixin`：只把 `isKeeperPresent` 的结果在邻接合法代理 BE（复用 `DollStockKeeperInteraction.keeperFor` 的唯一邻接 + 有效性判定）时提前置真，其余权限/锁定/菜单逻辑不改。真实 DollEntity 路径不走这条分支。
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
- `compat.create.stockkeeper.DollStockTickerBlockEntityMixin`（代理坐垫的 `isKeeperPresent` 适配）

client 列表添加 `compat.create.stockkeeper.DollStockKeeperRequestScreenMixin`。插件对整个 `compat.create.stockkeeper` 包同时检查 `create` 与 `kaleidoscope_doll`。没有新增玩偶编译依赖，兼容类通过精确注册 ID 与字符串 Mixin 目标保持可选加载。

Create 在场时注册 `DollStockKeeperGameTests`（`DimBlend.onRegisterGameTests` 的 Create 门控里唯一一次注册；本批侧原先那条 `dimblend.supplementTests` + sable + doll 的重复注册已在合并中删除）。合并后同一个类同时覆盖两套入口：代理坐垫用例用 `dimblend_compat_batch:empty`，真实 DollEntity 用例沿用 `dimblend_experience:copycat_obsidian`。若缺玩偶模组，每个用例入口统一记录 `[doll-stockkeeper-test] SKIPPED ... no runtime coverage` 并 `succeed()`，此情形只代表跳过，不能算功能验证。要执行真实用例需为测试运行装入实际玩偶 jar；禁止把本机绝对路径新增到构建依赖。

## 验证清单

GameTest 已覆盖但未运行：

本批侧（真实 DollEntity，4 项）：
1. 主手真实 `gameMode.useItemOn` 从物品入座，侧面点击、原模型数据、一次生成/一次消耗、玩家未抢座、重复点击/副手/已有乘客保护。
2. 坐垫及唯一相邻 ticker；第二个 ticker、未入座；原 LivingEntity 管理员正常、PACKAGE 仍排除。
3. 原座椅引用失效、换座、删除后界面存活条件撤销。
4. 原服务器交互真实打开菜单，只响一次；副手、购物清单和权限拒绝无提示音。

上游侧（代理坐垫，8 项 + 合并期新增 2 项）：
5. 生存安装：完整组件/颜色/含水/朝向存档与更新包往返；再次点击不重复消耗。
6. 非标签物品、零/高一格/两个 ticker 全部拒绝且保留原坐垫。
7. 生存破坏掉原色坐垫 + 完整玩偶各一份；ticker 移除只销毁一次；创造不消耗且回收玩偶。
8. 原生菜单类型/不坐人/不坐生物/直接 `sitDown` 不挂载；原生网络锁与订货单分支不变；Sable 装配后相对配对与数据。
9. （合并期新增）副手点击代理坐垫：消费交互但不放置、不消耗、不改方块。
10. （合并期新增）代理坐垫下 `StockTickerBlockEntity.isKeeperPresent()` 为真，且对 ticker 方块原生右键打开 Create 的 `STOCK_KEEPER_CATEGORY` 菜单（`DollStockTickerBlockEntityMixin` 的回归钉）。

整合包必须再实测：主手放置 → 右键打开 → 停留至少 40 tick → 库存/搜索刷新 → 下单 → 离座/删除关闭；锁定网络非所有者拒绝；同 Y 与高一格发报机；普通管理员、帽子路径和屏幕渲染不崩。GameTest 不创建客户端屏幕，不能代替此验收。

新增客户端待验路径（2026-11-11）：在同一仓储发报机相邻范围内同时放置有效坐垫玩偶与烈焰人燃烧室，打开请求界面后移除燃烧室，确认玩偶仍有效时界面持续打开且库存/搜索刷新正常；再移除或使玩偶离座，确认界面正常关闭。代理坐垫与真实玩偶两条入口都要各走一遍（同一界面渲染分支，判据分别求值）。现有服务器 GameTest 不创建客户端屏幕，不能覆盖构造器早退或此路径；本次只完成字节码与注入语义核对，未运行编译、GameTest 或客户端验收。
