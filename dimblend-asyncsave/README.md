# DimBlend AsyncSave

把周期自动存档里仍在服务器线程上同步落盘的两类小文件写挪到后台写线程，消除磁盘繁忙
（fsync/SYNC 写）时的整卡：

- `level.dat`（`LevelStorageAccess.saveDataTag`，含 NeoForge 的 `fml.LoadingModList` 段）
- 玩家 `<uuid>.dat`（`PlayerDataStorage.save`，提交后照常 fire NeoForge `PlayerEvent.SaveToFile`）

区块与实体写盘原版已是异步；SavedData 在 NeoForge 基线里已是主线程快照 + `Util.ioPool()`
原子写（临时文件 + move）——两者本 mod 都不接管。

## 工作方式

- 序列化（`WorldData.createTag` / `Player.saveWithoutId`）留在服务器线程上生成 NBT
  快照——它们读活的游戏状态，不能离开主线程。
- 压缩 + 写临时文件 + `safeReplaceFile` 挪到单线程后台 writer（level.dat 直接复用原版
  私有 `saveLevelData`，落盘语义与原版一致）；同一目标文件在队列里只保留最新快照。
- 手动 `/save-all`、`/save-all flush`、关服保存保持原版全同步语义：进入时先 `drain()`
  异步队列再原版同步执行；玩家进服读档前也会 drain，避免读到断线时尚未落盘的旧数据。

## 已知取舍

- 从自动存档快照生成到后台真正落盘之间若硬崩溃（kill -9 / 断电），会丢这一次自动存档——
  与原版区块异步保存的暴露窗口同类；正常关服不受影响（关服路径强制 drain + 同步）。
- 玩家统计（stats）与成就（advancements）的 JSON 写仍是原版同步路径，量小未纳入。
