# 旋转维度区块清除（region purge）

> 玩家基本只沿 X 单向行驶（持续朝 x+ 或 x-），身后的区块不会再用到，却一直占着磁盘和
> 内存。本功能在服务器运行时把远离所有玩家的区域文件在线删除，并同步清掉 vanilla 为这些
> 区块留下的内存备忘。实现：`dimblend.purge` 包。

## 一、目标与取舍

- [x] **在线删除**：服务器运行中直接释放磁盘，不需要重启（`RegionPurgeController`）。
- [x] **以 mca 为单位**：一次删除一个 `r.X.Z.mca`（32×32 区块），同时覆盖 `region/`、
      `entities/`、`poi/` 三个存储，以及该区域的超大区块文件 `c.X.Z.mcc`。
      （单区块清头只把扇区标空、文件不缩小，单向行驶后不会被复用，所以不做单区块删除。）
- [x] **不保护玩家建筑**（用户拍板）：被删区域再次到达时按种子重新生成，
      玩家放置/破坏的方块、掉落物、矿车、生物、村民（含 experience 的职业/新鲜附件）一并消失。
- [x] **内存同批清理**（用户拍板）：删除时一起清掉该区域的内存备忘（见第四节）。

## 二、判定规则

每 `scanIntervalSeconds` 扫描一次 `dimblend:rotating` 三个存储目录下的 `r.X.Z.mca`，
一个区域必须**连续** `idleSeconds` 同时满足以下条件才会被删：

1. **保留窗口之外**：与每个保留中心沿 X 的区块距离都大于有效保留距离。保留窗口只看 X
   （世界是沿 X 的条带），与 Z 无关。
   - 保留中心 = 每个进过本维度的玩家的**最后已知区块 X**（`RegionPurgeAnchors`，随维度存档
     `data/dimblend_purge_anchors.dat`，重启不丢）：在维度内时每次扫描刷新为实时位置；
     登出、穿门/传送离开本维度时在离开瞬间记下（`PlayerLoggedOutEvent` /
     `EntityTravelToDimensionEvent`）。因此去下界/暮色等维度的玩家、离线玩家，
     其离开点附近都受保护。条目永不删除，从不回来的玩家只占一个保留窗口的磁盘。
   - 有效保留距离 = max(`keepChunks`, pregen max(xBehind, xAhead) + 8, 视距 + 8)，
     保证清除永远不和 pregen / 玩家加载抢同一块区域。
   - **没有任何保留中心时不删**（新世界尚无人进入过本维度）：没有旅行者就没有"身后"。
2. **内存中没有任何状态**：区域内任一区块存在 ChunkHolder（`updatingChunkMap`）、
   待卸载（`pendingUnloads`）、任何 ticket（强加载、Sable、Create 及其它模组的票据全部涵盖）、
   实体加载状态（`chunkLoadStatuses`）、待卸载实体（`chunksToUnload`）或实体区段
   （实体可在未加载区块建区段，autosave 会回写）都视为忙。
3. **不在 Sable plot grid 内**：Sable 子世界方块所在的远端网格不是地形，永不删除。
   `sublevels/*.slvlr` 与 Sable 数据文件本身从不触碰。
4. **存档未关闭**：`/save-off` 期间暂停清除，保证备份看到一致的世界。

满足条件的区域按"离最近保留中心最远"优先，每次扫描最多删 `maxRegionsPerScan` 个。
仅有 8 KiB 头（或 0 字节）的空文件不含任何区块，另行清扫（每次至多 64 个，不占上述名额、
不计入删除统计，只打 debug 日志）。删除失败（如杀毒/备份软件锁文件）的区域 5 分钟后再试。

## 三、在线删除流程（为何不会写坏存档）

`RegionFileStorage` 最多缓存 256 个打开的区域文件，且每次写入都把整张内存头写回；
Windows 以 share-delete 打开，文件被外部删掉后缓存句柄仍会继续写，删除会"成功但无效"。
因此删除必须在该存储自己的 IO worker 邮箱里执行（`RegionFileDeleter`）：

1. 服务器线程在同一 tick 内：复核第二节条件 → 清内存备忘 → 向三个 IO worker 各提交一个
   FOREGROUND 任务。之后任何对该区域的加载都排在删除任务之后，只会看到"无文件"并重新生成。
2. IO 任务内：若该区域仍有排队写入（`pendingWrites`）则本次放弃（`DEFERRED`），下次扫描重试，
   不和 BACKGROUND 刷盘赛跑；否则从 `regionCache` 移除并关闭句柄 → 删除 `r.X.Z.mca`
   → 删除落在该区域的 `c.X.Z.mcc`。
3. 结果回到服务器线程统计并打日志 `dimblend purge: deleted r.X.Z (...)`。

已知接受风险：删除后若有只读访问（`/locate`、探险家地图、海豚、末影之眼扫描结构）触及该区域，
vanilla 会以 CREATE 方式重新打开，留下 0 字节空文件；由上面的空文件清扫收走。

## 四、内存备忘清理（`RegionMemoryPruner`）

| 备忘 | 不清的后果 |
|---|---|
| POI 区段缓存与 dirty 集 | dirty 残留会在删除后把 POI 列写回；缓存会继续提供已删 POI（NeoForge 卸载时会清区段，但之后的 POI 查询会重新载入） |
| POI 村庄距离追踪 | NeoForge 卸载清区段时绕过追踪器，已删村庄中心的等级残留，重新生成处 `isVillage` 仍为真（袭击、村民寻路）；故对区域内每个区段无条件重算 |
| `PoiManager.loadedChunks` | 传送门搜索（`ensureLoadedAndValid`）跳过重新生成区块的方块重扫 |
| `StructureCheck.loadedChunks` / `featureChecks` | 生成是确定性的，仍正确，仅为止住无限增长 |
| `PregenController.done` | 回头时 pregen 以为已完成而不再驱动；同时止住增长 |

不清的：`ChunkMap.chunkTypeCache`（NeoForge 卸载时已清）；`EntityStorage.emptyChunks`
（实体反序列化线程并发写入，批量删除可能损坏集合，收益仅每区块数字节，删除后内容仍正确）。

## 五、配置与命令

`serverconfig/dimblend-purge-server.toml`（与 pregen 的 `dimblend-server.toml` 分开）：

| 键 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `keepChunks` | `96` | 沿 X 的保留距离（区块），运行时会被抬到有效保留距离 |
| `idleSeconds` | `120` | 连续满足条件的时长 |
| `scanIntervalSeconds` | `10` | 扫描间隔 |
| `maxRegionsPerScan` | `2` | 每次扫描最多删除的区域数 |

- `/dimblend purge`：状态（运行状态、保留距离、磁盘区域数、忙区域数、倒计时中/删除中、保留中心数、
  累计删除/空文件/释放字节/延后/失败）。
- `/dimblend purge now [keepChunkX]`：立即扫描并跳过空闲计时，其余条件照旧（`enabled=false` 时拒绝）；
  `keepChunkX` 仅本次额外加一个保留中心，便于控制台按指定位置清理。
- 默认开启：服务器启动时若开启会打一条 WARN，提示身后区域会被在线删除及关闭方式。

## 六、未覆盖

- Create 轨道图（`create_tracks.dat`）不随区块删除修剪：走廊轨道重新生成后与图中已有边一致，
  `CreateTrackGraphCompat` 会识别为已在图中；玩家自建轨道/车站/信号被删后会在图中残留。
- 布局算法变更后重新生成的区域会按新布局生成，与未删除的旧区域之间出现接缝（同 README 说明）。
- 只增长、但仍正确、且有上限或体量很小的：Sable `sublevels/` 中已删区域的持有记录（停放的子世界
  回来时落在原样重新生成的地形上）、`IOWorker.regionCacheForBlender`（上限 1024）、
  `CreateTrackGraphCompat.pendingStitch`（上限 4096）。
