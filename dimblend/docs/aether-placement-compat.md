# 旋转维度天境条带的禁放兼容

状态：代码与纯规则测试已写，尚未运行编译、测试或整合包验收。

`AetherPlacementBanCompat` 在游戏事件总线上监听 `PlacementBanEvent.CheckItem` 与 `CheckBlock`，只在 `dimblend:rotating` 的天境条带将 `setBanned(false)`。不删除配方，不取消不可取消的事件，不改原生 Aether 维度，不改变普通条带的规则。

服务器以事件坐标 X，通过 `RotatingChunkGenerator.layoutOrNull()`、`bandSize()` 和 `BandLayout.laneName(layout.delegate(region))` 判定；`region = floorDiv(blockX, bandSize)`，负坐标边界与生成器一致。缺失布局时不猜，不触发生成或扫描区块。

物品使用检查在客户端也发生。客户端没有世界种子及随机条带布局，按仓库现有 `VoidscapeBand` 的处理方式，用**事件坐标**的 Aether biome 做预测；服务器最终仍按条带权威验证。此客户端预测不使用玩家所在条带；接缝处客户端 biome 与权威条带不一致的情况仍需实机验证。

## 已核对的来源

运行版本为 `aether-1.21.1-1.5.10-neoforge.jar`，按类解读而非整包反编译：

- `PlacementBanEvent.CheckItem` / `CheckBlock` 继承 `Event`，不实现 `ICancellableEvent`；提供 `getLevel/getPos/setBanned`。
- `RecipeListener.checkBanned(RightClickBlock)` 调用 `RecipeHooks.checkInteractionBanned`，客户端同样调用，后者走 `ItemBanRecipe.banItem` → `AetherEventDispatch.isItemPlacementBanned` → `CheckItem`。
- `BlockBanRecipe.banBlock` 用 `matches(level, pos.below(), state)` 匹配配方，但发出的 `CheckBlock.getPos()` 是原 `pos`。条带只按 X 划分，`below()` 不改变归属；本实现按事件实际方块位置判断。
- `RecipeListener.onNeighborNotified` → `RecipeHooks.checkExistenceBanned` → `isBlockPlacementBanned` → `BlockBanRecipe.banBlock` → `CheckBlock`，所以同一监听覆盖已经放下后被邻居通知删除的路径；不会只修右键入口。
- `dimblend` 的 mod metadata 原本已声明 Aether required，独立 `@EventBusSubscriber(modid="dimblend")` 不需要主类再次注册。

编译依赖由共享构建文件整合：`compileOnly "maven.modrinth:aether:K5X5qMwG"`。版本 ID 的发布页为 [The Aether 1.21.1-1.5.10-neoforge](https://modrinth.com/mod/aether/version/K5X5qMwG)。不新增本机绝对路径依赖。

## 验证

`AetherPlacementBanRulesTest` 的四项测试覆盖：仅旋转天境条带放行、正/负坐标边界逐格、缺失布局及非法 bandSize 不猜、客户端预测仅限旋转维度内事件位置的 Aether biome。测试目前未运行。

整合包待验：在旋转天境条带内放置原被禁物品/方块并触发邻居更新，确认持续存在；对照原生 Aether、相邻普通条带保持原规则；站在一带点击另一带、正负 X 接缝与跨 Y 放置；客户端不再提前取消。Aether 的放置转换配方是另一套机制，本改动仅覆盖禁放检查。