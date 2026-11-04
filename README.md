# DimBlend 单仓（Gradle 多工程）

八个 mod 同仓管理，Gradle 子工程：

| 子工程 | mod_id | 说明 |
|---|---|---|
| `dimblend/` | `dimblend` | 旋转维度本体 |
| `dimblend-experience/` | `dimblend_experience` | 玩法规则（探索/昵称/compat） |
| `dimblend-craft/` | `dimblend_craft` | TrainTripWorld 配方调整 |
| `dimblend-blocks/` | `dimblend_blocks` | 方块（创造伪装/易碎石） |
| `dimblend-radio/` | `dimblend_radio` | 红石点播电台（空唱片机+顶部选台+侧面音量；另可右键唱片机开 tune 面板，逐台设动态/固定音量） |
| `dimblend-carwash/` | `dimblend_carwash` | 车架脏污与清洗（行驶积灰，水桶/湿海绵/雨/消防喷淋洗净，纯视觉） |
| `dimblend-asyncsave/` | `dimblend_asyncsave` | 异步存档（level.dat 与玩家数据后台写入） |
| `dimblend-spyglass/` | `dimblend_spyglass` | 望远镜远景建筑：**只显示 `?` / `!` 符号与大概距离，不渲染建筑本体**（只读已有结构存档） |

## 常用命令（根目录执行）

```sh
./gradlew build                  # 构建全部八个 jar
./gradlew :dimblend:build        # 只构建某一个
./gradlew projects               # 查看子工程列表
./gradlew build -x test          # 跳过单测的构建
```

进游戏验证仍走本机实例（见 skill `dimblend-dev`）：各子工程 `build/libs/*.jar`
拷入 `E:\misc\TrainTripWorld\mods/`，不要用 `runClient` 另起实例。

## 基线（统一真相源：根 `gradle.properties`）

- MC 1.21.1 / NeoForge **21.1.249**（与 TrainTripWorld 实例一致）/ Parchment 2024.11.17
- ModDevGradle **2.0.147**（各工程统一）
- Ponder **1.0.82+mc1.21.1**（Create 6.0.10 运行时 JarJar 的版本，零漂移；dimblend 已从 1.0.85 回落）
- Wrapper：根唯一一份 Gradle 9.2.1，子工程不再保留 wrapper

## 联机必须两端同版本

各模块用 NeoForge payload registrar 的版本号做握手校验，**两端不一致会直接连接失败**，
不会降级或忽略。所以服务端与客户端必须装同一批 jar；改过协议版本的模块尤其如此。

2026-11-04 批次改动过的协议版本：

| 模块 | 版本 | 改了什么 |
|---|---|---|
| `dimblend_spyglass` | 7 → **8** | 删掉建筑形状/网格包，望远镜只下发标记与距离 |
| `dimblend_radio` | 1 → **2** | 电台状态包末尾追加「逐台音量」字段 |
| `dimblend_experience`（调参面板） | 29 → **30** | 四个电台旋钮搬出 `dbx tune`，面板少四项 |

其余模块本次未动协议版本（`dimblend` 为 1；experience 的 chassis/curse/exploration/nickname 为 1，
train horn 与 simurail deadman 为 2）。跨版本联机时以 jar 为准，不要只替换其中一部分。

## 约定

- `mod_*` 坐标只活在各子工程 `gradle.properties`；共享键只活在根。
- `repositories` 只活在根 `build.gradle`（全集）；子工程不再各自声明。
- experience 的 `neo_version_range`（toml 下限）保留在其子工程 properties。
- 开源 mod 依赖走 Modrinth/Curse Maven 定点版本，不 vendor blob（Eternal Starlight、
  Copycats+、CCA、CDG、sable-companion、Cold Sweat、Create: Fluid、Create: Fire Fighting Add、
  hardcore_nvg 均已 Maven 化）；`libs/` 仅剩两个没有公开坐标的 jar —— simurail 与自建的
  sable cui-modded 分支 —— **全仓只有一份，位于 `dimblend/libs/` 并入库**，各子工程用
  `rootProject.file("dimblend/libs/…")` 指向它。子工程不再各自持有 `libs/` 目录。
  因此干净检出即可 `./gradlew build` 全部 8 个模块，CI 也构建全部 8 个模块。
