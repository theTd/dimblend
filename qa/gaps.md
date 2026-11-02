# QA 缺口（望远镜标记迭代）

本文件只记录**无法自动验证**的项。补上或确认后删掉对应行；不要把它当成待办列表的重复。

- `StructureMarkLayer` 的绘制：`?`/`!` 的位置、镜框裁切、重叠跳过——纯 GUI 渲染，没有无头测试。
- `CrosshairDistanceLayer` 的顶部文字：准星命中只在游戏内成立（`CrosshairPicking` 有单测，图层消费没有）。
- `EdgeArrowLayer` 的箭头外观与方向：几何有单测，实际绘制、旋转与黑边位置只有手测。
- `SpyglassSourceScreen` 的滚动、复选框、状态文案：`compileOnly` 不进 test classpath（契约 §10），只有手测。
- 只装 `dimblend-experience`、不装 `dimblend-spyglass` 时 `望远镜 ▸` 按钮不出现：需要在没有 spyglass 的实例里手测。
- 手改 `dimblend_spyglass-server.toml` 写入非法 namespace 的加载期行为：未实测（设计是加载期拒绝）。
- 协议 6 两端不一致时 NeoForge 拒绝连接：需要只换一端 jar 手测。
- 128 格内沿上线后，128 到雾起点之间已加载建筑的叠画效果：需要实机看。
- 水下/岩浆/细雪/失明/黑暗时标记、距离、箭头整体关闭：需要实机看。
