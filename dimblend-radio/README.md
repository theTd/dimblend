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
- 范围固定 32 格，随距离衰减。
- 唱片机可放在 Sable 结构上：距离按结构当前位置算，声源随结构移动/旋转。
- 声音分类：唱片机/音符盒（RECORDS），跟随该音量滑块；该滑块或主音量为 0 → 电台静音、不压背景音乐，调回后按进度续播。
- 有盘时电台永不启动；播中被塞盘 → 停播让位原版。

## 多人

- 服务端只发 `trackHash + startTick + station + side + pos`，音频字节不走网络。
- 各客户端必须在自己的 `dimblend_radio/` 下放**同名文件内容一致**的曲库（hash 对不上 → 该端跳过+提示，不影响他人）。
- 迟加入/走近的玩家从曲中 offset 起播（顺序解码跳过对齐）。
- 唱片机被拆/被炸/被挪走（含 Sable 结构组装、解体）→ 服务端看门狗 1 秒内删状态并广播停播。
  客户端只保留当前维度的电台状态（别的维度收不到删除），退出即清空；进服/换维度/重生时服务端立即补推该维度全量。
- 在场听到开播的玩家从头播：首次解码耗时（冷解码 1~3 秒）不再吞曲首，本端允许落后服务钟至多 4 秒
  （曲间 5 秒间隔吸收，不截曲尾）；同一曲重建（静音恢复、走出走回、音频设备切换）沿用落后量接着播。

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
- Sable 结构：结构上唱片机的 `BlockPos` 是 plot 坐标（原点 20,480,000），不是世界坐标。
  `SubLevelProjection`（sable-companion 软依赖 shim，未装 Sable 时原样返回）统一换算：
  客户端建实例/保活/压背景音乐的距离判定、`RadioInstance` 声源位置（逐 tick 跟结构位姿；
  起播前即为世界坐标，避免 Sable 把实例包成 delegate 导致 `isActive` 查不到而反复重建），
  服务端进服/走近自动发现（玩家位置反投影进附近结构的 plot 再扫）。
- 解码依赖 JiJ 进 jar（`META-INF/jarjar`）：JLayer 1.0.1.4（LGPL-ish）、
  jFLAC 1.5.2（BSD-ish），发布时在更新页注明来源；sable-companion 1.6.0 同样 JiJ。
