# Dimblend Craft

NeoForge Mod，目标版本 Minecraft **1.21.1**（NeoForge `21.1.249`，NeoGradle `7.1.38`，Java 21），
mod id 为 `dimblend_craft`，包名为 `dimblend.craft`。

本 mod 是 **TrainTripWorld 整合包**专用的运行时配方调整工具：不改动任何上游 mod 文件，
在每次数据包重载（初始加载与 `/reload`）完成后，按冻结的需求规则表对 **Create 及其附属、
TACZ** 的配方做批量修改与删除。规则明细见 `docs/配方修改需求.md`。

## 工作原理

- 入口 `DimblendCraft` 监听 NeoForge `AddReloadListenerEvent`，追加一个重载监听器；
  该监听器排在 vanilla 监听器之后，apply 阶段对 `RecipeManager` 做单趟
  「删除 / 有序合成网格修改」，最后经原版 `RecipeManager#replaceRecipes` 原子生效。
- 规则全部声明在 `RecipeEditRules`（纯数据，与需求文档逐行对应），编辑逻辑在
  `ShapedGridEditor`（3×3 网格居中语义，与 JEI 显示一致），执行与日志在 `RecipeEditApplicator`。
- 目标 mod 缺失的规则自然空转，仅输出「未命中」告警；规则按产物 ID / 配方 ID 匹配，
  不依赖任何 mod 类，编译期只有 vanilla + NeoForge 依赖。

## 环境要求

- JDK 21，且 Gradle 本体必须跑在 JDK 17+ 上：`$env:JAVA_HOME='C:\Program Files\Java\jdk-21'`
  再执行 `.\gradlew.bat ...`（本机默认 `JAVA_HOME` 指向 JDK 11，会报
  `Gradle requires JVM 17 or later`；也可在 IDE 里把 Gradle JVM 指定为 21）。
  游戏编译/运行的 Java 21 toolchain 由 Foojay 插件自动供应；Gradle 运行参数收敛到根 `gradle.properties`。
- Gradle Wrapper 只有根一份（`gradlew.bat` / `gradlew`，Gradle 9.2.1），命令一律在仓库根执行，无需全局安装 Gradle

## 常用命令

```powershell
# 以下命令一律在仓库根执行
# 列出 run 配置并验证构建脚本（不下载游戏文件，最快）
.\gradlew.bat :dimblend-craft:tasks --group="NeoGradle/Runs"

# 构建 jar（输出 dimblend-craft/build/libs/dimblend_craft-0.1.0.jar）
.\gradlew.bat :dimblend-craft:build

# 启动客户端 / 服务端（首次会下载 MC、映射、依赖，耗时较长）
.\gradlew.bat :dimblend-craft:runClient
.\gradlew.bat :dimblend-craft:runServer
```

## 项目结构

```text
src/main/java/dimblend/craft/
  DimblendCraft.java              # @Mod 主类：注册数据包重载监听器（唯一的入口逻辑）
  recipe/
    RecipeEditRules.java          # 配方编辑规则表（与 docs/配方修改需求.md 逐行对应）
    RecipeEdit.java               # 规则类型：按配方 ID 删除 / 按产物删除（合成+规则指定类型） / 有序网格修改
    ResultIdFilter.java           # 产物 ID 匹配器（精确 + 前缀）
    RecipeIngredient.java         # 材料描述（物品 ID / 标签 ID），重载期才解析成 Ingredient
    ShapedGridEditor.java         # 3×3 网格编辑与 ShapedRecipe 重建
    RecipeEditApplicator.java     # 单趟执行器：删除/修改 + 日志 + 未命中告警
    RecipeEditReloadListener.java # AddReloadListenerEvent → apply 阶段执行规则
src/main/resources/
  META-INF/neoforge.mods.toml     # 由 gradle.properties 变量展开，切勿手写版本号
  data/c/tags/item/shulker_boxes.json  # 本 mod 提供的 c:shulker_boxes 标签（17 种潜影盒）
gradle.properties                # mod 坐标 + userdev parchment 键；MC/Neo 共享键以根为准（此处为冻结影子，见文件头注释）
build.gradle                     # NeoGradle userdev 配置、run、资源展开
docs/配方修改需求.md              # 需求冻结文档（规则表的唯一依据）
```

## 需求要点备忘（已按整合包配方文件核实）

- **网格位置语义**：配方在 3×3 网格中居中（与 JEI 一致），窄配方空缺槽位可被「添加」填充。
- **蓄电池**：本版 CCA 的可合成产物是 `createaddition:modular_accumulator`
  （`accumulator` 为弃用方块，无配方）；规则两个 ID 都匹配。
- **弹药装配台**：TACZ 配方产物是 `tacz:workbench_a` + custom_data，只能按配方 ID 删除。
- **植物油**：全整合包内获取配方仅 `createdieselgenerators:compacting/plant_oil`
  （Create 压缩）；植物油桶无配方，规则里仅作防御性删除。
- **SnR 自有铁轨**：`railways:track_*` 产物前缀；合成台配方 + 序列组装配方
  （类型键 `create:sequenced_assembly`）都删，原版 `minecraft:rail` 不动。
  经整合包配方文件核实，该前缀产物的配方仅有这两种类型。
- **c:shulker_boxes**：整合包内无任何 mod 定义该标签，由本 mod 提供
  （合并式标签，覆盖全部原版潜影盒，`required: false`）。

## 许可

见 `TEMPLATE_LICENSE.txt`（模板自带，占位用，发布前请替换为实际许可证，
并同步 `gradle.properties` 的 `mod_license`）。