# DimBlend Blocks

DimBlend 系列方块 Mod。Minecraft **1.21.1** + NeoForge **21.1.249**，构建基于
ModDevGradle 2.0.147 / Gradle 9.2.1，与 `dimblend`、`dimblend-craft`、
`dimblend-experience` 保持同一基线。

当前内容：

- C 板块·创造模式伪装方块（自 `dimblend-experience` v1.6 迁出）——伪装半砖/伪装粱
  （Copycats+ 基类）+ 伪装板（Create 本体基底）+ 自有 BE 类型 + C0 全体伪装方块硬度
  统一黑曜石 mixin；C4 洁净伪装板/半砖（永不积脏，洗车死角用）+ C5 伪装板/半砖隐藏
  （未贴材质空手右键隐藏、持扳手幽灵显现、扳手恢复、隐藏期洗车脏值冻结；隐藏状态挂
  BE NBT 不走方块状态）。仅在 Copycats+ 在场时激活（mods.toml optional 依赖 + mixin plugin 过滤）。
- K 板块·Create 创造模式传动件——创造模式传动杆/齿轮（同 create:shaft / create:cogwheel，
  生存不可破坏、生存扳手不可拆、可套壳、潜行扳手拆壳、蒸汽引擎天然不接入；扳手按模式
  分派：创造=原版旋转+潜行拆卸不掉落、生存=裸件互转禁拆；乌木齿轮+黑铁杆识别色与原版区分）。
  仅在 Create 在场时激活。

## 常用命令

| 命令（仓库根执行） | 说明 |
| --- | --- |
| `./gradlew :dimblend-blocks:build` | 构建并输出 jar 到 `dimblend-blocks/build/libs/` |
| `./gradlew :dimblend-blocks:runClient` | 启动开发客户端 |
| `./gradlew :dimblend-blocks:runServer` | 启动开发服务端 |
| `./gradlew :dimblend-blocks:runData` | 运行数据生成，输出到 `dimblend-blocks/src/generated/resources/` |

## 目录结构

- `src/main/java/dimblend/blocks/` — Java 源码（主类 `DimBlendBlocks`）
  - `compat/copycats/` — C 板块方块/BE/物品/创造栏注册（`CreativeCopycats`；含 C4 洁净变体、C5 隐藏交互）
  - `compat/create/` — K 板块创造传动件注册（`CreativeKinetics`，仅 Create 在场）
  - `api/` — 对兄弟 mod 开放的唯一入口（`CreativeCopycatHiding` 隐藏查询）
  - `client/` — 客户端渲染（`CreativeCopycatClient` 模型装饰/幽灵渲染，`CreativeKineticsClient` 传动件 visual）
  - `mixin/` — C0 硬度统一（`DimBlendBlocksMixinPlugin` 按 copycats/create 在场性过滤）
- `src/main/resources/` — 资源（assets / data）
- `src/main/templates/` — `neoforge.mods.toml` 模板，属性由 `gradle.properties` 展开注入
- `src/generated/resources/` — datagen 输出

## libs jar

Copycats+（C 板块编译基类）走 Modrinth Maven 定点版本
（`maven.modrinth:copycats:3.0.4+mc.1.21.1-neoforge`，见 `build.gradle`），不 vendor blob。
需运行时联测时临时将 `compileOnly` 提升为 `localRuntime`。