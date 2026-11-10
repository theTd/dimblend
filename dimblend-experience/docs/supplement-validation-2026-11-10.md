# 补充需求构建与 GameTest 验证（2026-11-10）

用户本轮已授权编译与开发 GameTest；未部署整合包、未启动客户端、未提交代码。全部执行位于专用 worktree。

最终状态：编译与 758 项 JUnit 通过；完整补充 fixture 37/37、独立死亡正向 12/12、无粉末负向 1/1 通过。独立正向 12 项是不同运行栈下的重复覆盖，不把三轮合计 50 次执行称为 50 个独立用例。

## 执行环境

- JDK：`C:/Program Files/Java/jdk-21`。
- Gradle：wrapper 9.2.1，`GRADLE_USER_HOME=C:/Users/cui/.gradle`。
- 串行限制：`--offline --no-daemon --max-workers=1`，Gradle JVM `-Xmx2G -XX:ActiveProcessorCount=2`。
- 子进程树 `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=2 -XX:ParallelGCThreads=2 -XX:ConcGCThreads=1`。
- 调度前采样（主编排）：CPU 均值 62.6%，可用内存 21027 MiB，E: 剩余 278.9 GiB。
- 普通沙箱 JVM 的 `user.home` 指向 `C:/Users/CodexSandboxOffline`，因此首次找不到既有 distribution。后续精确构建命令经自动审批，显式复用用户 Gradle 缓存；未修改 ACL、全局配置或网络设置。

## 编译与 JUnit

目标命令：

```powershell
.\gradlew.bat :dimblend:test :dimblend-experience:test --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx2G -XX:ActiveProcessorCount=2'
```

| 次数 | 新日志（相对仓库） | 原生退出码 | 结果 |
|---|---|---:|---|
| 1 | `build/diag/supplement-validation-junit-20261110-165126.log` | 1 | Wrapper 试图下载 distribution，沙箱 SocketException；未进入 Gradle 配置/编译。 |
| 2 | `build/diag/supplement-validation-junit-20261110-165320.log` | 1 | 进入编译，`:dimblend:compileJava` 失败，未执行测试。 |
| 3 | `build/diag/supplement-validation-junit-20261110-165641.log` | 0 | 独占实现者修正两处 Supplier lambda 后，`BUILD SUCCESSFUL in 1m 7s`，两个模块测试均真实执行。 |

第二次失败：`dimblend/src/main/java/dimblend/worldgen/CorridorLavaOriginals.java` 第 27、30 行 `builder(HashMap::new)` 在 `AttachmentType.builder(Supplier<T>)` 和 `builder(Function<IAttachmentHolder,T>)` 间重载歧义。主编排负责分派修正；执行单元未修改生产代码。

第 3 次前独占实现者将两处方法引用改为 `builder(() -> new HashMap<>())`，明确选择 Supplier 重载。

第 3 次的新 XML 结果（`<module>/build/test-results/test/*.xml`）：

| 模块 | XML 文件 | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| dimblend | 42 | 249 | 0 | 0 | 0 |
| dimblend-experience | 81 | 509 | 0 | 0 | 0 |
| 合计 | 123 | 758 | 0 | 0 | 0 |

编译仍有现有 deprecated API 警告；没有编译错误。

## GameTest

| Fixture | 参数 | 状态 | 专用运行目录 |
|---|---|---|---|
| 完整补充（第 1 次） | `-PwithSupplement -PsupplementModsDir=E:/misc/TrainTripWorld/mods` | 37 tests，7 required failed；GameTest JVM 退出 7 / Gradle 原生退出 1 | `dimblend-experience/build/supplement-gametest-run` |
| 完整补充（第 2 次） | 同上 | 37/37 required passed；GameTest / Gradle 原生退出均 0 | 同上，保留开发 world |
| 独立死亡正向 | `-PwithSupplementDeath -PsupplementModsDir=E:/misc/TrainTripWorld/mods` | 12/12 required passed；GameTest / Gradle 原生退出均 0 | `dimblend-experience/build/supplement-death-gametest-run` |
| 独立无粉末负向 | `-PwithSupplementDeath -PsupplementNoPowder -PsupplementModsDir=E:/misc/TrainTripWorld/mods` | 1/1 required passed；GameTest / Gradle 原生退出均 0 | `dimblend-experience/build/supplement-death-no-powder-gametest-run` |

每轮需核对实际 `GAME TESTS COMPLETE`、`All N required tests passed`、原生命令退出码与新日志。完整 fixture 默认选择四个 namespace，会包含同 namespace 的旧测试。开发 fixture 只包含四个 vanilla delegate，不能替代完整九区域新地形与整合包交互验收。既有 Sable plot/chunk 时序抖动须按 `8278e40` 对照，不能自动忽略红测。

完整 GameTest 前重新采样：CPU 66/61/67%，均值 64.7%；可用内存 20760 MiB，E: 剩余 278.9 GiB。普通沙箱 CIM 查询被拒绝，其伪 0 数值未采用；实际结果由自动审批的只读 CIM 查询取得。

### 完整补充第 1 次

日志：`build/diag/supplement-validation-gametest-full-20261110-165910.log`。实际 runtime 新日志：`dimblend-experience/build/supplement-gametest-run/logs/latest.log`（17:01:27 汇总，17:01:29 正常保存退出）。Gradle 耗时 2m19s，GameTest 执行 4.593s，37 项中 30 项通过、7 项 required 失败。

实际 batch：`defaultBatch:0` 24 项、`supplement_compass_spawn:0` 1 项、`corridor_lava:0` 12 项。后两 batch 没有失败记录。7 个失败均为死亡正向用例，错误相同：`backpack protection preserves progress, never revives the player`：

| 分类 | 实际数量 | 通过 | 失败 |
|---|---:|---:|---:|
| 岩浆（CorridorLavaSourceGameTests） | 12 | 12 | 0 |
| 指南针（SupplementCompassGameTests） | 1 | 1 | 0 |
| 玩偶（DollStockKeeperGameTests） | 8 | 8 | 0 |
| 死亡正向（BackpackTotemGameTests） | 12 | 5 | 7 |
| 共享 namespace 既有转向架交互（SimurailBogeyInteractionGameTests） | 4 | 4 | 0 |
| 合计 | 37 | 30 | 7 |

分类核对了运行 batch 总数和实际注册门控：Simurail 在场，无条件注册其 4 项交互用例；ChassisEvents.available 还要求 dimblend_blocks、copycats，在此 fixture 不满足，因此未注册 ChassisGameTests/RailwayCasingRecoveryGameTests。不能因选择了 namespace 而声称这些未注册用例已通过。BatchWaterCompat/LinkedTransmitter 的额外依赖门控在本 fixture 亦未满足。

- `fullinventorystackedtotempowderobeyscancelleddeathdrops`
- `snapshotwinsaftermovementcorpsechangesandlateclonelisteners`
- `nextunprotecteddeathresetssolandxpincludingkeepinventory`
- `everymaininventoryslotprotectsoneactualdeath`
- `solrecordandextrahealthsurvivebothresetsettings`
- `keepinventoryconsumesonceevenifdeathisreentered`
- `nondeathclonedoesnotrestoreadeathsnapshot`

该次将错误交回主编排分派诊断，未将其归为既有 Sable 抖动；待 fixture 修正后才继续矩阵。加载存在第三方可选内容错误日志（例如 Railways 可选木材 loot），但未阻止该轮进入和完成测试；未宣称启动日志无错误。

### 完整补充第 2 次

独占实现者诊断 7 个失败共同原因为 fake player 初生无敌窗口，修正仅在 `BackpackTotemGameTests.java`：通过真实 ServerPlayer.tick 60 次消除初生无敌、断言 fixture 状态不变、统一清理 fake players，并增强取消死亡/手持图腾路径确实触发的断言。未修改产品逻辑；第 1 次的 5 个死亡绿测也不能替代修正后的重测。

第 2 次资源采样 CPU 65/74/71%，均值 70.0%；可用内存 20764 MiB，E: 剩余 278.6 GiB。继续相同资源限制，未清理测试世界；GameTest 会重新编译位于 main 的测试 fixture，无理由不重复全部 JUnit。

第 2 次日志：`build/diag/supplement-validation-gametest-full-20261110-170755.log` 第 4696–4697 行：`37 GAME TESTS COMPLETE IN 8.104 s`、`All 37 required tests passed`；Gradle `BUILD SUCCESSFUL in 2m 3s`、原生退出 0。30/7 首轮结果被保留为红测证据，最终本轮 37/37 通过（岩浆 12、指南针 1、玩偶 8、死亡 12、既有转向架交互 4）。服务器正常保存全部维度后退出。

### 独立死亡正向

启动前资源：CPU 73/39/42%，均值 51.3%；可用内存 19433 MiB，E: 剩余 278.6 GiB。实际只选择 S.O.L、Grilling、Cookery 安装 jar，独立于 core 和完整 preset，继续既定串行资源限制。

日志 `build/diag/supplement-validation-gametest-death-positive-20261110-171100.log` 第 2097–2098 行：`12 GAME TESTS COMPLETE IN 2.331 s`、`All 12 required tests passed`；Gradle `BUILD SUCCESSFUL in 58s`，原生退出 0。正常保存退出，没有为消除隔离失败额外加入 core。

### 独立无粉末负向

启动前资源：CPU 33/31/44%，均值 36.0%；可用内存 20386 MiB，E: 剩余 278.6 GiB。仅选择 S.O.L 安装 jar，实际省略 Grilling/Cookery，使用独立专用运行目录。

日志 `build/diag/supplement-validation-gametest-death-no-powder-20261110-171302.log` 第 1862–1863 行：`1 GAME TESTS COMPLETE IN 769.1 ms`、`All 1 required tests passed`；Gradle `BUILD SUCCESSFUL in 46s`，原生退出 0。正常保存退出。

两轮独立运行的实际 mod discovery 日志已核对：正向有 solcarrot 1.16.6、kaleidoscope_grilling 1.1.1、kaleidoscope_cookery 1.4.1；负向只有 solcarrot，没有 Grilling/Cookery；两轮均没有 core `dimblend` mod，隔离未被额外依赖掩盖。

## 收尾与限制

- 全部 Gradle / GameTest 串行运行，所有 session 原生退出码均已取回。最终只读 CIM 进程检查没有 Java/Javaw 进程，本任务无残留 GameTest/Gradle。
- 每轮测试世界均保留，未删除测试世界、用户存档或旧日志；本轮每次尝试均有独立日志。
- 仍有 deprecated API / Gradle 10 兼容警告、第三方缺失可选类/资源/生成注册告警；首次创建独立目录会记录缺少 server.properties 的 ERROR。所有成功测试均明确到达 required 全通过和正常保存退出，不能把这些成功等价为日志零告警。
- GameTest 覆盖服务端逻辑与实际织入路径，不能验收客户端玩偶模型/贴图缩放、Ponder/tooltip 显示、运动视觉效果、完整整合包九区域新地形/性能、玩家真实交互。本轮未部署正式 jar 或进行客户端实机验收。
- 本执行单元只新增本报告及 build 下日志/生成物；源码修复由主编排的独占实现者完成。未 stage、commit、push。
