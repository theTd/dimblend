# DimBlend Radio

红石点播电台：空唱片机 + 顶部信号选台 + 侧面信号调音量。同台附近所有人听同一曲（服务端定曲定钟），各客户端本地解码。

## 玩法

- 游戏根目录建 `dimblend_radio/1` ~ `dimblend_radio/14`，每个文件夹丢音频文件（`ogg/mp3/flac/wav`，混放可）。
- 空唱片机（不能有唱片）：
  - 顶部输入 `0/15` → 不接管，原版行为。
  - 顶部输入 `1~14` → 播对应文件夹，文件夹内随机轮播，曲间 5 秒。
  - 侧面（水平四面 max，底部忽略）`0` → 停；`1~15` → 音量 `10%~150%`（side 10 = 原曲响度；
    &gt;100% 按每首余量线性放大，满幅母带没有余量则封顶 100%，不削波不压缩）；变音量即时、不断音。
  - 顶部输入可用：贴在顶部的模拟拉杆、顶部红石线、被充能的顶部方块（其上红石线/比较器指入）。
  - 侧面输入可用：贴在侧面的拉杆/模拟拉杆、贴着侧面的红石块、指入的红石线/中继器/比较器；放上/拿走即时生效。
  - 空唱片机不中继强充能：贴附的拉杆/模拟拉杆不会经唱片机串到其它面的红石线，顶/侧互不干扰
    （有盘时恢复原版导体行为）。
- 声音按声学传播路径衰减，96 格可听范围外静音（无仿真回退路径保持原版 64 格）；远处实例继续推进，走回不重新起播。
- 唱片机可放在 Sable 结构上：距离按结构当前位置算，声源随结构移动/旋转。
- 声音分类：唱片机/音符盒（RECORDS），跟随该音量滑块；该滑块或主音量为 0 → 电台静音、不压背景音乐，调回后按进度续播。
- 电台可闻时，正在播放的原版背景音乐用约 1 秒淡出后停止；淡出中电台停止则平滑恢复。
  电台缺文件、尚未起播或本端已播完时不压背景音乐，不修改玩家音量设置。
  压制还要求本端音频通道确实在播：顶部 0/15、侧面 0、音量滑块 0、解码失败、设备通道未启动/
  已停止、距离达到可闻边界（声学路径 96 格，原版回退 64 格）时均放行；未入选声学处理的电台也不压背景音乐。
  距离按声音监听器位置算；侧面 15 仍是有效的 150% 音量。
- 曲名显示：元数据标题/作者优先（MP3 的 ID3v2 TIT2/TPE1·ID3v1、OGG/FLAC 的 TITLE=/ARTIST=、
  WAV 的 LIST/INFO INAM/IART 或内嵌 ID3），无标签回退文件名（去扩展名）。两处同口径：
  - 工程师护目镜（Create，可选依赖）：戴上看唱片机（地面/结构通用，Sable 拾取本就是 plot 坐标），
    灰 label + 缩进 value（Create 惯例）：台号音量行、曲名、作者（无则省略）、48 段细进度条 + 时刻；
    缺文件 RED 警示 + 短 hash，不刷聊天栏；潜行加 hash；空盘无电台/有盘均不弹窗；缺 Create 照常跑。
    进度与本端播放共用同一时钟和解码时长；尚未准备好时不显示进度，曲终显示「播放完毕，等待下一曲」。
  - 切曲 actionbar：新曲起播时 `[电台 N台] 曲名`；同曲重建（静音恢复、走出走回）不重复弹。
- 有盘时电台永不启动；播中被塞盘 → 停播让位原版。
- 信号噪声（按电台所在处的群系/维度判定；旋转维度各条带按其借用的群系，Sable 结构按投影后的世界位置）：
  - 主世界 / 地下：清晰。
  - 下界与模组区域（deeperdarker、aether、starlight、twilight 及其它非原版维度/群系）：叠加微弱静电噪声
    （350 Hz~4.5 kHz 带限嘶声 + 偶发噼啪 + 缓慢信号衰落：衰落时曲目略降、噪声略升）。
  - 末地：曲目清晰，每隔约 25~70 秒（首次 10~30 秒后）混入一声微弱的末影人环境音（取当前资源包的
    `entity.enderman.ambient` 变体，同样带限并校准响度，播放时曲目短暂降低约 3.7 dB，像从电波里串进来的）。
  - 虚空（Voidscape 维度或其群系）：曲目消失，只剩静电噪声。
  - 噪声混在单声道 PCM 里、在声学处理之前，所以同样从电台位置发出、被遮挡、带回声，并跟随电台音量；
    换区域时约 0.35 秒平滑过渡，回到清晰后逐位还原原始 PCM。曲间 5 秒间隔不出声。
- `/dbx tune`（dimblend-experience 面板，需权限 2）可调两项，存于 `serverconfig/dimblend_radio-server.toml`：
  - 电台信号噪声 `receptionNoise`（默认开）：关闭后所有电台都清晰播放。
  - 电台声学模拟强度 `acousticIntensity`（默认 1.00，0.00~2.00）：缩放模拟回声（反射与混响）；
    0 只留直达声，遮挡与 3D 方位仍生效。
  SERVER 配置：进服时 NeoForge 下发给客户端；面板改值后经 experience 快照即时同步到各客户端（只改内存）。
  未装 experience 时可直接改配置文件。

## 多人

- 服务端只发 `trackHash + startTick + station + side + pos`，音频字节不走网络。
- 各客户端必须在自己的 `dimblend_radio/` 下放**同名文件内容一致**的曲库（hash 对不上 → 该端跳过不播，
  戴镜看该唱片机时护目镜 RED 提示缺文件，不影响他人）。
- 迟加入的玩家从曲中 offset 起播；走近已在本端播放的电台时直接听到当前进度。
- 唱片机被拆/被炸/被挪走（含 Sable 结构组装、解体）→ 服务端看门狗 1 秒内删状态并广播停播。
  客户端只保留当前维度的电台状态（别的维度收不到删除），退出即清空；进服/换维度/重生时服务端立即补推该维度全量。
- 在场听到开播的玩家从头播：首次解码耗时（冷解码 1~3 秒）不再吞曲首，本端允许落后服务钟至多 4 秒
  （曲间 5 秒间隔吸收，不截曲尾）；同一曲重建（静音恢复、音频设备切换）沿用本端单调时钟，
  不再按可能落后的 gameTime 回退。单人暂停同时冻结本端进度。已播完的同一轮不再起播，等服务端换曲。

## 技术

### Cave reverberation

- Steam Audio 4.8.1 simulates direct occlusion, three-band wall transmission and
  absorption, and reflected sound paths. Its simulated impulse response is rendered
  with native convolution; there is no enclosure score or preset EFX reverb.
- Emitted PCM passes through one propagation delay that feeds both the direct path and
  the reflection convolution: Steam Audio times reflection responses from the direct
  arrival, so reflections follow the direct sound at their simulated offsets instead of
  preceding it at range. The delay therefore only places sound in absolute time, and its
  rate of change is the Doppler shift. It follows a scaled copy of distance / 343 m/s
  (`-Ddimblend.radio.acoustic.doppler`, default 0.25; 0 disables Doppler, 1 is physical)
  through a critically damped follower with fractional interpolation, so pitch glides in
  and out over about 0.3 s instead of stepping with camera frames, jumps and strafing.
  Current listener occlusion and distance gain apply after that delay,
  so a newly blocked path does not keep playing old unobstructed PCM. Spectral coloration
  stays in the native equalizer; attenuation uses a 5 ms ramp instead of the SDK's
  exponential gain smoothing. The occlusion/transmission shading itself is smoothed per band
  in decibels with a 60 ms time constant, so an obstacle passing the line fades the sound
  instead of stepping it from one audio block to the next. Direct sound and reflection fields use headphone HRTF spatialization, including reflected arrival
  directions, so distant sound is not given the same dry/wet distance curve.
- Direct occlusion/transmission uses precise immutable terrain and Sable collision
  rays on a CPU worker. Transmission is traced by the mod (`AcousticDirectTransmission`), not
  Steam Audio's solver, which alternates casts from either end and stops after eight hits: with
  several pillars on the line, which hits it counted hinged on exact geometry and the level
  jumped by whole pillars. Each ray multiplies the transmission of every solid run it crosses,
  once, carrying the path through the run (up to eight blocks), and twelve rays in a
  block-wide bundle (narrowing to a point at each end, so none starts inside a wall beside the
  listener) are averaged per band, so clipping a corner changes the level gradually.
  Transmission depends on the materials along that path and its length: solid walls
  follow the mass law (−6 dB per doubling), foliage half that. A run of mixed blocks
  (carpet on planks on stone) adds each block like a mass, so a wool lining costs a
  stone wall little in the lows but as much as more stone in the highs. Two walls multiply.
- Block materials: a hardcoded table (`AcousticBlockMaterials` → `AcousticMaterials`) gives
  nine materials with their own three-band absorption and transmission: wool (wool, carpets,
  beds, hay, moss, sponge), foliage, soil (dirt, grass, sand, gravel, mud, soul soil),
  wood (all wood families including cherry, bamboo and nether), stone (every rock, ore,
  brick, concrete and unknown block), glass, metal, ice and snow. Tags are checked first
  (`#wool`, `#leaves`, `#ice`, `#c:glass_blocks`, `#logs`, …), then the block's sound type.
  A modded block that both leave as stone (default or custom sound type) is classified by
  its registry name (`AcousticBlockNames`): material words decide and the last one wins
  (`iron_framed_glass` is glass, `deepslate_tin_ore` stone, `locometal` metal), form words
  (`bricks`, `casing`, `sofa`, `table`) only without one. Vanilla blocks never go by name. Common terrain stays in a few materials on purpose:
  the reflection mesh merges only faces of one material. Both geometry paths — the direct
  path's voxel tracer and the reflection voxel mesher — read the same table.
- Approximate diffraction: direct occlusion is Steam Audio's volumetric mode rather than
  one ray. 16 fixed points in a 2-block sphere around the radio are tested against the
  listener (points the radio cannot see are dropped); when that sphere is partly hidden, a second
  sphere around the listener is tested against the radio and the larger visible share wins (an
  obstacle beside the listener hides nearly all of the radio's sphere, whose rays converge there).
  The heard level per band is `open + (1 − open) × transmission`, with the visible share shaped
  per band (low^1, mid^1.5, high^2). Past the shadow edge the bundle first grazes the
  wall's corner, short paths that pass nearly everything, so walking behind a corner or past
  a doorway near either end fades the dry sound over a few blocks instead of cutting it, the
  highs first. Before the edge, part of the sphere hidden by an obstacle beside the line dulls
  the highs (about −3 dB at the edge for stone) while lows and mids stay near full level: the
  hidden share passes no more than a grazing chord of stone, which also keeps the clear and
  blocked sides continuous. Native tests walk past a wall edge in eighth-block steps (every band
  falls, no step over 3 dB) and through a field of one-block pillars at walking pace (under
  4 dB from one audio block to the next; about 15 dB before the traced transmission and
  smoothing). The sound keeps its true direction, and openings more
  than the radius from both ends do not help. `-Ddimblend.radio.acoustic.diffraction=<radius,
  0–8, default 2>` (0 restores the single ray) and
  `-Ddimblend.radio.acoustic.diffraction.samples=<2–32, default 16>` tune it; a direct update,
  transmission bundle included, costs about 0.42–0.46 ms with 16 samples against 0.2 ms for one
  ray (native benchmark in `SteamDirectDiffractionTest`), and runs only while something moves.
- Reflections use Steam Audio's OpenCL/Radeon Rays GPU backend
  with the same per-triangle materials and float coordinates relative to a nearby origin.
  Terrain geometry is merged one-metre voxel surfaces built from the captured block
  palettes, independent of the renderer (vanilla or Sodium): every block with a collision
  shape is a full cell, so faces are whole-block rectangles and never degenerate.
  Sable vertices are transformed in double precision
  before conversion, preserving positioning even at distant plot coordinates.
- GPU devices and triangle scenes live as long as the radio session; scene meshes are
  re-uploaded in place when geometry changes, and convolution crossfades new responses.
  GPU rays come in complete 256-ray workgroups (1024 rays): Steam Audio
  4.8.1's histogram kernel reads at least 256 rays even when fewer are requested.
  A failed GPU session disables its acoustic simulation, including its direct-ray
  worker and effects; that radio continues with equal-power stereo panning. There is
  no CPU reflection fallback. If the first GPU engine of the game session cannot be
  created, radios bound afterwards play as vanilla positional sound.
  `-Ddimblend.radio.acoustic.gpu=false` disables acoustics.
- Up to four nearby radios simulate acoustics; a simulated radio keeps its slot until
  another is four blocks closer. Other radios in range are stereo-panned with the same
  distance curve rather than muted, and native engines are only created for radios
  that are simulated. Changes between simulated, panned and silent playback crossfade
  over one block; panning keeps the propagation delay running, so switching neither
  skips nor repeats audio, and audio from before a silent gap is never replayed.
  A radio that is switched off, loses its signal or changes track does not cut its channel:
  the input fades out over 10 ms and the channel plays out the propagation delay and the
  reverb tail (at most seven seconds, alongside the next track if one starts), keeping the
  simulated or panned path it had without taking a slot from a playing radio. Muting, a
  dimension change and leaving the world still stop at once.
  Reflection simulation uses 1024 GPU rays,
  up to 128 bounces, first-order Ambisonics, and a six-second IR limit. Direct results
  update on camera frames (at most 125 times/second); reflections update up to twenty
  times/second. Camera poses are published after mouse processing and Camera.setup,
  rather than vanilla's earlier sound-listener update from the preceding frame;
  worker jobs do not overlap for the same radio and sample the latest view when they start.
  Head turns spatialize every audio block independently of ray simulation. Block edits
  in observed sections (including air-only sections) wake a snapshot capture on the next
  camera frame; the half-second refresh remains a fallback for coverage/chunk changes.
  Snapshots are shared by nearby radios. Immutable palette copies and
  fingerprints are reused until a block write or chunk packet changes the palette;
  world resets clear the cache. Sable poses refresh separately. Greedy surface extraction
  uses linear strides without allocating coordinate arrays per voxel. The reflection mesh is
  reused while geometry is unchanged and movement stays within its sixteen-block padding;
  slow Sable motion is compared against the pose the mesh was built from, so it cannot
  drift away in sub-tolerance steps. GPU terrain retains a 32-block minimum margin around
  source/listener bounds; nearby captured Sable structures are included. Snapshots cover
  the union of all simulated radios' mesh regions plus 16 blocks, and are recaptured
  when movement leaves that coverage.
- Geometry changes bypass the motion cadence, and queued simulations take the newest
  snapshot when their worker actually starts.
- Equivalent geometry snapshots and stationary source/listener positions reuse the
  same response. Block/chunk changes, meaningful movement, or Sable pose changes
  invalidate it. CPU materials are specular, so CPU responses stay repeatable. GPU
  materials add 5% diffuse scattering: with axis-aligned voxel walls a purely specular
  lobe gathers too few paths for the field to converge, so successive GPU responses
  differ slightly (convolution crossfades between them).
- A non-finite reflection field withdraws the old output and forces a fresh simulation,
  even while stationary. Three invalid GPU fields disable the acoustic session and
  settle on stereo-panned playback. Failures are logged per session.
- Mix peaks are limited per sample (linear attack across the block, ~1 s release) with a
  soft knee above 0.95 of full scale. Teleport-sized propagation delay changes crossfade
  between the old and new delay instead of sweeping the read head. The processed PCM is
  already binaural, so OpenAL Soft's `AL_SOFT_direct_channels` keeps its HRTF from
  filtering it a second time. An invalid `-Ddimblend.radio.acoustic.wetgain` or
  `-Ddimblend.radio.acoustic.doppler` value is logged once and the default is used.
- Processed PCM uses 512-frame blocks and starts with a short spatialized queue (about
  32–35 ms at 44.1/48 kHz). An actual OpenAL underrun adds two buffers before restarting,
  bounded to roughly 90–100 ms. Every queued buffer delays head-turn spatialization, so
  after three seconds of played audio in which the queue never fell below two buffers,
  one buffer is given back (never below the short start); each underrun of a playback
  doubles that window (up to 16 times), so periodic hitches settle instead of cycling
  between starving and shrinking, and a paused source never counts as stable.
  The next playback starts with the learned headroom instead of starving again to relearn
  it; each new playback gives one buffer of it back.
  OpenAL Soft adds its own output buffer on top (3 × 20 ms updates by default; Minecraft's
  context attributes cannot shorten it, `ALC_REFRESH` is ignored). A user-level
  `alsoft.ini` with `period_size = 480` lowers it to about 30 ms for all game audio.
  Starvation counts are logged even when debug logging is off. Renderer/HRTF preparation and native
  teardown run on the reflection worker. Replacement withdraws the old renderer and
  source-owned IR under the PCM lock before releasing them; initialization does not
  hold locks used by other radios' audio processing.
  Radio refills run on the sound thread every 4 ms independently of render frames;
  each channel permits only one pending refill and unregisters on destruction.
  Original PCM caches, source volume/category controls, and track clocks are retained.
- The bundled native SDK currently supports Windows x64. Other platforms keep
  vanilla mono playback. Sound Physics Remastered or `-Ddimblend.radio.reverb=false`
  disables the local simulation backend.
- Limits: finite loaded geometry, block shapes approximated as whole voxels (slabs,
  stairs, fences and panes reflect as full cells), water surfaces of waterlogged blocks counted as reflectors,
  residual reflection noise, snapshot/update latency, approximate block
  materials, and diffraction approximated by volumetric occlusion (no path search around
  obstacles, no change of apparent direction). Musical listening and performance
  profiling remain necessary beyond the tested room fixtures.
- Geometry and audio regression tests run with `gradlew :dimblend-radio:test`.
  Native audio tests are opt-in:
  `gradlew :dimblend-radio:test -PradioAudioNatives=<LWJGL/OpenAL native directory>`.
  `gradlew :dimblend-radio:runGameTestServer` includes terrain and real rotated Sable
  room checks. Native impulse tests write open-air and room WAV files under `build/`.
  The original, superseded EFX research is in `docs/cave-reverberation-research.md`.

### Playback

- 服务端：`JukeboxRedstoneInputMixin`（neighborChanged 即时重算 + 空盘不中继强充能）、
  `JukeboxControlMixin`（useItemOn 塞盘让位）、`RadioSync` 事件（放置/唱片机自身状态变化）
  → `RadioState` 真值表 → `RadioStatePayload` S2C（维度内广播）→ 100 tick 补推 + 曲终推进。
- 曲长（服务端排曲终用，客户端 hello 上报）：MP3 逐帧累加帧时长（不把 ID3 内嵌封面算进去），
  OGG 末页 granule、FLAC STREAMINFO、WAV data 块，均为精确值。
- 运行时验证：`RadioSignalInputGameTests`（`gradlew :dimblend-radio:runGameTestServer`）。
- 客户端：`RadioLibrary`（JOrbis/JLayer/jFLAC 解码 → 16bit PCM → 下混单声道）→
  `PcmHeadroom`（按本曲峰值线性预放大到 ≤-0.3dBFS、最多 150%；各档音量 = 通道增益
  min(1, 音量%/100/倍率)，变音量含跨 100% 都不重建、不断音）→ `RadioPcmFeed` → `SoundBufferMixin` 分流（跳过 JOrbis）
  → `RadioInstance`（Tickable，resolve 旁路 registry，免 reload）。
- 每次起播分配独立 feed ID 和 PCM 游标，同文件不同起点的电台互不覆盖；缓存 PCM 只读。
  通道退出且 PCM 已读尽时记录完成；读尽但通道还在播放时不提前结束（OpenAL 有预读队列）。
  异步解码请求持续占位到主线程落地，并复核维度、nonce、hash、startTick 和完成状态。
- 回归单测：`RadioPlaybackTest`（低 TPS/重建/曲终/暂停/换曲）、`RadioPcmFeedTest`（同曲多实例/
  EOF/淡入/释放）、`RadioMusicFadeTest`（淡出/中断恢复）。根目录执行 `gradlew :dimblend-radio:test`。
- 可听性回归：`RadioAudibilityRulesTest`（顶部旁路/侧面静音、无播放通道、零音量、64/96 格边界、多电台）。
- Sable 结构：结构上唱片机的 `BlockPos` 是 plot 坐标（原点 20,480,000），不是世界坐标。
  `SubLevelProjection`（sable-companion 软依赖 shim，未装 Sable 时原样返回）统一换算：
  客户端建实例/保活/压背景音乐的距离判定、`RadioInstance` 声源位置（逐 tick 跟结构位姿；
  起播前即为世界坐标，避免 Sable 把实例包成 delegate 导致 `isActive` 查不到而反复重建），
  服务端进服/走近自动发现（玩家位置反投影进附近结构的 plot 再扫）。
- 解码依赖 JiJ 进 jar（`META-INF/jarjar`）：JLayer 1.0.1.4（LGPL-ish）、
  jFLAC 1.5.2（BSD-ish），发布时在更新页注明来源；sable-companion 1.6.0 同样 JiJ。
