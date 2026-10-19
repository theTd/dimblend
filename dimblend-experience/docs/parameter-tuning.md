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
| Bogie redstone signal cap | 1 | 1-15, step 1; signals above the cap are clamped to it, 15 restores vanilla scaling | experience |
| Brake sound speed threshold | 2.0 m/s | 0.0-64.0 m/s, step 0.1; brake sound plays only above the threshold, release sound is not gated | experience |
| Deadman brake radius | 32 | 8-128 blocks, step 1; rotating only: a moving consist with nobody within this radius of its nearest bogie force-brakes that consist; any raw redstone level change on any bogie releases its entire connected consist | experience |
| Bogie roll torque cap | 5000 | 0-10000000, step 1; 0 = no roll spring (cars rest on the 15° roll stop) | simurail |
| Dirtiness probability multiplier | 0.5 | 0.00-1.00, step 0.01; 0 = no soiling draws | carwash |
| Allow coupler redstone activation | false | Checkbox | experience |
| Allow Survival right-click on couplers | false | Checkbox | experience |
| Limited-water checks | true | Checkbox | experience |
| Structure-void fitting on vehicles | true | Checkbox | experience |
| Lava melting vehicle blocks | true | Checkbox | experience |
| Lava melt speed multiplier | 4.0 | 0.5-64.0, step 0.1; relative to bare-hand breaking, applies on the fly without clearing accumulated melt progress | experience |
| Portable Engine quantity limit | true | Checkbox | experience |
| Steam Engine overload penalty | true | Checkbox | experience |
| Small/Modular/Large Diesel Engine overload penalty | true | Checkbox | experience |
| Electric Motor overload penalty | true | Checkbox | experience |
| Radio reception noise volume | 0.50 | 0.00-2.00, step 0.01; 0 = every radio clean, 0.50 = former on level | radio |
| Radio End voice frequency multiplier | 2.0 | 0.0-4.0, step 0.1; 1.0 = original cadence, 0 = no new voices | radio |
| Radio End voice volume | 0.75 | 0.00-2.00, step 0.01; 0.5 = former hard-coded loudness, 0 = no voices (music not ducked) | radio |
| Radio acoustic simulation intensity | 1.00 | 0.00-2.00, step 0.01; 0 = no simulated echo | radio |
| Experience clear ratio on respawn | 1.00 | 0.00-1.00, step 0.01; rotating dimension only, ignores keepInventory, no experience orb drops | experience |

## Vehicle headlights (surface and volumetric haze)

The Sable page includes seven **experience-owned, server-authoritative** options.
Edits persist in `serverconfig/dimblend_experience-server.toml` and broadcast to
all clients through the tuning snapshot (protocol 22, 64-bit availability mask).

| `/dbx` option | Config key | Default | Range / step |
|---|---|---|---|
| `sable_headlight` | `sableHeadlight` | on | boolean |
| `sable_headlight_range` | `sableHeadlightRange` | 24 blocks | 4–64 / 0.5 |
| `sable_headlight_angle` | `sableHeadlightAngle` | 50 degrees (full cone) | 10–120 / 1 |
| `sable_headlight_intensity` | `sableHeadlightIntensity` | 1.00 | 0–3 / 0.05 |
| `sable_headlight_fog` | `sableHeadlightFog` | on | boolean |
| `sable_headlight_fog_density` | `sableHeadlightFogDensity` | 0.35 | 0–2 / 0.01 |
| `sable_headlight_fog_samples` | `sableHeadlightFogSamples` | 8 | 4–24 / 1 |

Only lit `dndecor:*_frontlight` blocks on Sable sublevels participate. The lens
is at block center, shifted 0.25 blocks opposite facing for `no_base`. Interpolated
render poses transform both the lens and facing. The nearest eight eligible
lamps are used, with conservative frustum/range culling. Static-world lamps are
excluded. Veil and Sable are required; Iris disables this renderer. No Create
Dynamic Lights, Sodium Dynamic Lights, or Veil area lights are used.

The surface pass runs after block entities, before translucency. Veil's dynamic
albedo and view-space normal buffers are sampled through shader texture sources
`veil:dynamic_buffer/albedo` and `veil:dynamic_buffer/normal`. The main depth is
reconstructed using the render event's inverse projection/view matrices, in
camera-relative world axes. Empty albedo falls back to a clamped copy of scene
color; missing normals use depth derivatives. Light uses a warm cone with a soft
shoulder over the outer radius and a gently brighter centre (no crisp rim),
exponential and squared-distance attenuation (`exp(-d/range) / (1 + (d / 0.6range)^2)`),
a soft exposure knee, wrapped Lambert, range fade and vanilla fog. Multiple lamps
still add together. There is **no shadow occlusion** yet, so light can cross walls;
scene depth only stops the view ray.

Volumetric haze runs at `AFTER_TRANSLUCENT_BLOCKS`, so it composites in front of
water and stained glass instead of being painted over by them. Right after the
surface pass a tiny half-resolution pass captures the opaque scene distance; the
march runs to that opaque surface and samples behind the front-most (translucent)
depth count at 60%, so a beam seen through a window or under water is dimmed, not
dropped. (Fabulous graphics draws translucency into its own target; there the haze
behaves as before and is covered by translucent surfaces.) A half-width ×
half-height RGBA16F target stores warm in-scatter in RGB and the front-most scene
distance in alpha. Each ray is clipped to the opaque distance, the lamps' range
spheres and forward cones, then sampled equi-angularly about its closest approach
to the lens (samples crowd where in-scatter peaks; a static per-pixel offset, since
nothing accumulates frames). The haze model is art-directed and mirrored by
`HeadlightVolumeMath`: exponential falloff along the beam (`exp(-2d/range)`, soft
end at range) with a near-lens boost (`1 + 2 / (1 + (d/1.25)^2)`) so the shaft
visibly leaves the lamp and thins out along its length (from the driver's seat it
reads as a beam going forward, not a disc floating ahead), a cross-section
feathered over the whole cone radius and weighted towards the axis (bright core),
phase = 70% isotropic + 30% Henyey–Greenstein lobe (g = 0.85) so side views keep a
visible shaft while looking into a lamp gives a concentrated glow, in-scatter faded
out within 4 blocks of the camera only while the camera stands in the (slightly
widened) cone (standing in a beam must not veil the whole screen and sky; behind or
beside the lamp nothing is faded), sample-position vanilla fog fading, and a
Reinhard-style soft cap (exposure 0.65, cap 0.5) that keeps gradation in long paths
down the beam and keeps overlaps from blowing out. The cone clip uses plain
quadratic roots: the `-(b + sign(b)·sqrt(d))` form collapsed on the GPU for rays
through the apex and left a black spot at the lamp. The `visibility(position)` hook
currently returns 1. A four-tap joint bilateral upsample weights low-resolution
distances against full-resolution depth (falling back to the texel with the most
similar distance at thin silhouettes instead of leaving a black hole), then adds
RGB onto a color-only main-target view without changing alpha. The same
full-resolution composite draws a subtle lens glow: a lens-sized core plus a soft
halo (measured in blocks at the lens, with an angular floor so distant lamps keep
a few pixels), only towards the lamp's front, and hidden per pixel where the scene
lies more than ~0.7 blocks in front of the lens (an occluded lens shows no glow).
The glow needs the haze option on. Fog off, zero density, or no eligible beams
frees the fog targets and skips all fog draws. Resize, disconnect and dimension
unload release/recreate targets.

Radial streaks seen around the glow from the driver's seat in the 2026-10-19
captures were scene geometry, not sampling noise: the stepped walls of a tunnel
along the beam axis converge on the vanishing point (`/dbxheadlight debug albedo`
shows the steps; `debug fog` shows a smooth haze there).

Buffers are requested before world rendering while eligible lit lamps exist,
and released when none remain or the setting/intensity disables lighting. A
color-only framebuffer view composites onto main, with a separate scene snapshot
to avoid feedback; no depth/stencil texture is allocated by this pass. A gated
Veil compatibility mixin resolves wrapped depth formats before stencil detection
and supplies packed depth-stencil allocation arguments for Veil's first-person
target. This fixes its invalid depth-only texture clear when main uses stencil,
without changing the `veil:light` framebuffer definition. Veil may
compile shader variants when the buffer mask changes. First activation and
resource reload behavior still require runtime verification.

Client-only diagnostics: `/dbxheadlight debug off|albedo|normal|depth|light|fog`;
`/dbxheadlight list` prints active beams' world lens positions and axes (chat and
log), `/dbxheadlight reload` reloads resource packs (shader iteration via a pack).
`off` restores additive lighting; the other modes replace the opaque scene with
the selected data while an eligible lamp is active. Normal colors show view-space
normals; depth shows reconstructed distance divided by headlight range. `light`
shows only the added, fog-faded surface light; `fog` shows only the upsampled haze
and lens glow on black (black if fog is disabled). Later translucent geometry may
still draw over the diagnostic view.

Runtime checks: verify moving/rotating vehicles and all six facings, `no_base`,
placement/removal/redstone changes, chunk replacement, disconnect and dimension
changes, nearest-eight selection, fog, resize and F3+T. Use albedo/normal modes to
check Sable blocks and Create/Flywheel visuals; their actual coverage cannot be
proven by unit tests. Check Iris and missing optional dependencies as well.

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
per option, so each added or removed option bumps the protocol version (now `22`; see the
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

The bogie redstone signal cap clamps any stronger signal to the cap; brake
strength and kinetic network stress follow the adjusted signal. 1 keeps every
signal at level one (the former fixed behavior); 15 is a no-op.

The bogie roll torque cap is Simurail's own `bogeyAngularSpringMaxTorque` in
`simurail-server.toml`; Simurail stays the only source of truth and experience
reads and writes it through a bridge loaded only when Simurail is present.
Each bogie with yaw and pitch offsets enabled and both axles on track runs a
roll spring that pushes the car body back upright with a torque proportional to
its lean (plus Simurail's curve compensation), clipped to this cap; other
bogies either lock roll with their joint or, while half-derailed, swing freely
to the stop, and ignore the cap. The pivot joint still
stops the body at 15° of roll (shifted by up to 10° of curve tilt), so at 0
cars sag onto that stop instead of being held upright. Once gravity's tip
torque, roughly `mass × 11 (Sable's default gravity) × (height of the centre of
mass above the bogie pivots) × sin(lean)`, exceeds the cap times the car's
number of sprung bogies, the springs can no longer bring the car back; raise the
cap above that for cars that lean over. Simurail reads it on every physics
step, so changes apply at once without re-placing bogies (typing slowly can
briefly apply a partial number). The spring's stiffness and damping are
unchanged.

The roll spring is explicit: at Simurail's defaults (frequency 15, damping rate
1.2) it is only stable for physics steps under about 0.048 s. A step of 0.05 s
or more — one Sable sub-step per tick, or a physics tick interval at least as
large as the sub-step count — is past that limit, and the spring then shudders,
flipping direction every step and growing until the cap bounds it (with a much
higher cap, only the 15° roll stop does). When raising the cap, keep the step
under 0.05 s, e.g. 2 sub-steps at tick interval 1.

## Sable Page

The **Sable ▸** row at the bottom of the main page switches to a second page of
Sable physics settings; **◂ Back** returns to the main page. Every row requires
Sable; otherwise the rows stay disabled.

| Setting | Default | Valid values | Recommended |
| --- | --- | --- | --- |
| Sub-steps per tick | 2 | 1-10, step 1 | 1; with Simurail trains prefer 2 — at 1 their roll spring shudders (see the bogie roll torque cap above) |
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
(`perf_check_request`, `perf_check_report`), which also bumped the tuning
protocol version (now `22`, see above). The thresholds live in `PerfRules` and are unit-tested;
`PerfChecklistTranslationsTest` checks both languages cover every step, advice
and fix label.

## Verification

Run `gradlew.bat :dimblend-experience:build :dimblend-carwash:build` with JDK 21.
Tests cover numeric bounds and precision, translations, configurable grime and
force math, and clearing diesel overload state without changing its RPM state.

In game, check both languages and GUI scales, persistence after reconnect,
permission rejection, each rule's live effect, and the disabled carwash row when
carwash is absent. Test worlds should enable cheats for permission level 2.
