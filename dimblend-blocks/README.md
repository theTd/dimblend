# DimBlend Blocks

DimBlend 系列方块 Mod。Minecraft **1.21.1** + NeoForge **21.1.249**，构建基于
ModDevGradle 2.0.147 / Gradle 9.2.1，与 `dimblend`、`dimblend-craft`、
`dimblend-experience` 保持同一基线。

当前内容：C 板块·创造模式伪装方块（自 `dimblend-experience` v1.6 迁出）——
伪装半砖/伪装粱（Copycats+ 基类）+ 伪装板（Create 本体基底）+ 自有 BE 类型 +
C0 全体伪装方块硬度统一黑曜石 mixin。全部仅在 Copycats+ 在场时激活
（mods.toml optional 依赖 + mixin plugin 过滤）。

## 常用命令

| 命令（仓库根执行） | 说明 |
| --- | --- |
| `./gradlew :dimblend-blocks:build` | 构建并输出 jar 到 `dimblend-blocks/build/libs/` |
| `./gradlew :dimblend-blocks:runClient` | 启动开发客户端 |
| `./gradlew :dimblend-blocks:runServer` | 启动开发服务端 |
| `./gradlew :dimblend-blocks:runData` | 运行数据生成，输出到 `dimblend-blocks/src/generated/resources/` |

## 目录结构

- `src/main/java/dimblend/blocks/` — Java 源码（主类 `DimBlendBlocks`）
  - `compat/copycats/` — C 板块方块/BE/物品/创造栏注册（`CreativeCopycats`）
  - `client/` — C 板块客户端模型装饰器（`CreativeCopycatClient`）
  - `mixin/` — C0 硬度统一（`DimBlendBlocksMixinPlugin` 按 copycats/create 在场性过滤）
- `src/main/resources/` — 资源（assets / data）
- `src/main/templates/` — `neoforge.mods.toml` 模板，属性由 `gradle.properties` 展开注入
- `src/generated/resources/` — datagen 输出

## libs jar

`libs/copycats-3.0.4+mc.1.21.1-neoforge.jar` 为 compileOnly 依赖（C 板块编译基类），
不入库（与 `dimblend-experience` 同政策）；出处与还原步骤见
`dimblend-experience/docs/requirements-spec.md`「libs jar 清单」。
需运行时联测时临时将 `compileOnly` 提升为 `localRuntime`。