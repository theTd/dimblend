# DimBlend Carwash

车辆脏污与清洗：车架方块随车厢行驶积灰，可洗净。纯视觉，不影响碰撞、质量、方块状态或任何玩法数值。

## 玩法

车架方块 = `dimblend_blocks` 的
- 创造模式伪装板，倒置（贴在格子顶部，`FACING=DOWN`）
- 创造模式伪装半砖，倒置（竖轴上半砖）或双层

反例（有意设计，勿并入判定）：
- 洁净伪装板/半砖（`creative_clean_copycat_panel/slab`）按注册名判定不是车架：永不积脏，
  供洗车正常洗车够不到的死角使用；
- 隐藏车架（创造玩家对未贴材质的伪装板/半砖空手右键可隐藏）：隐藏期间脏值冻结不增长，
  已有脏污不渲染，右键洗/弄脏交互穿透（不取消事件，水桶照常倒水）。

每块车架有脏值 0–511：

| 触发 | 效果 |
|---|---|
| 车厢（Sable 子关卡）速度 &gt; 4 m/s | 每秒，车厢内每块车架各以 `(速度/12)×0.5`（封顶 100%，24 m/s 满概率）的概率脏值 +1 |
| 手持水桶 / 湿海绵右键车架 | 不倒水、不放方块、不贴材质；本块脏值非 0 则 -32，为 0 则任选一个脏值非 0 的相邻车架 -32；生效时以被洗砖块为中心播放 `minecraft:entity.slime.jump`（pitch 0.5） |
| Create: FireFighting Additions 水喷淋命中车架 | 视同一次上面的清洗（含生效音效）；每 0.5 秒批量结算，期间被命中的车架各洗一次 |
| 手持泥土右键车架 | 不放方块、不贴材质；本块脏值 +32 |
| 下雨（不看遮挡） | 每秒每块车架各以 50% 概率脏值 -1，降到 64 后雨不再洗（511 约 15 分钟降到 64） |

外观（按当前脏值的档位 = 脏值 / 64，0–7；档位每变一次就换一张新的随机图，替换不叠层）：
- 泥：原版泥土贴图（只用 vanilla 16x16）随机去掉像素，第 1 档（64）去 90%，线性降到第 7 档（448）去 20%；
  贴在车架顶面、底面、四个侧面（侧面去掉下半）。
- 碎石：脏值大于 256 时，上方一格再贴原版沙砾贴图随机去掉 80% 像素，此后每次行驶弄脏都重掷更换；
  上方是空气则不贴，是非透明实心方块则贴其四个侧面与顶面，其余情况贴该格四个侧面并去掉上半；
  贴面沿法线微微外移，不与上方方块自身材质叠面闪烁（z-fighting）。
- 洗到下一档即换成该档的图，洗到 64 以下全部消失。

## 技术

- 脏值：Create `BlockEntityBehaviour`（`ChassisGrimeBehaviour`）挂在伪装方块 BE 上，随 BE 存盘、
  经 `sendData` 同步客户端。伪装方块 BE 不 tick，行为在首次读 NBT 时经 `BlockEntityBehaviourEvent`
  挂上，从未读过 NBT 的新 BE 在改值时补挂。不用方块状态：Sable 子关卡里每次改状态都会触发物理碰撞体/质量更新。
- 隐藏对接（dimblend-blocks C5）：隐藏标记挂在对方 BE 上（`CreativeCopycatHidable`），本 mod 只经
  `dimblend.blocks.api.CreativeCopycatHiding` 查询（`compileOnly project(":dimblend-blocks")`，
  运行时本为必需依赖）。三个对接点：增长总闸 `ChassisGrimeBehaviour.changeDirt`（`delta>0` 且隐藏 →
  直接返回，覆盖行驶积灰/泥土弄脏/一切未来来源）、渲染抑制口 `ChassisGrimeBehaviour.visualAt`
  （隐藏即 CLEAN，泥与上方碎石一并消失）、`ChassisHandInteractions` 对隐藏车架直接放行不取消事件。
- 行驶/下雨：`ChassisTravelGrime` 每秒在服务端关卡 tick 遍历一次 Sable 子关卡，速度取
  `ServerSubLevel.latestLinearVelocity`（m/s），车架取自其 plot 区块的 BE；每块车架每秒合并成一次改值。
- 喷淋：`NozzleSprayWashing` 注册到 FireFighting Additions 的 `NozzleSprayInteractionRegistry`
  （可选依赖，缺席不注册），命中回调只把坐标去重记入 `ChassisSprayWashQueue`，每 0.5 秒同一刻结算。
  车厢里的方块经 Sable 的射线投影命中。
- 同步：只在可见快照变化时 `sendData`（外观档位变化，或碎石激活期间每次弄脏换图）；行驶/雨水/喷淋都在固定刻集中结算，客户端每区段每秒至多重建
  1 次（行驶/雨）或 2 次（洗车）网格。改值只标区块待存盘，不走 `setChanged` 的邻居通知。
- 渲染：`ChassisGrimeModel` 包在 dimblend-blocks 换上的伪装模型外（mods.toml 对 `dimblend_blocks`
  声明 AFTER），cutout 层每面一张贴图：车架本体复制原模型的面只换贴图（位置、顶点顺序不变，不与材质打架），
  上方碎石按原版 FaceBakery 顶点顺序构造并沿法线外移 0.002 防 z-fighting（上方为空气时不贴）。镂空贴图由自定义图集精灵源 `GrimeSpriteSource`
  在资源加载时从 vanilla 包的泥土/沙砾贴图生成（`assets/minecraft/atlases/blocks.json`：泥 2 套 × 7 档 × 16 张，
  碎石 2 套 × 16 张，共 256 张 16x16）。

## 依赖

Create 6.0.10、Copycats+ 3.0、dimblend_blocks、Sable 2.0（必需）；Create: FireFighting Additions 0.2.1-beta（可选）。
Sable 编译期引用 `dimblend/libs` 下已入库的 cui-modded jar。
