# DimBlend 单仓（Gradle 多工程）

四个 mod 同仓管理，Gradle 子工程：

| 子工程 | mod_id | 说明 |
|---|---|---|
| `dimblend/` | `dimblend` | 旋转维度本体 |
| `dimblend-experience/` | `dimblend_experience` | 玩法规则（探索/昵称/compat） |
| `dimblend-craft/` | `dimblend_craft` | TrainTripWorld 配方调整 |
| `dimblend-blocks/` | `dimblend_blocks` | 方块（创造伪装/易碎石） |

## 常用命令（根目录执行）

```sh
./gradlew build                  # 构建全部四个 jar
./gradlew :dimblend:build        # 只构建某一个
./gradlew projects               # 查看子工程列表
./gradlew build -x test          # 跳过单测的构建
```

进游戏验证仍走本机实例（见 skill `dimblend-dev`）：各子工程 `build/libs/*.jar`
拷入 `E:\misc\TrainTripWorld\mods/`，不要用 `runClient` 另起实例。

## 基线（统一真相源：根 `gradle.properties`）

- MC 1.21.1 / NeoForge **21.1.249**（与 TrainTripWorld 实例一致）/ Parchment 2024.11.17
- ModDevGradle **2.0.147**（dimblend 已从 2.0.144 升上来；craft 的 NeoGradle userdev 7.1.38 迁移另起任务）
- Ponder **1.0.82+mc1.21.1**（Create 6.0.10 运行时 JarJar 的版本，零漂移；dimblend 已从 1.0.85 回落）
- Wrapper：根唯一一份 Gradle 9.2.1，子工程不再保留 wrapper

## 约定

- `mod_*` 坐标只活在各子工程 `gradle.properties`；共享键只活在根。
- `repositories` 只活在根 `build.gradle`（全集）；子工程不再各自声明。
- experience 的 `neo_version_range`（toml 下限）保留在其子工程 properties。
- experience/blocks 的 `libs/` 本地罐不入库（各自 `.gitignore` 延续单仓政策，出处见 experience `docs/requirements-spec.md`）；dimblend 的 `libs/{simurail,sable}` 是 tracked 编译基（无公开 Maven 的特殊构建才 vendor；Eternal Starlight 等开源 mod 走 Modrinth/Curse Maven 定点版本）。
