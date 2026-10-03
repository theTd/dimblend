# Opportunistic acoustic pre-baking research

Status: phase 1 (pathing) implemented and covered by unit and native tests; not yet verified in
game. Phase 2 (reflection tiles) is design only. "Phase 1 as built" below lists where the code
departs from the design.
Date: 2026-10-02. Target: Minecraft Java 1.21.1 / NeoForge, dimblend-radio, Steam Audio 4.8.1.

Goal: once the scene around a radio has stayed (nearly) static for a while and the client is
idle, bake acoustics at points throughout the scene in the background. Real-time simulation keeps
running and serves everything the bake does not cover yet; baked results take over where they are
valid, and edits push affected areas back to real time until they are baked again.

Decisions taken after the first spike:

1. **Idle includes "CPU not busy"**, not only paused, unfocused or away from the keyboard.
2. **Bakes are saved to disk** and reused in later sessions while the blocks they were baked from
   are unchanged.
3. **Diffraction is required**: Steam Audio's pathing (sound bending around corners and through
   openings, found on a baked probe graph) is in scope, not a later option.

## Recommendation

Two bake products, both on the **CPU with Embree** in one background bake context:

- **Pathing per radio** (diffraction, phase 1). One probe batch around each radio that is not on a
  moving structure; the pathing bake is cheap (about 1 s for a radio's neighbourhood on four
  threads) and its data does not depend on the source position. At run time the direct worker
  looks up the path from the radio to the listener (1–3 ms) and a path effect mixes it into the
  same Ambisonic bus as the reverb, weighted by how much of the direct path is hidden. This is the
  part that changes what is heard: today an opening further than the volumetric-occlusion radius
  from both ends does not help at all.
- **Static-source reflections in tiles** (`IPL_BAKEDDATAVARIATION_STATICSOURCE`, phase 2). Probes
  on walkable floor around the radio, baked in small tiles so the work can stop within ~50 ms when
  the client becomes busy and so a block edit only invalidates nearby tiles. A radio whose
  listener sits inside valid tiles takes its reflection IR from the bake (about 10 ms per update)
  instead of a live GPU run (hundreds of ms on a real world mesh).

Direct sound (occlusion, transmission, volumetric diffraction approximation) stays real time: it
is cheap, depends on the exact listener position and must react within one camera frame.

## What the SDK provides

All of the following are exported by the bundled `phonon.dll` (checked against its export table;
`phonon.h` in `E:/misc/dimblend/build/reverb-validation/`):

- Probes: `iplProbeArrayGenerateProbes` (`CENTROID`, `UNIFORMFLOOR`), `iplProbeBatchAddProbe`,
  `iplProbeBatchCommit`, `iplProbeBatchGetDataSize`, `iplProbeBatchRemoveData`.
- Bakers: `iplReflectionsBakerBake` / `iplReflectionsBakerCancelBake` (convolution and/or
  parametric data; variations `REVERB`, `STATICSOURCE`, `STATICLISTENER`), `iplPathBakerBake` /
  `iplPathBakerCancelBake`. "Only one bake can be in progress at any point in time."
- Runtime reflections: `iplSimulatorAddProbeBatch` / `RemoveProbeBatch`, then per source
  `IPLSimulationInputs.baked = true` with the matching `bakedDataIdentifier`;
  `iplSimulatorRunReflections` then interpolates the baked energy fields of the probes around the
  listener and reconstructs an IR in the same output slot the real-time run uses.
- Runtime pathing: `iplSimulatorRunPathing` with `pathingProbes` (one batch per source),
  `visRadius` / `visThreshold` / `visRange`, `pathingOrder`, `enableValidation`,
  `findAlternatePaths` and a deviation model (UTD by default); the simulator's `numVisSamples`.
  Output: `IPLPathEffectParams` (3-band EQ plus world-space SH coefficients).
- Path effect: `iplPathEffectCreate` / `Apply` / `Reset` / `Release`; with `spatialize = false`
  it renders un-rotated Ambisonics, ready to mix with other Ambisonic buffers before decoding.
- Serialization: `iplProbeBatchSave` / `iplProbeBatchLoad` via `iplSerializedObject*`.
- Embree: `iplEmbreeDeviceCreate` (Embree 4.04 is linked into `phonon.dll`; scene type 1 takes the
  same `iplStaticMeshCreate` triangle data the GPU path already uploads).

Variation choice for reflections. `REVERB` puts source and listener at the probe: one bake serves
every radio, but it is listener-centric, so a radio in a cave heard from outside would get the
outside's (dry) response. `STATICSOURCE` bakes from one fixed source to every probe, which is
exactly a jukebox radio, and keeps coupled spaces right (cave reverb leaking out of its mouth). One
probe batch can hold several layers, so all radios of an area share the probes, one layer per
radio. Pathing data is a probe-to-probe graph and needs no per-radio layer.

## Feasibility spike 1: reflections

`src/test/java/dimblend/radio/acoustics/SteamBakeFeasibilityScratchTest.java` (scratch, opt-in
native test; run with `-PradioAudioNatives=...`). Machine: Ryzen 7 5800X3D (8C/16T), RTX 4070 Ti;
the game client was running on the same GPU during all runs. Order 1 (4 channels), 3 bands. Its
final revision runs on the synthetic hall; the world-mesh rows come from earlier revisions that
loaded `worldmesh.bin` instead (`loadMesh()` is still in the file).

| Measurement | Result |
| --- | --- |
| Embree scene build, dumped world mesh (13.7k triangles) | 173 ms |
| `UNIFORMFLOOR` probes, 48 m box, 2 m spacing, world mesh | 1207 probes in 78 ms (~2 per column: more than one floor level) |
| Bake, 1024 rays, 64 bounces, 2 s, 1 thread | 22.8 ms/probe |
| Bake, same, 4 threads | 6.5 ms/probe (scales ~linearly) |
| Bake, 4096 rays, 64 bounces, 2 s, 4 / 8 threads | 24.3 / 14.7 ms/probe |
| Bake, 4096 rays, 128 bounces, 6 s, 8 threads (world mesh / hall) | 17–20 / 15 ms/probe |
| `REVERB` bake, 1024 rays, 4 threads | 8.1 ms/probe |
| GPU (Radeon Rays) bake, 1024 rays, 64 bounces, bake batch 1 / 8 / 32 | 301 / 46 / 14.5 ms/probe |
| Baked data per probe, 2 s / 6 s saved | 9.4 KiB / 28.1 KiB (energy fields; gzip saves ~5%) |
| Save / load 814-probe batch (22.4 MiB), across two contexts | 29 ms / 17 ms |
| Cancel latency, Embree / Radeon Rays | 45 ms / 544 ms |
| Runtime baked lookup, 6 s IR, CPU or GPU simulator | 8–11 ms per update, first one 22 ms (2 s IR: 3–5 ms) |
| Runtime live update, 1024 rays, 128 bounces, 6 s: Embree / GPU, world mesh | 14–41 ms / 370–790 ms (GPU, 64 bounces, 2 s: 260–370 ms) |
| GPU live update while a 4-thread Embree bake runs | 588 ms vs 587 ms alone, no missing IRs |
| Wet energy, baked vs live Embree, stone hall with pillars, 6 positions | within ±0.3 dB |
| Wet energy, baked vs live Radeon Rays, same positions | baked 0.5–1.8 dB louder |

Findings that shape the design:

- CPU baking is both faster here and kinder to the game than GPU baking: Radeon Rays needs large
  bake batches to approach Embree's speed, competes with frame rendering, and cancels ten times
  slower.
- A batch baked in one context loads into another context's simulator (including the session's
  GPU simulator), so a background bake context never shares native objects with the sessions.
- The baked IR keeps the real-time energy (±0.3 dB against the same CPU engine). The Radeon Rays
  engine itself differs from Embree by 0.5–2 dB on the same scene; switching between a baked and a
  live GPU IR therefore steps the reverb level slightly (the convolution crossfades the change).
- **A listener outside every probe's influence gets the previous IR again, with no signal.** The
  mod must decide coverage itself and fall back to real time; it cannot ask Steam Audio.
- The probe generator's volume is the unit cube **centred on the origin** (the transform's
  translation is the volume's centre); the spike's early runs placed it off by half its size,
  which is what produced the empty probe sets. The generator still casts down from the top of its
  volume and takes the first floor it hits, so under a roof it lands on the roof; the mod places
  probes from its own voxel data instead.
- The dumped `worldmesh.bin` gives no wet field even on the production engine (its source sits
  inside a closed volume), so energy comparisons use a synthetic 48 × 12 × 32 m stone hall with six
  pillars; timings use the world mesh.
- Live GPU reflection updates on a real world mesh took hundreds of milliseconds here, far below
  the 20 Hz cadence the session schedules. Baked lookups would let the reverb follow a moving
  listener at that cadence. (Embree live updates were roughly 20× faster than Radeon Rays on the same
  mesh in this process; worth an in-game measurement on its own, independent of baking.)

## Feasibility spike 2: pathing (diffraction)

`src/test/java/dimblend/radio/acoustics/SteamPathingFeasibilityScratchTest.java` (scratch, same
setup). Scenes: the 48 × 12 × 32 m stone hall split by a wall at x = 0 that leaves an 8 m gap at
one end (scaled up to 192 × 192 m for the size rows), and the same hall split fully except for a
1 × 2 m doorway. Bake: 4 visibility samples, sample radius 1 m, threshold 0.1, `visRange` 50 m,
`pathRange` 100 m, 8 threads unless stated. Runtime: Embree scene, 4 vis samples, order 1.

| Measurement | Result |
| --- | --- |
| Bake, 48 × 32 m, 4 / 3 / 2 m spacing (96 / 176 / 384 probes) | 16 / 10 / 54 ms; 49 KB / 167 KB / 786 KB (~5.3 B per probe pair) |
| Bake, 96 × 64 m and 96 × 96 m, 2 m (1536 / 2304 probes) | 1.4 s / 3.0 s; 11.5 MB / 20.9 MB |
| Bake, 192 × 192 m, 4 m (2304 probes), 1 / 2 / 4 / 8 threads | 3.0 / 1.9 / 1.2 / 1.0 s; 11.5 MB (2.2 B per pair: pairs beyond `pathRange` store less) |
| Bake, 192 × 192 m, 3 m (4096 probes) | 4.3 s; 36.6 MB |
| `visRange` 24 instead of 50 | 10–30% faster, 13–25% more data |
| `iplSimulatorRunPathing`, 384-probe batch, Embree scene | 1–3.5 ms per run (first 11–13 ms) |
| Same on a custom-callback scene (Java tracer, like the direct worker), validation on | 2.2 ms per run |
| Validation + alternate paths after the route was walled up, 384 / 2304 probes | 80–100 ms / 11–13 ms per run |
| Progress callback `NULL` | the bake returns at once and stores nothing (0 bytes) |
| `iplPathBakerCancelBake` during a bake | the call returns, then the bake thread faults (access violation, process dies); at 29% and 98% progress, 1 and 4 threads |

Walking behind the partition, source 20 m from the gap (EQ low / mid / high):

| Listener | EQ | W coefficient |
| --- | --- | --- |
| deep in the shadow (z = −14) | 0.62 / 0.27 / 0.15 | 0.007 = Y00 / 40 m (the path through the gap) |
| halfway (z = 2) | 0.72 / 0.38 / 0.22 | 0.011 |
| at the shadow edge (z = 10) | 1.14 / 1.10 / 1.05 | 0.012 |
| in view (z = 14) | 1 / 1 / 1 | 0.011 = Y00 / 26 m (the straight line) |

Through the 1 × 2 m doorway (listener 6 m to the side of it): EQ 0.72 / 0.40 / 0.25 with probes
every 4 m and none in the doorway; a probe in the doorway raises it a little (0.75 / 0.44 / 0.29,
0.82 / 0.54 / 0.35 with a 0.4 m sample radius); 2 m spacing gives 0.84 / 0.59 / 0.41.

Findings that shape the design:

- Bakes are cheap; data grows with probes × probes within `pathRange` (~5 B per pair). A radio's
  neighbourhood at 4 m spacing is about a second of work and a few MB.
- **A path bake cannot be cancelled** (the cancel crashes `phonon.dll` 4.8.1) and **needs a
  progress callback**. Path bakes must be sized to finish quickly and run to completion.
- **When the listener sees the source, pathing returns the straight line** (EQ 1, SH of the direct
  direction): added on top of the direct path it would double it. The path is weighted by the
  hidden share of the direct path, `1 - bandOcclusion(occlusion, band)`, so it fades in exactly as
  the volumetric occlusion fades the direct sound out.
- **The SH coefficients carry 1 / path length** (W = Y00 / r, Y00 = 0.2821; checked at 26 m in
  view and 40 m through the gap). The session replaces Steam's 1 / r with a linear curve to zero at
  `AUDIBLE_RANGE`, so the coefficients are rescaled by `distanceGain(r) · r` with r = Y00 / W.
  A path longer than `AUDIBLE_RANGE` is silent, so `pathRange = AUDIBLE_RANGE`.
- EQ exceeds 1 near the shadow edge (1.14); it is clamped to 1.
- `enableValidation` alone changed nothing. With `findAlternatePaths` it drops a route an edit
  sealed (SH 0), but it cannot find an opening made after the bake (it searches the baked
  visibility graph): edits need a rebake; until then validation + alternate paths keep stale data
  from leaking sound through new walls.
- Doorways one block wide pass paths without dedicated probes; probes nearest the middle of each
  4 × 4 column cell are enough to start with.
- `shCoeffs` points into simulator memory that the next run overwrites: copy it on the worker.
- The SH are world-space; the path effect with `spatialize = false` therefore mixes straight into
  the reflection bus before the one orientation-dependent decode.

## Design

### Data model

- **Pathing region** (phase 1): one `IPLProbeBatch` per radio, probes within
  `AcousticPathing.REGION_RADIUS` (64 blocks) of the radio, at most 1500 (the column cell widens
  through 4, 6, 8, 12 and 16 blocks until it fits; ~5 B per probe pair keeps that near 11 MiB). One
  probe per column cell and floor level (walkable cells within 3 blocks of height in a column cell
  are one level): the walkable cell nearest the cell centre, centre 1.5 blocks above the floor,
  influence radius = cell size. Only air connected to the radio's own air counts (a flood fill
  through open cells), so sealed caves below cost nothing. A cell is open when its collision shape
  fills less than half the block (air, doors, fences, panes, carpets, plants, fluids); below the
  world is solid, above it open; an uncaptured cell makes the placement incomplete. The bake mesh
  reaches 8 blocks beyond the probe region. Frame origin: the lower corner of the radio's block.
- **Tile** (phase 2): a 16 × 16 × 16 block cell aligned to chunk sections; the unit of reflection
  baking, invalidation, persistence and runtime attachment. One `IPLProbeBatch` per tile, probes
  every 2 blocks with radius 2.5, one `STATICSOURCE` layer per radio (sphere centred on the radio,
  radius `AUDIBLE_RANGE`).
- **Walkable cell**: an open cell (as above) with a solid cell below and a second open cell
  above. Works under roofs and on every cave level.
- **State** per pathing region or tile layer: `MISSING → BAKING → VALID`, plus `STALE` (usable
  with safeguards, rebaked when idle). Keyed by the palette fingerprints of the sections the bake
  mesh spans.

### Scene stability ("nearly static")

- **Signature** (`AcousticRegionSignature`): once a second, on the client thread, a hash over the
  sections of a radio's bake region of each palette container's identity and acoustic version
  (`AcousticPaletteVersion`, bumped by every block change and chunk packet), plus which chunks are
  loaded. No capture is needed for this; a region is a few hundred sections. A change makes the
  bake `STALE` at once.
- **Verify** after the signature has held for 1 s: a capture compares the region's section
  fingerprints with the bake's. Equal (a reloaded chunk, a door opened and closed again) → `VALID`;
  different → `STALE` and due for a rebake. A bake read from disk starts `STALE` until verified.
- **Stable** when the signature has not changed for 5 s (pathing) or 10 s (reflection tiles), every
  chunk of the region is loaded (missing terrain bakes open air that looks valid), and the radio is
  not on a Sable structure. Moving structures are left out of bake meshes: the real-time direct
  path still occludes them.
- Bake captures freeze palettes without taking over the frame loop's palette watch, so real-time
  edit detection keeps working while a bake region is captured.

### Client idle gate

Baking only runs while the client has spare capacity:

- **Deep idle** (up to half the logical processors, at most 8 bake threads; bakes estimated at up
  to 10 s): game paused, window unfocused or minimised, or no camera or player movement for 30 s.
- **CPU idle** (1 thread, 2 on 12+ logical processors; bakes estimated at up to 3 s): system CPU
  load below 50% in each of the last three one-second samples, and the frame rate at least 90% of
  the lower of 60 fps and the frame rate limit. Load is sampled only while no bake runs (the
  samples restart after one), so the bake's own threads never count. The player may move. Where
  the JVM does not report system load, only deep idle opens the gate.
- Never without a level, on a loading or connecting screen, or during a resource reload.

Reflection tiles stop through `iplReflectionsBakerCancelBake` (45 ms) when the gate closes. Path
bakes cannot be stopped, so one starts only when its estimated time at the chosen thread count is
within the budget: 1 µs per probe pair on one thread (twice the open-terrain spike), divided by
threads^0.66 up to 3× (the spike's 1.6× on two threads, 2.5× on four). A larger region waits for
deep idle; at the 1500-probe cap a bake is about 2.3 s on one thread, so in practice every capped
region fits the CPU-idle budget. Native bake threads cannot be deprioritised from Java; the
thread count is the throttle.

### Bake worker

- One daemon thread for the whole client, with its own Steam Audio context and Embree device
  (one bake per process is an SDK rule), created on first use.
- Per job: build the mesh for the bake region from a frozen capture and the same
  `ReflectionGeometry.terrainMesh` the real-time reflections use (Sodium surfaces with voxel fill),
  leaving the radio's own block out as the live scene does; place probes from the same capture.
- Pathing jobs come first (cheap, and the largest audible change), nearest radio first; reflection
  tiles after them, nearest the listener first.
- Pathing settings: 4 visibility samples, sample radius 1, threshold 0.1, `visRange` 48,
  `pathRange` = `AUDIBLE_RANGE`.
- Reflection settings: 4096 rays, 128 bounces, 6 s simulated and saved (matching the renderer's
  6 s IR slot), order 1, convolution data.
- Publish: `iplProbeBatchSave` to bytes; each session loads them into its own context
  (`iplProbeBatchLoad`) and adds them to its simulator; the bytes also go to disk.

### Runtime pathing

- The direct worker's CPU simulator (custom-callback scene over the voxel tracer) is created with
  the pathing flag. When the radio's pathing batch changes, the worker loads it into that context,
  swaps it into the simulator and commits.
- Pathing rides on the direct job, since it needs that run's occlusion for the same pose: after
  a direct run, at most every 50 ms, one `iplSimulatorRunPathing` with the batch's frame (the
  callback scene is frame-free; the tracer maps local coordinates through the frame origin), then
  copy EQ and the four SH coefficients into a Java record. A run the cadence skips is owed: the
  next direct job is scheduled when it falls due even if the scene has not changed.
- Shaping on the worker: EQ clamped to 1 and multiplied per band by the hidden share of the direct
  path; SH rescaled to the session's linear distance curve. Both fade out over the last 8 blocks
  before the 64-block probe region ends, so leaving the region is not a step. Nothing left (in
  view, or no path) → no path field.
- Renderer: a path effect (`spatialize = false`, order 1) runs on the propagation-delayed input in
  the prepare stage; the spatial stage decodes `wet · wetGain + path` once (`wetGain` is now
  applied before the decode rather than after it; the decode is linear, so this alone does not
  change the reverb). A path field that appears fades in over one block; one that disappears is
  rendered one more block from its last value towards zero, then the effect resets.
- `STALE` pathing runs with validation and alternate paths, at most every 200 ms.

### Runtime reflections (phase 2)

1. Coverage check in Java: every probe within influence radius of the listener belongs to a
   `VALID` or `STALE` tile layer of this radio, and there is at least one. Otherwise run live.
2. Covered: set `inputs.baked = 1` with the radio's identifier and run reflections on the
   session's existing simulator, which has the covering tile batches attached (attach and detach
   on the reflection worker, followed by `iplSimulatorCommit`).
3. Hysteresis: switch to baked after two consecutive covered updates, back to live at once. The
   convolution effect crossfades each IR change as it does today.
4. Baked updates are cheap, so they run at the full 20 Hz motion cadence even while walking.

### Invalidation

- Pathing: any signature change in the region → `STALE` (validation + alternate paths) and a
  rebake once stable and idle. A radio removed, or moved onto or with a structure → its pathing
  is dropped.
- Reflection tiles: a block edit in a tile, or within 16 blocks of it, or within 16 blocks of the
  radio → not usable (immediate fallback to live); any other edit inside a tile's mesh region →
  `STALE` (kept, rebaked first when idle).
- Validity keys are palette fingerprints, not mesh bytes, so Sodium meshing an extra section does
  not count as a change.

### Persistence

- Files under `<gameDir>/dimblend-radio/acoustic-bakes/<world>/<dimension>/`, one per radio and
  product (`<x>_<y>_<z>.pathing`, later `<x>_<y>_<z>/<tile>.reflections`). `<world>` is the
  singleplayer save folder or the server address, sanitised.
- Content: magic, format version, bake settings key (a hash of every setting that shapes the
  bake), the radio's position (frame origin; the region follows from it), probe count and column
  cell size, section keys with their palette fingerprints, the serialized probe batch, then a
  CRC32C over all of it. Written by the bake worker after a bake (to a temporary file, then moved
  into place). A file for other settings or another radio reads as none; a damaged one is deleted
  and baked again.
- On radio start: read on the bake worker, then compared with fingerprints from a one-off capture
  of the region on the client thread. All equal → `VALID`; otherwise `STALE` (still useful with
  validation, rebaked when idle); missing sections → wait until they load.
- Disk budget: least recently used files beyond 512 MiB are deleted after each bake; reading a
  file counts as use.

### Budget

- Pathing: a few MB per radio (about 800 probes per floor level within 64 blocks; at most 1500
  probes, ~11 MiB), baked in about a second on four threads. Each playing radio's session holds
  its own loaded copy of the batch in native memory.
- Reflections: 28.1 KiB per probe and layer at 6 s. A 32-block radius around a listener is ~800
  probes per floor level (~22 MiB); the full 96-block radius would be ~7200 per level (~200 MiB).
  Keep an LRU budget (default 64 MiB in memory, configurable) ordered by distance to the listener,
  and bake outward from where the listener actually is.
- Shorter saved IRs with a parametric tail (`BAKEPARAMETRIC` plus a hybrid reflection effect)
  would cut memory to a third, but the renderer uses pure convolution today; deferred.

## Phase 1 as built

| Part | Where |
| --- | --- |
| Bake and probe-batch bindings | `acoustics/SteamBaking`, `acoustics/SteamAudio` |
| Static mesh shared by live reflections and bakes | `acoustics/SteamStaticMesh` |
| Runtime pathing on the direct engine (`attachPathing`, `runPathing`) | `acoustics/SteamSimulation` |
| Shaping (hidden share, distance curve, coverage fade) | `acoustics/AcousticPathing`, `acoustics/PathingField` |
| Path effect mixed into the reflection decode | `acoustics/SteamRenderer` |
| Region change detector; terrain-only capture and openness | `acoustics/AcousticRegionSignature`, `acoustics/AcousticSnapshot` |
| Probe placement, baker, bake record, files | `acoustics/bake/PathingProbePlacement`, `PathingBaker`, `PathingBake`, `AcousticBakeFiles` |
| Idle gate | `client/AcousticIdleGate` |
| Per-radio state, worker, disk | `client/AcousticBakeScheduler` |
| Hand-over to sessions; pathing cadence | `client/RadioAcousticController`, `client/RadioSimulationSession` |

Departures from the design above, all reflected in the sections it describes: the probe cap is
1500 rather than 3000 (data grows with the square), regions are verified after 1 s and baked after
5 s, the path fades out over the region's last 8 blocks, the load samples skip bakes instead of
subtracting an estimate, and pathing runs inside the direct job.

Tests: `AcousticPathingTest` (shaping), `PathingProbePlacementTest`, `AcousticBakeFilesTest`,
`AcousticIdleGateTest` (gate and bake estimate) and the native `PathingBakerTest` (bake and lookup
in the partitioned hall at a world offset, a disk round trip into another context's simulator,
and the path effect's level and fade-out in the renderer).

The feasibility spikes stay in the tree as `*ScratchTest`. The two `cancelCallback*` tests in
`SteamPathingFeasibilityScratchTest` call `iplPathBakerCancelBake`, which kills the JVM; they are
`@Disabled` and only worth enabling one at a time to re-check a newer `phonon.dll`.

## Risks and open questions

- Frame-time impact of native bake threads on the game's own threads has not been measured in
  game; the idle thresholds need tuning with real telemetry.
- The captures that verify and start a bake run on the client thread over the whole region
  (~10 × 10 chunks). Unchanged sections reuse cached palette copies, but the first capture of a
  region, or one after many edits, copies and fingerprints every non-air section.
- A path bake that is already running cannot be stopped; the size cap bounds it to a few seconds.
- Path level against the direct path: verified through the SH magnitudes and the rendered level
  of the path effect (native test); not yet compared by ear against the panned direct sound.
- Probe spacing vs. early-reflection accuracy (phase 2): interpolating energy fields over 2 m
  smooths early reflections. Acceptable for reverb character; worth listening tests in narrow tunnels.
- Coverage edges (ladders, flying, elytra): the listener leaves probe influence and falls back to
  real time; for pathing that means no path field, which the direct path already handles.
- Unverified: whether the baked reflection lookup drops probes the listener cannot see (the SDK
  exports `iplProbeNeighborhoodCheckOcclusion`).
- Multiplayer: other players' edits invalidate like local ones; nothing is server-side.

## Phases

1. **Pathing** (implemented; in-game verification pending): bake bindings, voxel probe placement,
   stability and idle gates, bake worker, persistence, runtime pathing with the path effect,
   invalidation. Native tests: bake and lookup in the hall fixture, persistence round trip across
   contexts, renderer level of the path field; unit tests for placement, gates and the shaping
   maths.
2. **Static-source reflection tiles** on the same worker, gates and persistence; runtime coverage
   switch; memory budget.
3. A debug readout of bake state and progress; in-game tuning.
