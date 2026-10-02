# Radio cave reverberation research

Status: superseded proposal. The current implementation uses Steam Audio acoustic
simulation and convolution, not enclosure detection or preset EFX reverb.
Date: 2026-10-01. Target: Minecraft Java 1.21.1 / NeoForge, dimblend-radio.

Historical implementation note: the first backend used standard OpenAL EFX REVERB, 24
source rays with one additional reflection, explicit terrain/Sable intersections,
and a global sampling budget. Listener probes, connection checks, extended rays,
and snapshot/worker tracing below remain future refinements. Real OpenAL loopback
tests verify the tail and distance gain; GameTests verify actual terrain and rotated
Sable room geometry. See README.md for the current controls and limitations.
The implemented enclosure gate also requires wall-normal evidence so a broad roof
over an open floor does not create dense cave reverberation.
Live cave diagnosis on 2026-10-01 showed the original wall threshold reduced a
fully enclosed, floor/ceiling-dominated cave to send 0.10 with a 1.2-second decay.
The corrected gate transitions at wall-hit fractions 0.04-0.16; room scale uses
upper-quartile distances and a longer stone-cave decay baseline. The same live
scene then measured send 0.585 and decay 2.8 seconds, with a valid attached EFX slot.

## Recommendation

Use sparse CPU block raycasts to estimate enclosure, room scale, and reflection
persistence, then drive an OpenAL EFX parametric reverb on radio channels only.
Keep the existing mono PCM, playback clock, and dry sound path. No GPU ray tracing,
server simulation, impulse-response convolution, or new native SDK is necessary.

The critical control is a nonlinear enclosure gate: a nearby floor alone must not
produce a long reverb tail. Caves have hits above and around the source, and their
reflected rays keep hitting surfaces. Outdoors, upward rays and floor reflections
usually leave the sampled region. Use these differences together.

This is an acoustic approximation with deliberately strong cave tuning. Sparse
rays cannot reconstruct an accurate impulse response or reliably solve diffraction
through cave mouths and around corners.

## Verified integration points

- `RadioInstance` already supplies mono, spatial, streaming audio and follows the
  source's world position. `RadioLibrary` / `RadioPcmFeed` can remain as they are.
- `SoundEngineAccessor.dimblend$radioChannels()` already exposes the radio's
  `ChannelHandle`. `RadioController.playingGain()` demonstrates executing work on
  that handle. Add a `Channel.source` accessor for the OpenAL source ID.
- Minecraft's `ChannelAccess.ChannelHandle.execute()` dispatches to the sound
  executor. Perform EFX creation, updates, attachment, and deletion there, with a
  valid current context. Read world geometry on the client thread; transfer only
  immutable acoustic parameters to the sound executor.
- Minecraft's `Channel.attachBufferStream()` pumps four buffers, each sized for
  one second. Processing reverb in `RadioPcmFeed` would bake parameters into audio
  queued ahead of playback. EFX can respond to the current environment directly.
- `BlockGetter.clip()` traverses blocks and intersects selected voxel shapes.
  `ClipContext.Block.COLLIDER` and `Fluid.NONE` are suitable starting policies for
  dry-air acoustics. Acoustic shape/material overrides should remain configurable.
- `ClientChunkCache.getChunk(..., false)` returns null for unavailable chunks;
  with true it can return an empty placeholder. A loaded-only raycast must expose
  unknown segments rather than interpreting missing terrain as open air.
- `Library.init()` creates the OpenAL device/context; `Library.cleanup()` destroys
  them after channels. Effect resources must be scoped to that context and released
  before destruction, then recreated after device changes or sound reloads.

These findings were checked against the local Mojmap sources in
`E:/misc/mcsrc-1.21.1`: `com/mojang/blaze3d/audio/{Channel,Library}.java`,
`net/minecraft/client/sounds/{ChannelAccess,SoundEngine}.java`,
`net/minecraft/world/level/{BlockGetter,ClipContext}.java`, and
`net/minecraft/client/multiplayer/ClientChunkCache.java`.

## Sparse sampling design

Initial settings below are tuning proposals, not measured performance results.

1. For each audible radio, cast 24 equal-area directions distributed over a sphere
   from a nearby air point, using a stable Fibonacci or stratified distribution.
   Cover upper and lower hemispheres evenly. Avoid a new random orientation every
   update: it causes audible parameter fluctuations.
2. Trace up to 64 blocks per segment. For primary hits, trace one reflected segment
   as well: `reflected = direction - 2 * dot(direction, normal) * normal`.
   Offset the new origin slightly into air to prevent hitting the same surface.
   Initial maximum: 48 segments per radio evaluation. A second bounce is an optional
   quality setting, increasing the maximum to 72.
3. Exclude the emitter's own voxel on the initial segment, and check that the probe
   starts in air. A jukebox center is inside a solid block; treating its own geometry
   as the room would generate false enclosure. Nearby mounting blocks still count.
4. Record primary hit fraction H, upper-hemisphere hit fraction U, conditional
   second-hit fraction B, robust hit-distance statistics, and material estimates.
   A secondary miss ends that path. It does not supply additional reflection energy.
5. Treat a max-range miss as "no nearby boundary", not proof of escape to sky.
   Large enclosed caverns can exceed the range. When many rays reach the cap,
   opportunistically extend a small subset to 128 blocks under the same budget.
   Sky visibility may be an additional hint, never the sole cave classifier.
6. Unknown chunks do not count as hits or confirmed open directions. Track coverage
   separately; retain a recent reliable estimate briefly, then fade toward dry if
   coverage stays poor. Do not divide a tiny number of known hits into a confident
   fully enclosed estimate.

For an initial artistic enclosure control, define:

```text
smoothstep(a, b, x): t = clamp((x-a)/(b-a), 0, 1); return t*t*(3-2*t)
enclosure = smoothstep(0.65, 0.92, H)
          * smoothstep(0.25, 0.75, U)
          * (0.4 + 0.6*B)
targetSendGain = 0.55 * enclosure * materialFactor * connectionFactor
```

Fractions use reliable samples only, with a minimum coverage requirement before
publishing. Material and connection factors are clamped to [0, 1]. This mapping
intentionally emphasizes roofed spaces; it is not a physical room-acoustics law.
A flat outdoor field has H around 0.5 and U around 0, giving zero send. A fully
enclosed stone cave with B around 0.8 gives enclosure around 0.88, before other
factors. Roofs without surrounding walls generally fail the H gate. Thresholds
need tests at cave mouths, under trees, in ravines, and under bridges.

Start sampling at the source, because that is where sound excites the room. Add a
shared 12-ray listener probe at 2 Hz and one source-to-listener visibility check per
evaluation. Use the actual sound listener transform, consistent with RadioAudibility.
When both endpoints are enclosed and directly connected, connectionFactor is 1.
When the listener is outside, reduce wet gain without forcing it to zero: a cave
radio can still send reverberant sound through its entrance. When the direct path
is blocked, a few reflection-point-to-listener checks can provide evidence of shared
airspace; use conservative wet gain otherwise. This remains a heuristic, not proof
that two points occupy the same room. Leave full occlusion/diffraction for later.

## Reverb rendering and tuning

Use one EFX effect slot and effect per processed radio, with one auxiliary send
on that source. This isolates room parameters and permits muting a radio's wet
output at the existing 64-block boundary. Allocate lazily, initially cap processing
at four audible radios, and keep additional radios dry if resources are unavailable.
Prioritize nearby radios with hysteresis so slot reassignment does not oscillate.

Prefer `AL_EFFECT_EAXREVERB`, fall back to `AL_EFFECT_REVERB`, and fall back to dry
playback when EFX or effect allocation is unavailable. Query `ALC_EXT_EFX` and the
actual `ALC_MAX_AUXILIARY_SENDS`; this design needs one send, not four. Check errors
and legal parameter ranges. Reattach the effect to its slot after changing its
parameters; use a send filter for per-source gain and high-frequency damping.

| Environment | Initial decay-time target | Initial send-gain target |
| --- | --- | --- |
| Open field / exposed hillside | No new reverb input | 0 to 0.01 |
| Cave mouth / partly enclosed shelter | 0.5 to 1.5 seconds | 0.05 to 0.20 |
| Small stone cave / tunnel | 1.2 to 2.5 seconds | 0.25 to 0.45 |
| Large enclosed stone cavern | 2.5 to 4.5 seconds | 0.40 to 0.60 |

Send gain is an EFX input multiplier, not a literal final wet/dry percentage.
Effect gain, early/late gains, source gain, and attenuation all affect the result.
Calibrate by listening and measurement. A strong diffuse tail with a preserved dry
signal should produce the requested severe cave reverberation without obscuring
every musical transient.

Use robust distances to select room scale, not just hit count. Small tunnels can
be fully enclosed yet should not automatically receive the longest tail. Start
with a clamped room-size mapping into the ranges above; tune stone toward long
decay and wool/leaves toward shorter, darker decay. Material values are artistic
defaults, not measured absorption coefficients.

Start with high diffusion, 10-50 ms early-reflection delay, 10-40 ms late delay,
and high-frequency decay ratio around 0.5-0.8. Keep values within the chosen EFX
effect's limits. Few rays should control a dense reverb, not be rendered as a
handful of repeating delayed copies, which would sound like discrete echoes.

Smooth sends and room parameters with time-based interpolation: approximately
0.3-0.6 seconds when entering enclosure and 0.2-0.4 seconds when leaving it.
Stop feeding the tail outdoors; allow its existing energy to decay naturally.
At category mute, stop, disconnect, dimension change, or the strict 64-block cutoff,
mute/clear the radio's slot according to the existing radio audibility contract.
Verify wet distance attenuation explicitly; automatic EFX attenuation must not be
assumed to match Minecraft's linear dry curve. Avoid applying the same attenuation
twice. Retain gain headroom for the summed dry/wet output.

## Scheduling and compatibility

- Target source evaluations at 4 Hz while moving, slower when stationary. Sample
  the listener once for all radios. A 48-segment source evaluation at 4 Hz plus
  twelve listener rays at 2 Hz is at most 216 segments/second for one radio,
  excluding visibility checks and extended rays.
- Impose a global budget, initially 16 ray segments/client tick plus an elapsed-time
  limit, and spread evaluations across ticks. The budget is approximately 320
  segments/second at 20 client ticks/second, not per radio. Lower each source's
  refresh rate as the number of radios rises. Count secondary and visibility rays.
  A segment's cost depends on length and geometry; ray count is not a CPU guarantee.
- Invalidate on relevant nearby geometry changes and movement; discard an unfinished
  sample set if its origin moves too far. Use wall-clock smoothing and reset cached
  samples across dimensions or level replacement.
- Keep initial world access on the client thread. If profiling shows it is too costly,
  use immutable loaded-geometry snapshots for worker tracing. Do not read ClientLevel
  unsafely from an audio or worker thread.
- Sable needs world-aware geometry queries, not just projected source coordinates.
  The existing SubLevelProjection positions audio but does not establish a raycast
  path through moving structure geometry. Verify Sable's available interception or
  transform rays into intersecting plots and compare hits in world distance. A first
  terrain-only prototype must explicitly report that structure walls are unsupported.
- Detect Sound Physics Remastered or another owner of source sends. Prefer one owner:
  delegate radio acoustics to it or disable local EFX with a clear configuration.
  Do not let two implementations continuously overwrite the same send/filter.
- Sound Physics Remastered's inspected 1.21.1 defaults disable updates for moving
  music/RECORDS sounds. Installing it alone is therefore not sufficient evidence that
  this long-running radio will update correctly; verify configuration and runtime hooks.

## Alternatives and evidence

Sound Physics Remastered is the closest working reference. Its inspected 1.21.1
branch traces a spherical distribution from each sound, adds reflected segments,
weights reverb sends by path length/materials, and uses four EAX reverb presets.
Defaults are 32 primary rays and four additional bounces. It also contains its own
distance correction for wet sound. Our narrower design needs fewer segments and
one send per source. The project is GPL-3.0 while dimblend-radio currently declares
All Rights Reserved; use it as a reference or optional installed integration, and
review licensing before copying code or embedding it.

Steam Audio supports reflection simulation with configurable ray/bounce counts and
parametric or hybrid rendering. It is a credible option for future full propagation,
but would require native SDK integration and a geometry bridge; that is unnecessary
for this initial enclosure-driven effect. A hand-written PCM reverb adds DSP and
buffer-latency work that existing OpenAL EFX avoids.

Sources inspected:

- [Sound Physics Remastered 1.21.1 SoundPhysics.java](https://github.com/henkelmax/sound-physics-remastered/blob/db2799e9b040382cfa6ea388c2d18dfaa97353a2/common/src/main/java/com/sonicether/soundphysics/SoundPhysics.java)
- [Reference configuration defaults](https://github.com/henkelmax/sound-physics-remastered/blob/db2799e9b040382cfa6ea388c2d18dfaa97353a2/common/src/main/java/com/sonicether/soundphysics/config/SoundPhysicsConfig.java)
- [Reference reverb presets](https://github.com/henkelmax/sound-physics-remastered/blob/db2799e9b040382cfa6ea388c2d18dfaa97353a2/common/src/main/java/com/sonicether/soundphysics/config/ReverbParams.java)
- [Reference raycast helper](https://github.com/henkelmax/sound-physics-remastered/blob/db2799e9b040382cfa6ea388c2d18dfaa97353a2/common/src/main/java/com/sonicether/soundphysics/utils/RaycastUtils.java)
- [OpenAL Soft EFX definitions and limits](https://github.com/kcat/openal-soft/blob/master/include/AL/efx.h)
- [Steam Audio simulation API](https://valvesoftware.github.io/steam-audio/doc/capi/simulation.html)

## Implementation and validation sequence

1. Add a radio-only EFX backend with a manually selected cave preset. Verify live
   gain updates, pause/stop, category sliders, device reload, 64-block wet cutoff,
   and multiple radios before adding geometry-driven controls.
2. Add the loaded-only sparse sampler and debug statistics for H/U/B, room scale,
   coverage, send gain, and elapsed sampling cost. Unit-test classification using
   synthetic flat ground, roof-only, closed-box, and missing-chunk scenes.
3. Connect smoothed parameters and compare the same dry audio in open terrain,
   a small tunnel, large cave, cave mouth, forest, ravine, bridge, and soft room.
   Include source/listener on opposite sides of an entrance or wall.
4. Verify moving Sable radios separately, along with geometry edits, low frame rate,
   multiple sources, installed acoustic mods, and unsupported EFX devices.

Acceptance targets: negligible new wet input in open areas, unmistakable sustained
stone-cave tails, no abrupt parameter jumps, no audio-thread world queries, and
bounded sampling work. Measure p95/p99 client sampling time and listen/record in
the actual game before accepting numerical tuning or claiming CPU performance.
