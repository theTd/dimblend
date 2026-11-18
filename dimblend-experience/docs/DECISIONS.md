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

---

# DimBlend Experience — 20-Item Batch Decisions (2026-11-15)

Recorded from `dimblend-batch-1115` (`generated: 2026-11-15T14:13:32.844Z`, 5 locked decisions: 4 defaults kept, 1 explicit user override).

1. **`fluid-hatch-direction` [OVERRIDE]**: **只能放入、不能取出**
   - `FluidHatchRules.ACTIVE_MODE = Mode.DEPOSIT_ONLY`; `FluidHatchDirectionMixin` permits `tryEmptyItem` deposit and blocks `tryFillItem` extract.
   - `dimblend-craft` `lang_overrides/{zh_cn,en_us,zh_tw}.json` `block.create_dragons_plus.fluid_hatch.tooltip.behaviour1` retains only the deposit clause.

2. **`controls-debug-menu` [DEFAULT]**: **仅创造模式可打开生成菜单**
   - `ChassisInteraction.SURVIVAL_STANDARD_CHASSIS_ENABLED = false`; `create:controls` right-click opens the no-confirmation preset spawn screen for all non-nest presets in Creative only.

3. **`lava-over-16-consume` [DEFAULT]**: **消耗（化作沿途烟雾蒸发）**
   - `WaterBucketLavaQuenchMath.CONSUME_WHEN_SOURCE_OVER_16 = true`; Survival consumes water from the bucket or 1 blue ice even when the upstream lava source is `> 16` steps away and evaporates into the sequential smoke + extinguish-sound trail.

4. **`photo-marker-hud` [DEFAULT]**: **仅世界投影“@”符号（同放下望远镜后的标记）**
   - `PhotoOriginHud.GUIDE_WITH_ARROW_AND_DISTANCE = false`; holding `camerapture:picture` in main/off-hand renders only the `2x` world-projected `@` glyph when `RegionLanes.sameRegion` matches, with no off-screen arrow or crosshair distance readout.

5. **`camera-creative-durability` [DEFAULT]**: **创造与生存一致（均按相机内耐久结算，0耐久提示无纸张）**
   - `CameraFilmRules.CREATIVE_BYPASSES_DURABILITY = false`; Creative and Survival both consume 1 camera film durability per shot out of 64 max, start newly crafted cameras at 0 shots, reload via paper right-click in inventory, and display the upstream `no_paper` warning without shooting at 0 remaining shots.

---

# DimBlend Performance Optimization — P0 + P1 + postProcessGeneration Batch Decisions (2026-11-15)

Recorded from `dimblend-perf-p0p1-decisions.json` (3 locked decisions: 2 defaults kept, 1 explicit
user override with own wording). Basis: spark profile `i708Ci2hF3`, a 30-minute TrainTripWorld
travel capture.

1. **`compute_mode` [DEFAULT] (`remote_castawayl`)**: **卸载构建与测试到远端 CASTAWAYL**
   - Gradle builds and tests for this batch run on CASTAWAYL, not on the loaded workstation; the
     worktree only receives source and doc edits.

2. **`sable_scope` [DEFAULT] (`modpack_config_only`)**: **备份并修改整合包 `sable-server.toml`**
   - Set `sub_level_substeps_per_tick = 1` and `solver_iterations = 12` in the modpack's
     `serverconfig/sable-server.toml` (backup first), without touching the `sable` repository
     defaults or its vendored jar. Stock `2 / 18` remains the fallback if a train jitters.
   - In the checklist the same tradeoff is surfaced by `PerfCheck.SABLE_PHYSICS` (informational;
     its button opens the Sable page), whose copy now cites the capture's ~13.5 % of server tick
     time (~3.0 ms/tick) and the ~half saving that `1 / 12` gives.

3. **`third_party_scope` [OVERRIDE / OWN WORDING]**: **严格收敛到 dimblend 仓库，接入调优自查面板**
   - No third-party config file is rewritten behind the user's back (e.g.
     `easy_mob_spawn_control-common.toml`) and no invasive mixin is injected into
     `create_connected`. Instead the three profile-backed findings become checklist steps with
     one-click fixes inside `dimblend-experience`'s Performance Checklist
     (`PerfChecklistScreen` / `PerfCheck`):
     - `PerfCheck.MOB_SPAWN_CAPS` (`mob_spawn_caps`): reads the live
       `MobCategory.MONSTER` / `AMBIENT` / `WATER_AMBIENT` per-chunk caps; when
       `easy_mob_spawn_control` is loaded the fix lowers them through that mod's own
       `CommonConfig.saveSpawnCaps` + `HandleServerAboutToStart.onServerAboutToStart(null)` path
       (`EasyMobSpawnControlBridge`), which sets `MobCategory.max` in memory so
       `SpawnState.canSpawnForCategory` fails in O(1). Its `spawnRates` path is deliberately not
       used: cancelling `FinalizeSpawnEvent` keeps the caps from filling and makes
       `NaturalSpawner.spawnForChunk` (26.2 % of the captured tick, 5.74 ms/tick) worse.
     - `PerfCheck.RANDOM_TICK_SPEED` (`random_tick_speed`): reads the game rule, offers the
       one-click `randomTickSpeed = 3`.
     - `PerfCheck.CHUTE_CONTRAPTION_SCAN` (`chute_contraption_scan`): counts ticking
       `create:chute` / `create:smart_chute` block entities by reflecting
       `Level.blockEntityTickers` (no chunk loads, no Create classes) and advises on the
       create_connected per-tick inventory scan (15.56 s, 0.47 ms/tick in the capture).
     - `PerfCheck.IMMEDIATELY_FAST` (`immediately_fast`, client): recommends the client-side
       batching mod as `INFO`, so it never inflates the warning count.

---

# DimBlend Experience — Batch 1116 Gameplay Decisions (2026-11-16)

Recorded from `dimblend-batch-1116` (`generated: 2026-11-16T05:56:39.750Z`, 3 locked decisions: all 3 explicit user overrides over provisional defaults).

1. **`brew-batch-economy` [OVERRIDE] (`keep_16_yield_quarter`)**: **保留 16 原料槽上限，产出折算为 1/4（16 原料 → 4 份酒）**
   - `TavernBrewingRules.ACTIVE_MODE = EconomyMode.KEEP_16_YIELD_QUARTER` (enforced by `Batch1116Unit2RulesTest#tavernLockedModeKeepsSixteenInputAndCapsOutputAtFour`).
   - Barrel ingredient slot limit stays `16` and JEI/EMI/REI display `16` inputs + `4` fluid buckets → `4` outputs; `BarrelRecipe#assemble` divides stackable ingredient counts by `4` (capped at `4`, with `maxStackSize < 4` ingredients exempt from division; closed barrels matching a real recipe with `< 4` stackable items hold idle via `BarrelBlockEntityMixin` instead of spoiling 4 buckets into Vinegar), and unmatched Vinegar fallback yields `4`.

2. **`catalyst-redstone-block-mode` [OVERRIDE] (`replace_with_crystal_sand`)**: **挖除后仍替换为晶化沙或红水晶苔藓**
   - `CrystalbornCatalystRules.ACTIVE_REPLACE_MODE = ReplaceMode.REPLACE_WITH_CRYSTAL_SAND` (enforced by `Batch1116Unit2RulesTest#crystalbornLockedToReplaceWithCrystalSandAndHasTooltipKey`).
   - When `eternal_starlight:crystalborn_catalyst` is powered with an adjacent `minecraft:redstone_block`, it mines peripheral blocks matching `pos.below()` (never mining `pos.below()` or `pos` itself, and rejecting `crystallized_sand`/`red_crystal_moss_block` as self-replacing templates) and replaces mined positions with `red_crystal_moss_block` / `crystallized_sand`.

3. **`thermolith-mode-control` [OVERRIDE] (`vanilla_ice_break_only_reset`)**: **仅原版冰/熔岩块单向设模式；仅拆掉重放恢复默认**
   - `ThermolithModeRules.ACTIVE_POLICY = InteractionPolicy.VANILLA_ICE_BREAK_ONLY_RESET` (enforced by `ColdSweatBatch1116Test#vanillaIceBreakOnlyResetPolicyIsLockedAndOnlyPlainIceCounts`).
   - Only `minecraft:ice` (`> 30 °C` → `15`, updated from `40 °C` in `batch-1118`) and `minecraft:magma_block` (`< 10 °C` → `15`) set `cold_sweat:thermolith` operating mode without consuming the held block; repeated right-clicks are idempotent and breaking + re-placing restores default `NORMAL` mode.

---

# DimBlend Experience & Dragon Chase — Batch 1118 Gameplay Decisions (2026-11-18)

Recorded from `dimblend-batch-1118` (`11` locked decisions: `9` defaults kept + `2` user overrides):

1. **`thirst-unmatched-edibles` [OVERRIDE] (`strict_rule_only`)**: Only edibles matching the water or fruit rules (`254` items in `thirst_edible_overrides.json`) are added/overridden; unmatched edibles keep YAT's existing config value if present and remain unconfigured otherwise.
2. **`thirst-fruit-drinks-and-dough` (`fruit_drink_3_direct_water`)**: Direct water / stockpot default water / spout tea counts as water (`6` thirst / `8` quenched for drinks, `4` / `5` for foods); fruit-only drinks restore `3` / `4`; fruits & fruit-only foods restore `1` / `1`; dough water does not carry into dry baked bread/cookies.
3. **`canola-compacting-fluid` (`createaddition_seed_oil`)**: `1x kaleidoscope_grilling:canola_seeds` → unheated `create:compacting` → `200 mB createaddition:seed_oil`.
4. **`dirty-water-purity-and-basin` (`purity0_basin_default_block`)**: Reject only `Purity == 0` dirty water across crafting grids, stockpot, teapot, tavern barrel, Copper Tap, and Create Basin (allowing `Purity == 0` into Basin only when its filter or contents match a potion or non-food industrial recipe); handheld rejection shows hotbar `"太脏了 至少先过滤一下"`.
5. **`thermolith-signal-modes` (`all_modes`)**: Ice mode threshold lowered to strictly `> 30 °C`; external neighbor redstone signal suppresses output (`0`), emits red dust particles, and appends tooltip `"通入信号停止工作"` across all 3 modes (`NORMAL`, `ICE`, `MAGMA`), ignoring self-feedback from front-face `RedStoneWireBlock`.
6. **`crystal-car-design` (`entity_sync_with_obsidian_campfire`)**: Keep native `EndCrystal` entity with `hasImpulse = true` server sync + per-frame `ClientSubLevel#renderPose(partialTick)` client lock (`CrystalMountsPayload`), and place zero-damage `dimblend_dragonchase:obsidian_soul_campfire` pedestals beneath crystals.
7. **`brake-and-ender-sounds` (`existing_low_pitch_all_dims`)**: Broadcast Brake Car coupling (`1.0` pitch) and newly latched Ender Brake (`0.5` pitch, zero speed gate) `train_brake` announcements to all online players across all dimensions.
8. **`phantom-track-visibility` (`chase_active_only`)**: Force `railways` phantom tracks visible strictly while an Ender Dragon chase is active in `dimblend:rotating` (`PhantomSpriteManagerMixin`).
9. **`end-corruption-boundary` (`no_corrupt_blocks_in_128`)**: Filter `biomesoplenty:end_corruption` → `minecraft:end_highlands` for `|quartZ| <= 35` in `RotatingBiomeSource` so `|z| <= 128` contains zero corrupt blocks.
10. **`dragon-music-trigger-and-loop` (`chase_start_single_loop`)**: Start chase BGM on `ChaseDirector.startChase` (real chase only, not Region-32 prologue), server-roll 1 random track (`0..3`) per phase for single-track looping, and fade out over `16 s` (`320` ticks) on dragon death or removal.
11. **`dragon-music-channel-and-transition` [OVERRIDE] (`records_channel_crossfade_4s`)**: Play chase BGM on `SoundSource.RECORDS` while blocking/stopping `100%` of `SoundSource.MUSIC` until the `16 s` fade-out ends; on first drop strictly `< 50%` HP (`> 0`), execute a `4 s` (`80`-tick) overlapping crossfade from Phase A to Phase B.

