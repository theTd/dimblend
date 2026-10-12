# Parameter Tuning

Open the live panel with `/dbx tune` or the unbound **Parameter Tuning** key in
Controls. Changing server settings requires permission level 2. The screen does
not pause the game. Labels, descriptions, validation and status messages support
English (`en_us`) and Simplified Chinese (`zh_cn`) through Minecraft's language
selection.

Validated numeric input is submitted after a short typing delay. Checkboxes,
steppers and per-setting resets apply immediately. Each request edits one setting;
the server validates bounds and precision, saves it, then broadcasts the current
values. Changes also reach other open panels and client-side coupler prediction.
Missing optional mods leave their controls disabled.

| Setting | Default | Valid values | Owner |
| --- | --- | --- | --- |
| Train shaking draw interval | 2.0 seconds | 0 = disabled; 0.1-60.0, step 0.1 | experience |
| Train additional lateral force | 1200 pN | 0-10000, step 1 | experience |
| Dirtiness probability multiplier | 0.5 | 0.00-1.00, step 0.01; 0 = no soiling draws | carwash |
| Allow coupler redstone activation | false | Checkbox | experience |
| Allow Survival right-click on couplers | false | Checkbox | experience |
| Limited-water checks | true | Checkbox | experience |
| Structure-void fitting on vehicles | true | Checkbox | experience |
| Lava melting vehicle blocks | true | Checkbox | experience |
| Portable Engine quantity limit | true | Checkbox | experience |
| Steam Engine overload penalty | true | Checkbox | experience |
| Small/Modular/Large Diesel Engine overload penalty | true | Checkbox | experience |
| Electric Motor overload penalty | true | Checkbox | experience |
| Radio reception noise volume | 0.50 | 0.00-2.00, step 0.01; 0 = every radio clean, 0.50 = former on level | radio |
| Radio acoustic simulation intensity | 1.00 | 0.00-2.00, step 0.01; 0 = no simulated echo | radio |
| Experience clear ratio on respawn | 1.00 | 0.00-1.00, step 0.01; rotating dimension only, ignores keepInventory, no experience orb drops | experience |

## Ownership and Persistence

The panel and its network protocol live in experience. Carwash registers its own
SERVER config and exposes `dimblend.carwash.api.CarwashTuning`; experience has a
compile-only project dependency and an optional runtime dependency. The bridge
is loaded only when carwash is present. Carwash can run without experience, and
experience can run without carwash.

Settings persist in the world's `serverconfig/dimblend_experience-server.toml`
and `serverconfig/dimblend_carwash-server.toml`. Carwash remains the only source
of truth for its multiplier. Its next one-second settlement uses the new value:
`min(1, speed / 12 * multiplier)` above 4 m/s. Rain and other washing continue
when the multiplier is zero.

Radio follows the same pattern through `dimblend.radio.api.RadioTuning` and its
own `serverconfig/dimblend_radio-server.toml`. Its settings are heard on each
client, so remote clients also apply the broadcast radio values in memory
(NeoForge already sends the config at login). The snapshot carries one value
per option, so each added or removed option bumps the protocol version (now `8`; see the
performance checklist below).

The existing `couplerRedstone` config uses the inverse meaning: true blocks
redstone effects. The panel displays the positive **allow** meaning. Survival
right-click permission affects couplers only; breaking and explosion protection
remain independent.

Diesel and motor penalty switches are separate from their existing behavior
switches, preserving RPM, redstone and energy rules. Disabling penalties clears
loaded engines' active warning/fuse/latch state on their next tick. Disabling
the train interval clears its countdown and active push; changing a nonzero
interval restarts the countdown. Changing force affects subsequent physics steps.
Disabling a rule cannot undo damage that has already occurred. Re-enabling the
Portable Engine limit resumes the existing enforcement on tracked networks.

## Sable Page

The **Sable ▸** row at the bottom of the main page switches to a second page of
Sable physics settings; **◂ Back** returns to the main page. Every row requires
Sable; otherwise the rows stay disabled.

| Setting | Default | Valid values | Recommended |
| --- | --- | --- | --- |
| Sub-steps per tick | 2 | 1-10, step 1 | 1 |
| Solver iterations | 18 | 1-64, step 1 | 6 |
| PGS iterations | 2 | 1-8, step 1 | 2 |
| Stabilization iterations | 2 | 1-8, step 1 | 2 |
| Physics tick interval | 1 | 1-10, step 1 | 1; 2 is an experimental half-rate mode |
| Sleep skip | true | Checkbox | on |
| Contact force events | true | Checkbox | off — vehicles lose collision sounds and particles |
| Biome projection fast path | true | Checkbox | on |
| Empty-container fast path | true | Checkbox | on |

All Sable rows persist in Sable's own
`serverconfig/sable-server.toml`; each save is re-applied to every loaded
level's physics system immediately through
`dev.ryanhcode.sable.api.physics.SablePhysicsTuning.applyToAllLevels`.
Sable rows are in-memory no-ops on remote clients: the
physics settings are enforced by the server only.

## Performance Checklist

The **Performance checklist ▸** button on the main page opens a step-by-step
wizard (one page per step, then a summary whose rows jump back to their step).
Each step shows its status, the current value, what to do and why. The order is
largest tick stall first, from the 2026-10 TrainTripWorld spark captures.

| Step | Checked on | Flags when | One-click fix |
| --- | --- | --- | --- |
| spark background profiler | server | `backgroundProfiler` is not `false` in `config/spark/config.json` (spark's Java sampler freezes all threads ~100×/s) | writes `false`; takes effect on restart |
| Garbage collector | server, remote client | not generational ZGC | — (launcher: `-XX:+UseZGC -XX:+ZGenerational`) |
| Heap headroom | server, remote client | heap still used after the last collection > 60 % (> 75 % = problem) of `-Xmx`; suggests the `-Xmx` that restores 60 % | — (launcher) |
| Machine memory | server, remote client | free physical memory < 15 % (< 5 % = problem) | — |
| Background autosave writes | server | `dimblend_asyncsave` missing, or loaded without its mixins | — |
| FiniteWater mod | server | `finite_water` installed | — (remove the jar) |
| Overworld can unload | server | game rule `spawnChunkRadius` > 0 (the spawn-chunk ticket keeps a 7×7 full area plus a partly loaded ring in memory all session), or chunks force-loaded with `/forceload` | sets `spawnChunkRadius` to 0 (the rule callback drops the ticket at once); force-loaded chunks: guidance only |
| Simulation distance | singleplayer client; dedicated server | > 8 | singleplayer: sets the option to 8 (the integrated server follows it live); dedicated: guidance for `server.properties` |
| Render distance | client | > 12 | sets the option to 12 |
| Sable physics settings | server | informational: shows sub-steps / iterations / interval versus Sable's defaults | opens the Sable page |
| Sable smoothing buffer | client | `sub_level_snapshot_interpolation_delay_ticks` < 3 | sets 3 (applies immediately) |

The JVM steps run in the process they describe: in singleplayer only the
server's results are shown (same JVM); a client connected to a remote server
also lists its own collector, heap and memory. Server steps and the spark fix use
the same permission gate as the panel (level 2) and travel on two extra payloads
(`perf_check_request`, `perf_check_report`), which is why the tuning protocol
version is now `8`. The thresholds live in `PerfRules` and are unit-tested;
`PerfChecklistTranslationsTest` checks both languages cover every step, advice
and fix label.

## Verification

Run `gradlew.bat :dimblend-experience:build :dimblend-carwash:build` with JDK 21.
Tests cover numeric bounds and precision, translations, configurable grime and
force math, and clearing diesel overload state without changing its RPM state.

In game, check both languages and GUI scales, persistence after reconnect,
permission rejection, each rule's live effect, and the disabled carwash row when
carwash is absent. Test worlds should enable cheats for permission level 2.
