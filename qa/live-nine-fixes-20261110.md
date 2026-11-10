# Nine fixes live verification

## 2026-11-11 bridge cleanup

- Test client PID 72048 exited after WM_CLOSE; `latest.log` recorded `Stopping!` and `All dimensions are saved` at 01:00:29.
- Inbox was empty. Python PID 27992 was the TrainTripWorld `agent_bridge.py` process, created at 00:39:43 with parent PID 72048; its status heartbeat stopped at 01:00:26.
- The bridge exits when `player_position()` reports no world; its source exposes no external stop operation. After confirming no Minecraft Java process remained, terminated only PID 27992 and verified it exited. The lock file was retained.
- Restart remains pending: host CPU was above the agreed threshold.

## GameTest availability

- NeoForge 21.1.249 `GameTestHooks.isGametestEnabled()` requires `!FMLLoader.isProduction()` even when `neoforge.enableGameTest` is true. The production pack cannot run `/test` through that property. GameTests need a separate non-production run.
- The client reached the title screen and the dedicated world with the revised MixinExtras declaration. Bridge ping returned `pong=true`; the rotating dimension used the Rapier physics provider.
- Loot Journal screenshots from the first run do not show the HUD and do not establish whether the icon problem reproduces. The input and render fixtures are not yet validated in game.
- Correction: bridge `screenshot` uses Minescript's default render-loop executor, drained at `RenderLevelStageEvent.AFTER_LEVEL`; it calls `Screenshot.grab` on the main render target. `GameRenderer.render` draws `gui.render` later, so those captures cannot establish HUD visibility even if `hideGui` is false. HUD-complete capture remains unverified.

## World anchor acceptance

- Stage 1 hold preserves its starting Y and tilt. Read/free/hold samples can establish ground-joint behavior, drift, wheel pivots and removal, but cannot establish a production nominal height or level the deck. Capture raw deck world height separately for later calibration.

## Resource gate

- After the craft and COM builds, four CPU samples were 89.65%, 73.43%, 68.16%, and 87.63%; available memory was 19,041, 18,932, 18,991, and 19,842 MiB respectively. The required three consecutive samples below 60% were not met; the client was not launched.
- A separate 8.8-second CPU sampling window measured visible processes at 37.26% of 16 logical cores while PDH reported 80.9% busy. The 43.6-point residual was unresolved and cannot establish a lower total load.
- The client remains closed. No in-game tests were run against the final COM-corrected experience jar or the revised craft jar. Resume with three consecutive CPU samples below 60%, sufficient memory, no concurrent build, and verification of the deployed hashes before launch. The batch remains incomplete.
