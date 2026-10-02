# Radio acoustics review

Block-edit latency follow-up: observed palette writes now invalidate the acoustic
snapshot immediately, including first placement in an air-only section. Capture runs
on the next camera frame; the 500 ms poll is only a coverage/chunk fallback. Edited
sections withdraw old Sodium geometry and validate future builds against captured
block contents, so an old worker cannot restore stale walls. Geometry changes bypass
the motion cadence, and waiting jobs use the newest snapshot when they start.
Identical lighting-only meshes no longer trigger new simulation.

Installed build validation: 157 tests passed, zero skipped. Restarted normally into
the same singleplayer test world, then performed three reversible client-only edits
at (-286,34,51), restoring the original air block after each test. Edit-to-published
direct/reflection results were 14.44/274.05 ms, 5.91/171.59 ms, and 3.75/166.91 ms.
These measure simulation readiness, not speaker output; queued audio adds latency.
Raw measurements are in build/block-edit-latency{,-2,-3}.txt.

Stuttering follow-up: the user's artifact was rapid interrupted playback, not changing
treble. The former two-buffer queue could repeatedly stop and restart with unchanged
headroom. A real OpenAL device-clock regression using deliberate 35/65 ms stalls
reproduced 9 restarts with the old shallow queue and 1 with adaptive recovery. Streams
now start at about 32–35 ms at common rates, add two buffers on an actual underrun,
and retain the learned depth for the track, capped around 90–100 ms. Counts are logged
without requiring debug mode. Both attempted live probes had no active radio stream,
so this demonstrates and fixes the failure mechanism; live listening confirmation
remains pending.

Responsiveness follow-up: a live 20-second probe found sound-executor queue p95
2.16 ms, while view publications were 26.61 ms apart at p50 and 53.46 ms at p95.
Minecraft.runTick calls SoundManager.updateSource BEFORE mouse processing and camera
setup, so the earlier hook still published last frame's pose. The hook now runs
after Camera.setup in GameRenderer.renderLevel, and client ticks no longer overwrite
that view. Direct rays can update on each camera frame rather than waiting 50 ms.

The direct path now delays source PCM BEFORE applying current listener occlusion.
Steam Audio's GainEffect adds a further exponential gain lag (25% toward the target
per audio block); direct spectral filtering remains native, while overall attenuation
uses a bounded 5 ms ramp. A native test verifies that a source with 200 ms propagation
delay becomes blocked within three 512-sample blocks, rather than replaying old clear
PCM from the delay line. The stereo queue is now about 21–23 ms at 44.1/48 kHz.
Greedy mesh extraction also avoids allocating a coordinate array for every voxel.
The JFR recording contains 1,701 execution samples (high sampling noise); it is useful
for hotspot leads, not precise CPU shares. Its startup heuristic is triggered by
module metadata even though the game was already running in-world and unpaused.
Diffraction has not been implemented.

Latency/GPU-only follow-up: Steam Audio 4.8.1's GPU gather kernel launches at least
256 rays without guarding smaller buffers. The runtime now allocates and traces 256
GPU rays, uses 512-frame PCM blocks with roughly 32–35 ms of queued stereo at 44.1/48
kHz, reads the fresh interpolated camera transform, and reuses padded meshes during
movement. GPU failure disables all acoustic processing; CPU reflection fallback has
been removed at the user's request. Validation: 142 tests passed with GPU natives,
zero skipped. The build has been installed in TrainTripWorld with a verified SHA-256.
The earlier implementation and original review below describe prior states.

Implementation update: all six findings below are now addressed in the `radio-acoustics`
working tree here. Nearby radios share snapshots and use mutation-versioned palette copies.
Native regression tests also exposed and fixed zeroed GPU material transmission defaults.
Validation: 137 tests passed, zero skipped, with native GPU audio enabled; the release jar
build passed. The previous review-only repros are now `AcousticRecoveryTest`, supplemented
by session lifetime, cache, geometry coverage, audibility and buffering tests. The running
game has not been restarted or updated. The review below is the historical diagnosis.

Reviewed branch `radio-acoustics`, commit `da9ca7c`, against its merge base with `master`. Review performed on 2026-10-02 in a detached worktree. No production code or installed game files were changed.

## Observed game symptoms

`E:/misc/TrainTripWorld/logs/latest.log` records a non-finite reflection field at 08:48:52 (line 7835), and buffer underruns at 08:49:09, 08:52:03, and 08:52:44 (lines 7854, 8084, 8097). This establishes that invalid wet output and starvation really occurred. It does not establish the original producer of the NaN. In particular, the geometry log for that run says `render-mesh(0 sections)+voxel`, so the Sodium decoder defect below must not be claimed as the proven cause of that warning.

## Findings

### P1 — Non-finite recovery can leave reverb silent indefinitely

`SteamRenderer.java:92-98` clears wet output and calls `iplReflectionEffectReset`, but never requests a fresh simulation. `AcousticUpdateGate.java:74-77` suppresses further simulations while positions and geometry are unchanged. Steam Audio 4.8.1's `OverlapSaveConvolutionEffect::reset` clears the active convolution IR (`mPrevFFTIR`); merely reusing the published reflection parameters does not restore it. A new simulation must publish fresh data.

Reproduced using the exact reset operation from the handler, with finite PCM and unchanged geometry: wet energy was 1.366793 before reset, 0.0 after the decoder tail drained, and 2.025630 after an explicit resimulation. The review regression test fails against the current implementation. The global `NAN_WARNED` flag also hides subsequent incidents for the remainder of the process.

Fix direction: report the reset to the session, invalidate the reflection update gate and request fresh output; use a bounded retry/fallback policy and per-session counters. This finding explains why reverb can remain absent after the logged warning, without asserting what originally produced the NaN.

### P1 — GPU fallback releases native IR storage while audio can still use it

`RadioSimulationSession.java:130-142` closes the GPU engine before taking the session monitor, publishes the new engine separately, and retains old `reflectionOutputs` until CPU simulation finishes. `process()` is synchronized, but engine destruction is outside that synchronization. It can therefore render with an old pointer concurrently with destruction, or create a renderer for the new engine while still holding the old outputs.

This is a native lifetime defect, not just Java visibility: Steam Audio 4.8.1 `CSource::getOutputs` returns the address of the source-owned `reflectionOutputs.overlapSaveFIR`; `CReflectionEffect::apply` dereferences it. Keeping the JNA `SimulationOutputs` object alive does not retain that native source. The implementation's `SteamSimulation.close()` releases the source and simulator.

Trigger: a GPU run/upload throws and takes the CPU fallback path after output has already been published. Potential consequences are invalid output or native access violations. This path was established by source inspection, not induced in the live game.

Fix direction: withdraw the old outputs and retire the renderer under the same synchronization as audio processing before releasing the old source; publish a complete replacement engine/renderer/output generation together.

### P1 — Expensive renderer construction runs on the shared sound executor with too little buffering

`RadioSimulationSession.java:191-192` constructs the renderer during PCM processing. Its constructor creates convolution and HRTF effects; `BinauralSpatializer.java:53` calls `iplHRTFCreate`. The SDK explicitly says to avoid creating HRTFs on the audio thread. Measured renderer construction in the review probes took about 50–69 ms on this machine.

Meanwhile `RadioStreamingChannelMixin.java:31-40` reduces buffering to two 2048-frame blocks: 92.9 ms total at 44.1 kHz, with only 46.4 ms left when the first block becomes refillable. Minecraft's `Channel.pumpBuffers` performs the read synchronously on its sound executor. The 20 ms timer only queues more work onto that same executor; it cannot refill while that executor is blocked. Creating a renderer for another radio can also delay existing streams.

This is a concrete starvation mechanism consistent with the recorded underruns, though the log has no timing trace tying each underrun to a particular blocking call. Recovery restarts OpenAL only after a gap has already occurred.

Fix direction: prepare renderer/HRTF resources off the sound executor with safe ownership transfer; use an audio deadline budget and enough buffered PCM to tolerate scheduling and initialization spikes.

### P2 — Degenerate triangles pass the geometry safety filter

`SectionMeshDecoder.java:80-92` validates a normal made from corners 0, 1 and 3. `AcousticMesh.java:125` subsequently emits triangles (0,1,2) and (0,2,3) without validating either actual triangle. A quad with corners `(0,0,0), (1,0,0), (1,0,0), (0,1,0)` passes the decoder and emits a zero-area first triangle. The review regression test reproduces this.

Thus the claimed protection against degenerate GPU geometry is incomplete. This is a candidate contributor to numerical instability when such model geometry is present, not the established cause of the logged NaN.

Fix direction: validate the area and finiteness of every emitted triangle; preserve a valid triangle from a collapsed quad rather than submitting its degenerate partner.

### P2 — Hybrid geometry drops translucent walls in mixed sections

`SodiumGeometrySink.java:54-60` deliberately skips translucent passes and promises voxel fallback. However `SectionGeometryCache.java:90-92` declares an entire section covered as soon as it has one retained quad, and `AcousticMesh.java:51-54` skips every voxel in covered sections.

In a section containing an opaque floor and stained-glass walls, the floor marks the section covered, the glass is excluded from render geometry, and the voxel path cannot restore it. Similar omissions affect solid geometry rendered outside the captured chunk passes. Room reflections can therefore change as Sodium coverage arrives, even without a physical opening in the room.

Fix direction: track coverage at the omitted block/geometry level, or fall back to a complete voxel section whenever render coverage is incomplete. Existing tests only verify that whole-section skipping works; they do not verify mixed-material completeness.

### P2 — Music suppression still uses the old 64-block range

`RadioAcousticController.AUDIBLE_RANGE` and `RadioSimulationSession.distanceGain` make simulated radio audible out to 96 blocks. `RadioAudibility.java:50-54` still returns inaudible at 64 blocks when deciding whether to suppress background music. Between 64 and 96 blocks, vanilla music can resume over an audible radio. At 64 blocks, the new direct distance gain is still one third.

Fix direction: derive suppression from the active playback path and its actual audibility range, retaining the old range for the non-simulated path.

## Additional performance concern

Snapshot capture runs on the client thread every 500 ms per selected session (`RadioAcousticController.java:71-72`). It copies all non-air section palettes in a radius of 120 blocks, then serializes and hashes every copied palette (`AcousticSnapshot.java:177-183`, `AcousticUpdateGate.java:43-54`). This work happens before the update gate can decide nothing changed, and up to four nearby radios duplicate the same terrain work. This is a plausible source of periodic frame/GC spikes, but it was not profiled in-game in this review. Share terrain snapshots and use dirty-section versions if a capture confirms the cost.

## Validation and limits

- Ran the native-enabled suite with JDK 21 and the installed LWJGL natives, using the RTX 4070 Ti.
- Final result: 129 tests run, 127 passed, two review-only regression tests failed as expected. No skipped tests in the final run; the existing world-mesh dump was copied into the detached worktree for replay.
- The two new repros are in `dimblend-radio/src/test/java/dimblend/radio/acoustics/ReviewProbeTest.java`.
- Several existing native tests only print diagnostics, including concurrent apply and world-mesh playback. They do not assert signal validity or audibility. The world-mesh replay printed approximately `1.29e-12` wet energy and still passed; that result requires scene-specific interpretation and is not independently proof that the scene should reverberate.
- Vanilla sound-thread behavior was verified against `E:/misc/mcsrc-1.21.1`; native IR ownership/reset behavior against Steam Audio tag `v4.8.1`, checked out under `build/steam-audio-source`.
- No live game launch, world mutation, jar replacement, or branch modification was performed. The original workspace remains on `master` and clean.

Reproduce the review tests from this worktree with:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21'
.\gradlew.bat :dimblend-radio:test -PradioAudioNatives=E:/misc/TrainTripWorld/TrainTripWorld_1.21.1-NeoForge_21.1.249-natives --tests '*ReviewProbeTest' --console=plain
```
