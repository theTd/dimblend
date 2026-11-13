# DimBlend Experience — Batch Decisions (2026-11-12)

Recorded from `dimblend-batch-confirm-20261112-decisions.json` (9 locked decisions, 3 explicit user overrides over defaults).

1. **`bogey-interaction` [OVERRIDE]**: **连创造模式的潜行+扳手也禁用（扳手永远只开菜单）**
   - On `simurail:physics_bogey` (normal & inverted), ONLY an empty hand or wrench (`create:wrench` / `#c:tools/wrench`) opens `PhysicsBogeyMenu` (both standing and sneaking, in both Survival and Creative).
   - Wrench never rotates (`onWrenched`) and never dismantles (`onSneakWrenched`) a physics bogey, even when sneaking in Creative mode.
   - All other held items (bricks, slabs, copycats, axial shafts, steering connectors, wire spools — standing or sneaking) bypass `PhysicsBogeyMenu` and execute normal item/block placement (`stack.useOn`), with server inventory resync on cancelled interactions so client stack counts never desync.

2. **`bogey-support-mode` [OVERRIDE]**: **方案 B：旋转维度硬锁车底 Y=66 与俯仰翻滚（放开 X/Z/偏航）**
   - In `dimblend:rotating`, lock carriage deck elevation (`Y = 66.0` / `entry.nominalHeight()`) and pitch/roll (`{LINEAR_Y, ANGULAR_X, ANGULAR_Z}`), while keeping `LINEAR_X`, `LINEAR_Z`, and `ANGULAR_Y` (yaw) FREE across straight rails, switches, Bezier curves, and `ZO` sidings.
   - Rigid-body `MassData` is untouched so mass-dependent traction/acceleration/braking dynamics remain unchanged.
   - Zero dependency on `RailJointLocks` (compatible with runtime `simurail-1.21.1-0.0.0-a+ecd2dd3.jar`).

3. **`loot-journal-icon` [DEFAULT]**: **局部兼容：仅在 Loot Journal 绘制时冲刷批处理并恢复深度**
   - Scoped client compatibility around `dev.obscuria.lootjournal.client.renderer.PickupComponent#render` flushing ImmediatelyFast's HUD batch and rendering Loot Journal's pickup layer on vanilla's `RenderBuffers#bufferSource`, preserving global `hud_batching: true` for all other HUD layers.

4. **`millstone-particle-target` [OVERRIDE]**: **仅部署列车子层级粒子修正（直播指的是机械动力石磨/粉碎轮）**
   - Target is Create's `create:millstone` and `create:crushing_wheel` on moving/turning Sable train sublevels (do not alter `kaleidoscope_cookery:millstone`).
   - Aligns both newly spawned (`Tick 0`) and already ticked (`Tick 1+`) sublevel-tracked particles to `ClientSubLevel#renderPose(partialTick)`.

5. **`animated-blocks-scope` [DEFAULT]**: **全量补齐 4 类缺口（流动流体 + 伪装块 + 单格子层级 + 重载刷新）**
   - Marks animated atlas sprites active under Sodium's `animate_only_visible_textures` for flowing water/lava & modded fluids, Copycats+ `ModelData` material quads, `VanillaSingleSubLevelRenderData` single-block sublevels, and post-`F3+T` resource reloads.

6. **`door-geometry-span` [DEFAULT]**: **方案 A：逻辑占 1 格，平移后居中跨 2 格 [-0.5, +1.5]**
   - Applies to `create:andesite_door`, `create:brass_door`, `create:copper_door`, `create:train_door`.
   - Paired door occupies 1 logical cell `[0, 1]` (`DIMBLEND_PAIRED = true`, no adjacent block placed) and renders two full 1-block leaves shifted `±0.5` (`[-0.5, +1.5]` centered span), with matching `[-0.5, +1.5]` closed collision/outline box and client overhang raycast picking.

7. **`door-open-collision-wrench` [DEFAULT]**: **开门清空物理阻挡（留点选框）；全面禁用普通扳手旋转、保留潜行拆**
   - Open paired doors return `Shapes.empty()` from `getCollisionShape` while keeping shifted outer-leaf `getShape`/`getInteractionShape` for clicking/breaking.
   - Non-sneak wrench adjustment (`onWrenched`) is disabled on all 4 target Create doors (both single and paired); sneak-wrench dismantle remains active.

8. **`door-train-casing-recovery` [DEFAULT]**: **对开列车门任何位置均掉 2 个列车门；未配对单门在 |Z|>32 仍掉机壳**
   - Paired `create:train_door` drops 2 `create:train_door` everywhere (including `|Z| > 32` in `dimblend:rotating`, via tool break or sneak-wrench); unpaired `create:train_door` at `|Z| > 32` continues recovering as 1 `create:railway_casing`.

9. **`door-tooltip-location` [DEFAULT]**: **仅在四种门的物品栏悬浮提示（Tooltip）添加该描述行**
   - Adds `"手持再次右键可变为单格对开"` (`zh_cn`) / `"Right-click again with the same door in hand to form a single-block double door"` (`en_us`) to hover tooltips of the 4 target Create door items.

---

# DimBlend Experience — Seven-Fixes Batch Decisions (2026-11-13)

Recorded from `dimblend-seven-fixes` (`generated: 2026-11-12T16:27:26.321Z`, 5 locked decisions: 4 defaults kept, 1 explicit user override).

1. **`advancement-action-dims` [DEFAULT] (`include_action_6`)**: **连同带维度限制的动作类进度一并改写为群系判定**
   - Converts all 15 pure dimension-entry advancements plus the 5 runtime-active dimension-gated action advancements (`aether:aether_sleep`, `twilightforest:twilight_hunter`, `voidscape:psychosis`, `minecraft:nether/ride_strider_in_overworld_lava`, `minecraft:nether/uneasy_alliance`) to `#dimblend_experience:orig_dim/<dim>` biome-tag checks while preserving original triggers and entity/vehicle/distance conditions (`ColdSweat`'s unloaded legacy `advancements/lava_sea.json` is left untouched to avoid orphan-parent errors).

2. **`advancement-bumblezone` [DEFAULT] (`hide_if_mod_missing`)**: **为这 5 个蜜蜂支线补上模组存在条件自动隐藏**
   - Adds `neoforge:mod_loaded` conditions for both `kaleidoscope_dim_wine` and `the_bumblezone` on `kaleidoscope_dim_wine:the_bumblezone_*` (5 nodes) so they stay hidden when `the_bumblezone` is absent and remain biome-mapped if later installed.

3. **`cdg-oil-peak-and-scanner` [DEFAULT] (`finite_10pct_with_scanner`)**: **峰值区块折算为 10% 有限油田，并同步缩放新区块探测器阈值**
   - Only chunks newly promoted to `FULL` (`ChunkEvent.Load#isNewChunk() == true`, tracked in `CdgNewOilChunksSavedData`) suppress `OIL_CHUNK_INFINITE_THRESHOLD` promotion to `Integer.MAX_VALUE`, scale positive finite oil reserves by `ceil(amount * 0.10)`, and scale `OilScannerItem` low/high thresholds by `0.10` on those new chunks; already-generated chunks keep 100% original CDG behavior.

4. **`warpgate-push-and-hold` [DEFAULT] (`push_any_hold_when_zero`)**: **任意 1 人在任意 Sable 即全推；0 人在 Sable 时门内锁停防溜车**
   - In `dimblend` (`WarpGateMotion` + `WarpGateSableCrossing`), whenever `playersOnAnySubLevel >= 1` (any game mode, any dimension, any Sable sublevel), every Sable sublevel entering the warp gate from `-X` (including unmanned coupled cars) is pushed `+X`; when `playersOnAnySubLevel == 0`, sublevels inside the gate hold still (`HOLD_STILL`) so unattended cars cannot drift away.

5. **`train-lateral-shake-mode` [OVERRIDE] (`sway_plus_micro_roll`)**: **水平微晃 + 限幅 ±2.5° 轻微侧倾**
   - Keeps `ChassisRailLock` hard-locking deck elevation (`Y = 66.0` / `entry.nominalHeight()`) and nose pitch (`ANGULAR_Z = 0°`), opens `±0.12 m` spring-centered horizontal sway (`LINEAR_Z`) on `!allowLateralOffset` bogeys, AND dynamically opens capped `±2.5°` (`ANGULAR_X`) spring-damped micro-roll during lateral shake pulses (`[0°, 0°]` dead-flat lock when parked/idle so off-center cargo never tilts idle cars), with inertia-normalized roll torque impulses alongside mass-normalized horizontal impulses.
