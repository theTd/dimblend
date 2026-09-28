# DimBlend Carwash

车辆脏污与清洗：车架方块随车厢行驶积灰，可洗净。纯视觉，不影响碰撞、质量、方块状态或任何玩法数值。

## 玩法

车架方块 = `dimblend_blocks` 的
- 创造模式伪装板，倒置（贴在格子顶部，`FACING=DOWN`）
- 创造模式伪装半砖，倒置（竖轴上半砖）或双层

每块车架有脏值 0–511：

| 触发 | 效果 |
|---|---|
| 车厢（Sable 子关卡）速度 &gt; 4 m/s | 每秒，车厢内每块车架各以 `速度/12`（封顶 100%）的概率脏值 +1 |
| 手持水桶 / 湿海绵右键车架 | 不倒水、不放方块、不贴材质；本块脏值非 0 则 -32，为 0 则任选一个脏值非 0 的相邻车架 -32 |
| Create: FireFighting Additions 水喷淋命中车架 | 视同一次上面的清洗；每 0.5 秒批量结算，期间被命中的车架各洗一次 |
| 手持泥土右键车架 | 不放方块、不贴材质；本块脏值 +32 |
| 下雨（不看遮挡） | 每秒所有车架脏值 -5（511 约 102 秒降到 0） |

外观（按当前脏值的档位 = 脏值 / 64，0–7；档位每变一次就换一张新的随机图，替换不叠层）：
- 泥：原版泥土贴图（只用 vanilla 16x16）随机去掉像素，第 1 档（64）去 90%，线性降到第 7 档（448）去 20%；
  贴在车架顶面、底面、四个侧面（侧面去掉下半）。
- 碎石：档位对应的倍数大于 256（320/384/448）时，上方一格再贴原版沙砾贴图随机去掉 50% 像素；
  上方是实心不透明方块则贴其四个侧面与顶面，否则贴该格四个侧面并去掉上半。
- 洗到下一档即换成该档的图，洗到 64 以下全部消失。

## 技术

- 脏值：Create `BlockEntityBehaviour`（`ChassisGrimeBehaviour`）挂在伪装方块 BE 上，随 BE 存盘、
  经 `sendData` 同步客户端。伪装方块 BE 不 tick，行为在首次读 NBT 时经 `BlockEntityBehaviourEvent`
  挂上，从未读过 NBT 的新 BE 在改值时补挂。不用方块状态：Sable 子关卡里每次改状态都会触发物理碰撞体/质量更新。
- 行驶/下雨：`ChassisTravelGrime` 每秒在服务端关卡 tick 遍历一次 Sable 子关卡，速度取
  `ServerSubLevel.latestLinearVelocity`（m/s），车架取自其 plot 区块的 BE；每块车架每秒合并成一次改值。
- 喷淋：`NozzleSprayWashing` 注册到 FireFighting Additions 的 `NozzleSprayInteractionRegistry`
  （可选依赖，缺席不注册），命中回调只把坐标去重记入 `ChassisSprayWashQueue`，每 0.5 秒同一刻结算。
  车厢里的方块经 Sable 的射线投影命中。
- 同步：只在外观档位变化时 `sendData`；行驶/雨水/喷淋都在固定刻集中结算，客户端每区段每秒至多重建
  1 次（行驶/雨）或 2 次（洗车）网格。改值只标区块待存盘，不走 `setChanged` 的邻居通知。
- 渲染：`ChassisGrimeModel` 包在 dimblend-blocks 换上的伪装模型外（mods.toml 对 `dimblend_blocks`
  声明 AFTER），cutout 层每面一张贴图：车架本体复制原模型的面只换贴图（位置、顶点顺序不变，不与材质打架），
  上方碎石按原版 FaceBakery 顶点顺序构造。镂空贴图由自定义图集精灵源 `GrimeSpriteSource`
  在资源加载时从 vanilla 包的泥土/沙砾贴图生成（`assets/minecraft/atlases/blocks.json`：泥 2 套 × 7 档 × 16 张，
  碎石 2 套 × 16 张，共 256 张 16x16）。

## 依赖

Create 6.0.10、Copycats+ 3.0、dimblend_blocks、Sable 2.0（必需）；Create: FireFighting Additions 0.2.1-beta（可选）。
Sable 编译期引用 `dimblend/libs` 下已入库的 cui-modded jar。
