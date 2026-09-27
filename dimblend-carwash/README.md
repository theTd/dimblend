# DimBlend Carwash

车辆脏污与清洗：车架方块随车厢行驶积灰，可洗净。纯视觉，不影响碰撞、质量、方块状态或任何玩法数值。

## 玩法

车架方块 = `dimblend_blocks` 的
- 创造模式伪装板，倒置（贴在格子顶部，`FACING=DOWN`）
- 创造模式伪装半砖，倒置（竖轴上半砖）或双层

每块车架有脏值 0–255：

| 触发 | 效果 |
|---|---|
| 车厢（Sable 子关卡）速度 &gt; 4 m/s | 每秒，车厢内每块车架各以 `速度/12`（封顶 100%）的概率脏值 +1 |
| 手持水桶 / 湿海绵右键车架 | 不倒水、不放方块；本块脏值非 0 则 -16，为 0 则任选一个脏值非 0 的相邻车架 -16 |
| Create: FireFighting Additions 水喷淋命中车架 | 视同一次上面的清洗，按被命中方块冷却 0.25 秒 |
| 手持泥土右键车架 | 不放方块；本块脏值 +16 |
| 下雨 | 每分钟所有车架脏值 -16 |

外观（按当前脏值，洗掉后对应层随之消失）：
- 每到 32 的倍数贴一层泥：原版泥土贴图随机去掉 80% 像素，贴在车架顶面、底面、四个侧面（侧面去掉下半）。
- 达到的倍数大于 128（160/192/224）时再在上方一格贴一层碎石：原版沙砾贴图随机去掉 80% 像素；
  上方是实心不透明方块则贴其四个侧面与顶面，否则贴该格四个侧面并去掉上半。
- 每层每面的镂空图案独立随机；重新达到某层时重掷。

## 技术

- 脏值：Create `BlockEntityBehaviour`（`ChassisGrimeBehaviour`）挂在伪装方块 BE 上，随 BE 存盘、
  经 `sendData` 同步客户端。伪装方块 BE 不 tick，行为在首次读 NBT 时经 `BlockEntityBehaviourEvent`
  挂上，从未读过 NBT 的新 BE 在改值时补挂。不用方块状态：Sable 子关卡里每次改状态都会触发物理碰撞体/质量更新。
- 行驶/下雨：`ChassisTravelGrime` 在服务端关卡 tick 遍历 Sable 子关卡，速度取
  `ServerSubLevel.latestLinearVelocity`（m/s），车架取自其 plot 区块的 BE。
- 喷淋：`NozzleSprayWashing` 注册到 FireFighting Additions 的 `NozzleSprayInteractionRegistry`
  （可选依赖，缺席不注册）。车厢里的方块经 Sable 的射线投影命中。
- 渲染：`ChassisGrimeModel` 包在 dimblend-blocks 换上的伪装模型外（mods.toml 对 `dimblend_blocks`
  声明 AFTER），cutout 层叠加贴层：车架本体复制原模型的面只换贴图（位置、顶点顺序不变，不与材质打架），
  上方碎石按原版 FaceBakery 顶点顺序构造。镂空贴图由自定义图集精灵源 `GrimeSpriteSource`
  在资源加载时从当前资源包的泥土/沙砾贴图生成（`assets/minecraft/atlases/blocks.json`，四套各 16 张）。

## 依赖

Create 6.0.10、Copycats+ 3.0、dimblend_blocks、Sable 2.0（必需）；Create: FireFighting Additions 0.2.1-beta（可选）。
Sable 编译期引用 `dimblend/libs` 下已入库的 cui-modded jar。
