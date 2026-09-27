# DimBlend Radio

红石点播电台：空唱片机 + 顶部信号选台 + 侧面信号调音量。同台附近所有人听同一曲（服务端定曲定钟），各客户端本地解码。

## 玩法

- 游戏根目录建 `dimblend_radio/1` ~ `dimblend_radio/14`，每个文件夹丢音频文件（`ogg/mp3/flac/wav`，混放可）。
- 空唱片机（不能有唱片）：
  - 顶部输入 `0/15` → 不接管，原版行为。
  - 顶部输入 `1~14` → 播对应文件夹，文件夹内随机轮播，曲间 5 秒。
  - 侧面（水平四面 max，底部忽略）`0` → 停；`1~15` → 音量 `10%~150%`。
- 范围固定 32 格，随距离衰减（RECORDS 分类）。
- 有盘时电台永不启动；播中被塞盘 → 停播让位原版。

## 多人

- 服务端只发 `trackHash + startTick + station + side + pos`，音频字节不走网络。
- 各客户端必须在自己的 `dimblend_radio/` 下放**同名文件内容一致**的曲库（hash 对不上 → 该端跳过+提示，不影响他人）。
- 迟加入/走近的玩家从曲中 offset 起播（顺序解码跳过对齐）。

## 技术

- 服务端：`JukeboxControlMixin`（neighborChanged/onPlace/useItemOn）→ `RadioState` 真值表 →
  `RadioStatePayload` S2C（维度内广播）→ 100 tick 补推 + 曲终推进。
- 客户端：`RadioLibrary`（JOrbis/JLayer/jFLAC 解码 → 16bit PCM → 下混立体声 →
  `side>100%` 预放大）→ `RadioPcmFeed` → `SoundBufferMixin` 分流（跳过 JOrbis）
  → `RadioInstance`（Tickable，resolve 旁路 registry，免 reload）。
- 解码依赖 JiJ 进 jar（`META-INF/jarjar`）：JLayer 1.0.1.4（LGPL-ish）、
  jFLAC 1.5.2（BSD-ish），发布时在更新页注明来源。
