# dimblend

多模块 NeoForge 1.21.1 mod 单仓（列车物理/电台/工艺/体验兼容等，每个子模块一个独立 mod，职责见各自 README）。

## 构建与测试

- 需要 `JAVA_HOME` 指向 JDK 21（本机默认可能指向 JDK 11，gradle 起不来）。
- 单模块编译+单测：`./gradlew :dimblend-experience:test`（模块名换目标模块）；打包：`:<模块>:jar`，产物在 `<模块>/build/libs/`。
- GameTest：`./gradlew :dimblend-experience:runGameTestServer -PwithCdg`；sable 族套件（void-fit/lava-melt）有既有整轮非确定性抖动（plot 区块就位时序，干净 HEAD 可复现，见 commit 8278e40），判红时先对照该记录。

## Mixin 纪律（两轮实机事故沉淀，2026-10-15）

- **声明包内只放 mixin 类与插件类**：各模块 `*.mixins.json` 的 `package`（如 `dimblend.radio.mixin`）内不允许放被 merge 代码或外部引用的 helper/DTO——Mixin 禁止包外类直接引用 mixin 包内非注册类，违则 `IllegalClassLoadError`（反例：JukeboxGoggleLayout 误放 mixin 包，戴护目镜看唱片机才炸，修复见 13d397b）。需要拆纯函数时放普通包（正例：`dimblend.radio.client.JukeboxGoggleLayout`）。
- **mixin 类成员一律 private / 实例方法**：非 private static 字段触发 `InvalidMixinException`，非 private static 方法还会被 merge 进目标类造成污染（事故修复见 60a4815）；`@Invoker`/`@Accessor` 接口的 static 成员是官方惯例，不受此限。注入 handler 用 `require`/`allow` 钉死匹配数。
- **这两类约束单测和启动冒烟都抓不到**（单测不应用 mixin，不触发目标路径就不加载），改动 mixin 类结构或新增引用后，必须实机把对应功能路径走一遍才算验证完。
