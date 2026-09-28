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
- 声音随距离衰减，64 格归零（32 格约半音量）；远处实例继续推进，走回不重新起播。
- 唱片机可放在 Sable 结构上：距离按结构当前位置算，声源随结构移动/旋转。
- 声音分类：唱片机/音符盒（RECORDS），跟随该音量滑块；该滑块或主音量为 0 → 电台静音、不压背景音乐，调回后按进度续播。
- 电台可闻时，正在播放的原版背景音乐用约 1 秒淡出后停止；淡出中电台停止则平滑恢复。
  电台缺文件、尚未起播或本端已播完时不压背景音乐，不修改玩家音量设置。
  压制还要求本端音频通道确实在播：顶部 0/15、侧面 0、音量滑块 0、解码失败、设备通道未启动/
  已停止、距离达到或超过 64 格时均放行。距离按声音监听器位置算；侧面 15 仍是有效的 150% 音量。
- 曲名显示：元数据标题/作者优先（MP3 的 ID3v2 TIT2/TPE1·ID3v1、OGG/FLAC 的 TITLE=/ARTIST=、
  WAV 的 LIST/INFO INAM/IART 或内嵌 ID3），无标签回退文件名（去扩展名）。两处同口径：
  - 工程师护目镜（Create，可选依赖）：戴上看唱片机（地面/结构通用，Sable 拾取本就是 plot 坐标），
    灰 label + 缩进 value（Create 惯例）：台号音量行、曲名、作者（无则省略）、48 段细进度条 + 时刻；
    缺文件 RED 警示 + 短 hash，不刷聊天栏；潜行加 hash；空盘无电台/有盘均不弹窗；缺 Create 照常跑。
    进度与本端播放共用同一时钟和解码时长；尚未准备好时不显示进度，曲终显示「播放完毕，等待下一曲」。
  - 切曲 actionbar：新曲起播时 `[电台 N台] 曲名`；同曲重建（静音恢复、走出走回）不重复弹。
- 有盘时电台永不启动；播中被塞盘 → 停播让位原版。

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
- 可听性回归：`RadioAudibilityRulesTest`（顶部旁路/侧面静音、无播放通道、零音量、64 格边界、多电台）。
- Sable 结构：结构上唱片机的 `BlockPos` 是 plot 坐标（原点 20,480,000），不是世界坐标。
  `SubLevelProjection`（sable-companion 软依赖 shim，未装 Sable 时原样返回）统一换算：
  客户端建实例/保活/压背景音乐的距离判定、`RadioInstance` 声源位置（逐 tick 跟结构位姿；
  起播前即为世界坐标，避免 Sable 把实例包成 delegate 导致 `isActive` 查不到而反复重建），
  服务端进服/走近自动发现（玩家位置反投影进附近结构的 plot 再扫）。
- 解码依赖 JiJ 进 jar（`META-INF/jarjar`）：JLayer 1.0.1.4（LGPL-ish）、
  jFLAC 1.5.2（BSD-ish），发布时在更新页注明来源；sable-companion 1.6.0 同样 JiJ。
