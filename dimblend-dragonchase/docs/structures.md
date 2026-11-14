# Dragon Chase 结构图（策划原文逐字转录，2026-11-13）

坐标约定（实现口径）：每层的行沿 **X 轴**（列车前进方向，行与行之间相差 1 格 X），列沿 **Z 轴**（7 列，中间一列为 z=0 走廊中线）。
第 1 层 = 甲板层（与转向架同层，参照奖励车厢 `RewardWagonLayout.STRUCTURE_DY` 的甲板高度），往上逐层 +1。
三种结构前后对称（两端都是车钩），所以行的先后顺序不影响结果。

## 龙巢车厢（11 行 × 7 列 × 5 层）

1层：
```
空 空 空 钩 空 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 黑 黑 黑 空 空
空 黑 黑 黑 黑 黑 空
空 黑 黑 架 黑 黑 空
空 黑 黑 黑 黑 黑 空
空 空 黑 黑 黑 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 空 钩 空 空 空
```
2层：
```
空 空 空 空 空 空 空
空 空 空 空 空 空 空
空 空 黑 黑 黑 空 空
空 黑 矿 矿 矿 黑 空
黑 矿 矿 矿 矿 矿 黑
黑 矿 矿 黑 矿 矿 黑
黑 矿 矿 矿 矿 矿 黑
空 黑 矿 矿 矿 黑 空
空 空 黑 黑 黑 空 空
空 空 空 空 空 空 空
空 空 空 空 空 空 空
```
3层、4层：只有正中一格「黑」（第 6 行第 4 列）。
5层：只有正中一格「箱」（第 6 行第 4 列）。

- 钩：自动车钩（`simurail:automatic_coupler`，朝外）
- 粱：创造模式伪装梁（`dimblend_blocks:creative_copycat_beam`），平行于 X 轴，材质紫珀块（`minecraft:purpur_block`）
- 黑：创造模式伪装台阶（`dimblend_blocks:creative_clean_copycat_slab` 或 `creative_copycat_slab`，W4 取证后定），上下双阶，材质紫珀块
- 架：物理转向架（`simurail:physics_bogey`）：启用 / 角向禁止 / 线向禁止 / 竖向禁止 / 刹车 / 应力 0.0 / 可见
- 矿：生成时留空（龙死后放金属块，共 20 格）
- 箱：生成时留空（龙死后放潜影盒）

## 刹车车厢（13 行 × 7 列 × 1 层，另在转向架上方 2 格）

```
空 空 空 钩 空 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 红 红 红 空 空
空 黑 红 红 红 黑 空
空 黑 红 架 红 黑 空
空 黑 红 红 红 黑 空
空 空 红 红 红 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 空 钩 空 空 空
```
- 钩、粱、架：同龙巢（粱材质紫珀块）
- 黑：伪装台阶上下双阶，材质黑曜石（`minecraft:obsidian`）
- 红：伪装台阶上下双阶，上半材质 `minecraft:redstone_ore`，下半材质 `minecraft:deepslate_redstone_ore`
- 架 y+1：`minecraft:redstone_block`；架 y+2：`minecraft:redstone_lamp`

## 水晶车厢（13 行 × 7 列 × 1 层）

```
空 空 空 钩 空 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 黑 末 黑 末 黑 空
空 末 黑 末 黑 末 空
空 黑 末 架 末 黑 空
空 末 黑 末 黑 末 空
空 黑 末 黑 末 黑 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 空 粱 空 空 空
空 空 空 钩 空 空 空
```
- 钩、粱、架：同上
- 黑：伪装台阶上下双阶，材质黑曜石
- 末：伪装台阶上下双阶，材质末地石砖（`minecraft:end_stone_bricks`）；随机 1–4 个「末」格的 y+1 处各生成 1 个末影水晶

## 龙巢奖励（requirements.md §3.6）

「矿」20 格（随机分布）：铜块×2、铁块×8、锌块（`create:zinc_block`）×5、金块×2、钻石块×2、下界合金块×1。

「箱」：潜影盒，27 格全部填满，格位随机：

| 物品 | 每格数量 | 格数 |
|---|---|---|
| `minecraft:experience_bottle` | 16 | 8 |
| `minecraft:dragon_breath` | 16 | 2 |
| `kaleidoscope_end:raw_ender_dragon_meat` | 4 | 2 |
| `kaleidoscope_end:dragon_tooth` | 2 | 1 |
| `kaleidoscope_end:dragon_dust` | 4 | 2 |
| `kaleidoscope_dim_wine:dragon_blood_bottle` | 16 | 2 |
| `kaleidoscope_end:dragon_breath_bucket` | 1 | 2 |
| `kaleidoscope_grilling:dragon_egg_powder` | 4 | 4 |
| `kaleidoscope_end:dragon_egg_liquid` | 8 | 1 |
| `minecraft:totem_of_undying` | 1 | 2 |
| `voidscape:voidic_crystal` | 16 | 1 |

物品 id 已在整合包 jar 的 `zh_cn.json` 中核对（2026-11-13）。缺 mod 时跳过该格并 WARN 一次。
