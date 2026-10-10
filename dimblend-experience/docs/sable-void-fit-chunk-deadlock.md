# SableVoidFit 区块加载死锁（2026-10-10 事故记录）

> 进世界约 15 秒后集成服务端线程永久挂起，客户端卡在"加载世界中"。根因：
> `ChunkEvent.Load` 里同步 `setBlock` 被 Sable 的物理钩子拽去**同步请求相邻区块**，
> 在区块任务邮箱的执行上下文里形成嵌套 `managedBlock`，永远等不到完成。

## 一、现象与时间线

实例：TrainTripWorld（1.21.1 + NeoForge 21.1.249），dimblend-experience 1.0.0 + Sable 2.0.5。

- 23:52:37 第二次进世界，`Starting integrated minecraft server`；
- 23:52:50 `Preparing start region`，出生区 689ms 生成完毕；
- **23:53:05 起 spark 每分钟报 `Timed out waiting for world statistics`**——服务端线程
  在进世界后约 15 秒内就已无响应，之后 20 多分钟再未醒来；
- 客户端渲染线程停在 `Minecraft.doWorldLoad` 的 sleep 循环（等服务端就绪），
  表现为加载界面永远转圈。

## 二、证据

两份 jstack 线程 dump（间隔 55 秒），服务端线程栈**逐字节相同**、CPU 只前进 0.5s
——真死锁，不是慢加载：

- `build/shutdown-hang/threaddump-001206.txt`
- `build/shutdown-hang/threaddump-b.txt`

## 三、死锁链（服务端线程，自底向上）

```
ServerLevel.tick
└─ sable: SubLevelContainer.tick → SubLevelPhysicsSystem.tick
   └─ PhysicsChunkTicketManager.update → inhabitChunk
      └─ ServerChunkCache.getChunk                       ← 外层同步等区块
         └─ MainThreadExecutor.managedBlock              ← 泵区块任务邮箱
            └─ ChunkStatusTasks.lambda$full$2            ← 某区块到 FULL
               │  （经 dimblend 的 releaseMailboxAfterTask 包装）
               └─ 发 ChunkEvent.Load
                  └─ SableVoidFit.onChunkLoad            (SableVoidFit.java:154)
                     └─ VoidFitApplier.placeInChunk → placeVoid
                        └─ Level.setBlock(pos, STRUCTURE_VOID)   (VoidFitApplier.java:206)
                           └─ sable wrapOperation @ LevelChunk.setBlockState (4802)
                              └─ SableCommonEvents.handleBlockChange
                                 └─ RapierPhysicsPipeline.handleBlockChange
                                    └─ VoxelNeighborhoodState.getState
                                       └─ LevelAccelerator.getBlockState → getChunk
                                          └─ ServerChunkCache.getChunk     ← 嵌套同步等邻块
                                             └─ managedBlock → waitForTasks → 永久 parked
```

要点：

1. **外层等待**是 Sable 自己的物理票据系统（`PhysicsChunkTicketManager.inhabitChunk`）
   在 `ServerLevel.tick` 里同步等区块，此时服务端线程已经在 `managedBlock` 泵邮箱；
2. 泵到的 FULL 转换任务发出 `ChunkEvent.Load`，VoidFit 的补放路径在**该任务的执行
   上下文里**调 `Level.setBlock`；
3. Sable 在 `LevelChunk.setBlockState` 上的 wrapOperation 拦截所有方块变更，其物理
   管线读邻域方块时用 `Level.getChunk`（`LevelAccelerator.grabChunkFast`）**同步拉取
   相邻区块**，不管它是否已加载；
4. 嵌套的 `getChunk` → `managedBlock` 再也等不到 future 完成：邮箱任务正被自己占着
   （外层 FULL 任务要 `tell(Unit)` 归还邮箱必须等它返回），且此刻 JVM 里**没有任何
   Worker-Main / 区块后台执行器线程**（dump 里一个都不存在），后台生成侧无人干活，
   future 永远 pending。两者叠加 = 永久死锁。

## 四、触发条件（缺一不可）

- Sable 在场且载具存在于 rotating 维度（`PhysicsChunkTicketManager` 周期性 inhabit）；
- VoidFit tracker 里有载具的 target 格落到**正在加载**的区块（`onChunkLoad` 补放路径
  被命中——`tracker.targetCellsOfChunk` 非空）；
- 补放 `setBlock` 触发 Sable `handleBlockChange`，且其邻域读取落到**未加载**的邻块。

`VoidFitApplier.placeVoid` 自带的 `level.hasChunkAt(pos)` 只保护**目标格**所在区块，
管不到 Sable 邻域读取触碰的相邻区块——这是防线的缺口。

## 五、修复方向

首选：**`onChunkLoad` 不再在事件里直接 `placeInChunk`**，改为把待补放格入队，
在 `onLevelTick` 扫描周期里按既有预算纪律（`VEHICLE_BUDGET_PER_SCAN` /
`CELL_BUDGET`）消化。离开区块任务邮箱执行上下文后，Sable 的邻块读取最多是同步
加载变慢，不会再形成邮箱内嵌套等锁。

注意事项：

- 补放走 tick 后仍需保持"target 记账先行、materialized 差分续跑"的不变式
  （预算耗尽时 target 已更新、下轮自动补缺口），只需把 `placeInChunk` 的调用点
  从事件搬到 tick 队列；
- `refit`/`removeAll` 本就在 `onLevelTick` 里跑，不受影响；
- `VoidFitStrip` 的保存剥离直写 `LevelChunkSection.setBlockState`、绕开了
  `LevelChunk` 包装，不经过 Sable 的 wrapOperation，不受影响；
- dimblend 核心的 `ChunkTaskPriorityQueueSorterMixin.releaseMailboxAfterTask` 只是
  保证任务抛异常也 `tell(Unit)`，与本死锁无因果关系（栈里那帧是正常包装）。

## 六、遗留疑点

dump 中整个 JVM 没有任何 `Worker-Main-N` / `IO-Worker-N` 线程（这是第二次进世界，
第一次进世界 23:34 正常游玩了约 18 分钟）。区块后台执行器线程为何全部消失、与死锁
谁先谁后，未定论；修复第五节后若进世界仍异常卡顿，需要回头查这个。

## 七、修复落实（2026-10-11）

已按第五节首选方向实施：

- `SableVoidFit.onChunkLoad` 不再调用 `placeInChunk`，只把含 target 格的 ChunkPos
  登记进按维度的 FIFO 队列（`PENDING_CHUNKS`）；
- `onLevelTick` 扫描周期末尾按预算消化：每轮最多 `CHUNK_BUDGET_PER_SCAN=2` 个区块、
  每区块沿用 `VoidFitApplier.CELL_BUDGET` 格预算（`placeInChunk` 改为带预算、
  返回是否放完）；放不完的区块回队尾下轮续放，target 已迁走的条目直接丢弃；
- 「target 记账先行、materialized 差分续跑」不变式不受影响：登记时机不变
  （target 早已在账），补放只是 materialized 的延迟补齐；
- `refit`/`removeAll`/`VoidFitStrip` 路径本就安全，未改动。

待实机回归：TrainTripWorld 二次进世界 15 秒挂起场景应不再复现；同时留意第六节
的 Worker 线程消失疑点是否仍有症状。
