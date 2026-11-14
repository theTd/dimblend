# Sable 子层级渲染兼容：诊断开关与已验证缺陷

本文件只记录**已核实根因**的渲染兼容缺陷、它们的开关与验证方式。所有行为开关默认关闭，
诊断日志一律限频，不给常态路径增加开销。

## 1. 动画贴图缓存缺陷：已实施，用户视角停播**待实机**

⚠️ **本节的定性边界**：已证实的只有「缓存不会随图集换新贴图而作废」这一**代码缺陷**，
以及它的修法。**用户报告的"车上火焰只在特定视角位置动 / 在车上看和下车看一样"尚未被证明
由该缺陷引起**——那需要实机对照（见"仍需实机"）。不要把用户现象当成已归因结论。

### 现象（用户报告，未归因）
载具（Sable 子层级）上的熔炉/篝火火焰只在特定视角位置动；在车上看、下车看都一样，
而地面同一种火正常。

### 成因链（2026-11-11 追加取证：Sodium 0.8.13 + 实例 sable 2.0.5_cui-modded）
1. **谁在拦截动画**：Sodium 的 `SpriteContents$Ticker#tick` 在
   `animateOnlyVisibleTextures` 为真、且该贴图**本刻未被标活**时取消推进
   （sodium jar 内层 `mixin/features/textures/animations/tracking/SpriteContentsTickerMixin#preTick`，
   javap 实读 `SodiumOptions$PerformanceSettings.animateOnlyVisibleTextures`、`sodium$isActive` 与
   `CallbackInfo.cancel`），`postTick` 每刻无条件把标记清回 false。实例
   `config/sodium-options.json` 中该开关为 **true**。
2. **谁把贴图标活**：`RenderSectionManager#tickVisibleRenders` 只遍历**上一帧可见区块**的
   `ChunkRenderList`（javap 实读 `SortedRenderLists.iterator` →
   `ChunkRenderList.sectionsWithSpritesIterator` → `SpriteUtil.markSpriteActive`）。
   贴图是**全局**的：一张贴图只要被任一可见的地形区块用到，它这一帧就是活的。
3. **车为什么例外**：Sable 的子层级网格不在 Sodium 的区块网格里
   （`SubLevelRenderer.SelectedRenderer.SODIUM_REACHAROUND` → `VanillaChunkedSubLevelRenderData`），
   它的贴图不在 Sodium 的 render list 中。于是只有车厢自己用某张动画贴图时，它永远不被标活；
   一旦视野里出现同贴图的地面方块（火/岩浆/熔炉正面），整张贴图一起复活——这就是「随视角」的来源。
   与之相符的现场：本批实机日志 `latest.log:23947` 只有一行 `[sable-anim] 贴图代次推进 epoch=1`
   （说明 `renderChunkedSubLevel` 确实被调到），但整场没有任何 `computed`/采样行，
   即该场没有可扫描到动画贴图的子层级区块。

### 已证实的代码缺陷（源码取证）
1. Sodium 开了 `performance.animate_only_visible_textures` 后，只推进被
   `SpriteUtil#markSpriteActive` 标记过的贴图；Sable 的 vanilla 子层级网格不走 Sodium 的
   区块网格，所以本 mod 用 `SubLevelAnimatedSprites` 每刻补标记。
2. `SubLevelAnimatedSprites` 的贴图集合按 **BlockState** 与 **已编译区块** 两级缓存。
   而 `TextureAtlas#upload`（vanilla `net/minecraft/client/renderer/texture/TextureAtlas.java:53-90`）
   每次资源重载都把 `texturesByName` 整体换成新的 `TextureAtlasSprite`
   （`List.copyOf(...)`，同文件 L60/L68），旧实例连同 `SpriteContents`/`Ticker` 一起被
   `clearTextureData()`（L148-155）关掉。
3. `TextureAtlas` 对象本体在 `ModelManager` 里常驻、重载后身份不变，所以**图集身份不是代次信号**。
   可靠的信号是**贴图实例身份**：取一张固定探针贴图（岩浆流动帧）在当前图集里的实例，
   每次 upload 都会换新实例。
4. 原实现两张缓存都不作废：重载后仍持有旧 `TextureAtlasSprite`，
   `markSpriteActive` 标的是已经 `close` 的 `SpriteContents`。
   更关键的已证实事实：`TextureAtlas#upload` 换掉的是 `texturesByName` 里的贴图实例，而
   区块的 `CompiledSection` 身份并不随之更新——所以「`compiled` 没变」不能推出「贴图还是新的」。
   本实现因此改用「图集贴图实例代次」这一独立信号整体作废，而不是依赖 `compiled` 身份变化。

### 修法
（下述缓存代次修复解决的是「重载后仍标旧贴图」；上节成因链对应的补标记主体是
`SableSubLevelSpriteAnimationMixin`，目标方法/字段已对实例 jar 逐项 javap 核对存在。）
- 新增纯 helper `compat/sable/SableAnimationSpriteCache`：按**引用**记账，提供
  `invalidate()`（清空 + 代次 +1）、`retain(live)`（清掉已释放的区块）、`isPending(key)`
  以及**完整性感知**的 `getPartial`。
  - 读取失败（抛异常 / 返回 null）→ 短窗口（20 tick）后重试；
  - 不完整结果（`Computed.complete()==false`）→ 中窗口（40 tick）后重试；
  - 完整结果（含「确实没有动画贴图」的空集合）→ 长期有效，**不**定期重扫；
  - 时间大幅回退（换世界）→ 整体作废；小幅回退（不同维度的 gameTime 抖动）→ 不动缓存。
- `SubLevelAnimatedSprites`：
  - `refreshGeneration()` 用探针贴图判断重载：实例变了就 `invalidate()` 状态级缓存并推进
    全局重载代数；每个子层级渲染器按该代数作废自己的区块级缓存；
  - 区块级结果用 `getPartial`，且 `collect` 把「内有状态仍在等待重试」传播成
    `Computed.partial(...)`。否则一个状态失败、其它状态成功时，区块非空结果会被永久缓存，
    那个失败状态再也没有机会被 `get`，它自己的重试窗口永远不会开始（双层缓存的失败传播）。
  - 桥调用失败次数被计数，日志首次即记、之后每分钟合并一次（不再每帧刷屏）。
- 桥 `SodiumSpriteAnimationBridge`：`markActive` 改为返回是否成功（供诊断采样），
  Sodium 缺席时的提示从 WARN 降为一次性 INFO（缺席是正常软兼容路径）；
  `readActive` 回读 `sodium$isActive()`，仅供诊断。

### 测试状态（两种，分别标注）
1. **正式 JUnit（仓库口径，尚未运行）**：
   `./gradlew :dimblend-experience:test --tests '*SableAnimationSpriteCacheTest*'`。
   本轮**没有 Gradle token，未执行**；首轮全模块编译与测试交整合 owner。
2. **Standalone 预检（本机手动执行，不替代 Gradle）**：把
   `SableAnimationSpriteCache.java` + 对应测试类用 `javac` 直接编译、再用 JUnit Platform
   launcher 跑，结果 `10 tests / 10 successful / 0 failed`。
   这**只**证明该 helper 的逻辑与断言自洽，**不是**仓库正式测试结果，也不覆盖 mixin 应用、
   方块注册与实机渲染路径。

用例（10 个，均直接验证不变量、不镜像实现）：重载后必然重算；失败不永久缓存；
**完整**空结果长期缓存；**不完整**区块按窗口恢复（含"一个状态成功 + 另一个暂时失败，
后者恢复后区块集合补全"）；`stamp` 变化；`retain`；时间回退与维度抖动。

### 仍需实机（本轮无法完成）
- 进档 + 资源重载（F3+T）后载具火焰是否继续动；
- Sodium 的 `animate_only_visible_textures` 打开时与关闭时行为一致；
- **用户报告的现象是否真的由本缺陷造成**：需在实机上用第 2 节的诊断采样对照
  `is_current`/`active` 才能判定；
- mixin `client.SableSubLevelSpriteAnimationMixin` 结构未变，但按仓库纪律**必须**
  在整合包里把「载具上的火/岩浆」路径走一遍才算验证完。

## 2. 诊断开关（默认关闭）

| 开关 | 默认 | 作用 |
|---|---|---|
| `-Ddimblend.sable.particle.diag=true` | 关 | 子层级粒子渲染位姿校正的采样：全局 2 秒最多一行，记录本帧被平移的粒子数与本帧最大位移（格）。默认关闭时不进方法。 |
| `-Ddimblend.sable.animation.diag=true` | 关 | 子层级动画补标记的采样：**全局 2 秒最多一行**（不分区块、不按刻配额，没有每刻条数上限）；记录区块 origin、`compiled` 身份、两级代次、贴图名、贴图实例身份、**当前图集同名贴图实例身份**、`is_current`、`mark_call`、`active` 回读。状态级另有 24 条上限；重载代次推进单独一行。 |
| `-Ddimblend.lootjournal.icon.diag=true` | 关 | Loot Journal 拾取 HUD 组件的入口采样：全局 2 秒最多一行 `[loot-journal-icon]`；记录 `GuiGraphics#bufferSource` 的真实实现类（`HudBatchingBufferSource` 是否在场）、`GL_DEPTH_TEST`、shader color、pose 累计 z、`options.hideGui`。只读，不换 source、不改 GL 状态。 |

采样只读公开信息（`System.identityHashCode`、`sprite.contents().name()`、
`atlas.getSprite(name) == sprite`、`sodium$isActive()`），**不改变任何 GL / 渲染状态**。

三个判据各自独立，**不能互相替代**：

- `is_current` = `atlas.getSprite(sprite.contents().name()) == sprite`（按贴图自己声明的
  `atlasLocation()` 选图集）。这是**唯一**能判定「缓存里的贴图实例是否仍属于当前图集」的手段：
  贴图自带的 `atlasLocation()` 只是资源名，图集对象身份在重载前后也不变。
  `is_current=false` 表示缓存已过期（正是本次修复要消除的状态）。
- `mark_call`：`markSpriteActive` 反射调用正常返回，**不等于**动画活跃。
- `active`：回读 Sodium 内部 `SpriteContentsExtension#sodium$isActive()` 的真实值，
  才是「Sodium 会推进这张贴图动画」的依据。回读句柄不可用时打印 `n/a`（**不写成 false**）。

判读要点：
- 重载后应出现一行 `贴图代次推进 epoch=N`，之后 `state_epoch`/`section_epoch` 单调增，
  且新采样应立刻满足 `is_current=true`；
- `is_current=false` → 缓存没随重载作废（回归）；
- `is_current=true` 且 `active=true` 却仍定格 → 问题不在补标记侧（转去查 Sodium 的可见性剔除或
  子层级网格本身），不要继续加标记；
- `active=n/a` 时不要下「未激活」的结论。

## 3. 未做（避免假修复）

### 3.1 石磨粒子（kaleidoscopecookery 1.4.1）——已定位到 renderPose 阶段并修复

**三段链路（均为源码/字节码取证）**
1. **生成中心（服务端，plot 坐标）**：`MillstoneBlockEntity#tick`（CFR 反编译自实例
   `[森罗物语：厨房] kaleidoscopecookery-1.4.1-neoforge+mc1.21.1.jar`）第 246–260 行：
   `center = Vec3.atBottomCenterOf(getBlockPos())`，
   `particlePos = new Vec3(0,1,1).yRot(rot) + center`，
   `serverLevel.sendParticles(BLOCK|ITEM, …, 5, 0.1,0.1,0.1, 0.05)`。
2. **Sable 换算（客户端）**：`ServerLevelMixin` 只把距离判定改成
   `distanceSquaredWithSubLevels`，发给客户端的仍是 plot 坐标；`ParticleEngineMixin#add`
   （`@Inject(method="add", at=TAIL)`，实例 jar javap 实读）调
   `ParticleExtension#sable$initialKickOut`，后者用 `subLevel.logicalPose()` 做 plot→world
   （`ParticleMixin.java:142`；javap 常量池实读 `SubLevel.logicalPose:()LPose3d;`）。
   **没有**任何二次换算，所以不得在 `sendParticles` 处再转坐标（那样会整团泄漏到 plot）。
3. **绘制（render pose）**：子层级网格用 `subLevel.renderPose()`
   （`VanillaChunkedSubLevelRenderData.java:287`），即
   `lastPose.lerp(logicalPose, partialTick)`（`ClientSubLevel.java:313–329`）。

**已证实的偏差阶段＝第 3 段**：粒子被钉在**tick 末**位姿，网格却在 tick 内插值。
移动/转向中的载具上，整团粒子相对模型前移 `(1-partialTick)` 个 tick 的位移，并随帧抖动；
静止载具 `lastPose == logicalPose`（位移 0），地面方块没有子层级位姿（正常）。
这与「地面正常、装配车上偏、静止车看不出」的口径一致；第 1、2 段无缺陷，不改。

**修法（本轮）**
- `compat/sable/SubLevelParticleRenderPose`：`logicalPose⁻¹(当前世界坐标) → renderPose(pt)` 求差值；
- `mixin/client/SableParticleEngineRenderPoseMixin`：`ParticleEngine#render` HEAD 平移、RETURN 还原
  被 Sable 子层级追踪的粒子（`ParticleExtension#sable$getTrackingSubLevel()` 非空），
  只动绘制坐标，模拟位置/速度/包围盒不变；
- `mixin/client/SableParticleRenderPoseMixin`：给 `Particle` 加渲染坐标读写口
  （`x/y/z` 与 `xo/yo/zo` 都是 `protected`，`Particle.java:24–29`）。
- 诊断：`-Ddimblend.sable.particle.diag=true` 时每 2 秒一行
  `[sable-particle] 按渲染位姿平移 N 颗粒子，最大位移 x.xxxx 格`——静止车应≈0，行驶车是偏差量级。

**验证状态**：手工 `javac -proc:none` 预检通过（见 §4）；**Gradle 与实机都未跑**。
实机判据：静止车最大位移≈0；行驶/转弯时粒子不再相对磨盘前移或抖动；
未被追踪的地面粒子行为不变。

### 3.2 Loot Journal 图标缺失——已定位到玩家端 `elementOrder` 拼写错字触发 `[NAME]` 回退并修复
- **真实根因（2026-11-14 玩家实际配置 + 字节码闭环）**：开发实例 `E:\misc\TrainTripWorld\config\obscuria\loot_journal-client.toml` 是合法默认串 `"COUNT 4px NAME 4px ICON 4px TOTAL"`（因此本机无法复现），而玩家 PCL 实例传来的 `loot_journal-client.toml` 第 44 行为 `elementOrder = "COUNT 4px NAME 4px ICON 4px TOTA"`（漏写末尾 `L`）。`LayoutParser#createElement("TOTA")` 抛 `IllegalArgumentException`，`ConfigCache#refresh` 捕获后回退到 `PickupLayout.DEFAULT`，而上游 `PickupLayout.DEFAULT` 实为 `new PickupLayout(List.of(NameToken.SHARED))`（仅含 `NAME`，`ICON` 被静默丢弃）。
- **修法**：`LootJournalLayoutSanitizerMixin` 在 `LayoutParser#normalize` 入口通过 `LootJournalLayoutSanitizer` 自动修复唯一前缀错字（如 `TOTA -> TOTAL`）并隔离非法词元；同时移除已证伪的 `LootJournalHudCompat`（换 `bufferSource`），仅保留默认关闭的只读采样开关 `-Ddimblend.lootjournal.icon.diag=true`。

### 3.3 明确不做
- 坐标源：Sable `RenderSection` origin 是 plot 全局存储坐标，父 `ClientLevel` 被
  `ClientChunkCacheMixin` 重定向，现有读取正确，**不得换成 EmbeddedAccessor**。
- 粒子：**不得**在服务端 `sendParticles` 处重复做 Sable 坐标转换。

## 4. 编译与注册状态

- **2026-11-11（石磨粒子修复）**：新增 `RenderPoseShiftable`、`SubLevelParticleRenderPose`、
  `SableParticleRenderPoseMixin`、`SableParticleEngineRenderPoseMixin` 四个文件，并把后两者登记进
  `dimblend_experience.mixins.json` 的 `client`、在 `DimBlendMixinPlugin` 里与既有
  `SableSubLevelSpriteAnimationMixin` 同门控（`isLoaded("sable")`）。
  手工 `javac -proc:none`（JDK 21；classpath = 主检查点 `neoforge-21.1.249-merged.jar` + 实例 sable jar
  + `sable-companion` jar + sponge-mixin + joml + fastutil + slf4j + 本 worktree 已编译 classes）
  **exit 0**。**Gradle 未跑**：wrapper 需要写 `C:\Users\cui\.gradle`，本会话沙箱只允许写工作区，
  报 `FileNotFoundException: ...gradle-9.2.1-bin.zip.lck (Access is denied)`。
  因此本批**没有** `:dimblend-experience:test` 数字，也**没有**实机验证；mixin 应用正确性按仓库纪律
  需要进游戏把「载具上的粒子」路径走一遍才算验证完。
- 本轮**未运行 Gradle、未进游戏**（无 token）。全模块编译/正式测试与实机由整合 owner 串行执行。
- 手工 `javac` 预检（仅用主检查点 `E:/misc/dimblend/build/moddev/artifacts` 里的 NeoForge/MC
  jar，无 Gradle）：四个 `compat/sable` 类**通过**，仅剩 `@Deprecated` API 警告与
  `Dist` 注解类的 classpath 警告（后者是手工 classpath 的已知现象，不是代码问题）。
- `SableSubLevelSpriteAnimationMixin` 的手工预检**未通过**：报
  `dev.ryanhcode.sable.companion.ClientSubLevelAccess` 找不到——该类型在 sable 的 jarjar 内层，
  手工 classpath 无法拼齐。**交整合 owner 用 Gradle 正规 classpath 编译判定**，本轮不自行排错。
- **2026-11-14（粉碎轮粒子坐标定点化 & Loot Journal 布局容错）**：新增普通包工具类
  `dimblend.experience.compat.create.CrushingParticleMath` 与 `CrushingWheelControllerParticleMixin` /
  `CrushingWheelControllerBeParticleMixin` 的 `double` 定点分支；删除 `LootJournalHudCompat` 与
  `GuiGraphicsBufferSourceAccessor`，新增 `LootJournalIconDiagnostics`、`IconSamplingWindow`、
  `LootJournalLayoutSanitizer` 及 `LootJournalLayoutSanitizerMixin`（登记入 `dimblend_experience.mixins.json`
  `client` 并在 `DimBlendMixinPlugin` 按 `isLoaded("loot_journal") && classPresent("...LayoutParser")` 门控）。

## 5. 只读取证脚本（本机静态就绪，实机执行属 live）

脚本根固定为 `E:/codexworktrees/nine-fixes/dimblend/build/diag/nine-fixes-harness/render/`，
由 live 的 Minescript 4 桥以 `eval_file` 执行；脚本只读，不跑命令、不写世界、不改 GL/渲染状态。
参数走**同名 `.json`** 旁挂文件（模板见 `*.example.json`），可设 `BRIDGE_RESULT` 回传
`result.eval_result`。两份脚本都不调用 `markSpriteActive`、不调用 `sendParticles`、
不重新做坐标变换——它们只读结果。

桥执行脚本时 **namespace 里没有 `__file__`**，因此两份脚本都用本任务的固定绝对目录常量
`HARNESS_RENDER_DIR` 定位旁挂配置，不从 `__file__` 推导（会 NameError）。

### 5.0 观测模型（先说清能证明什么）

- 每次采样是一个**独立的 `ms.render_loop` 块**（各自一次渲染线程作业），
  但**一个块不等于一帧**，块内的多个 `java_*` 调用**也不保证原子**。
  因此每次采样都记 `System.nanoTime()` 与 `Level.getGameTime()` 的**起止值**，
  回传 `nano_start/nano_end/elapsed_ms/game_time_start/game_time_end/spanned_ticks`。
  时间戳相同只作为事实记录，**不据此断言"同一帧"**。
- 帧间位移是**跨越未测量间隔的位移**，不是每帧速度。

### 5.1 粒子实际位置：`particle_probe.py`
```json
{"op": "eval_file",
 "path": "E:/codexworktrees/nine-fixes/dimblend/build/diag/nine-fixes-harness/render/particle_probe.py"}
```

返回 `samples[]`（每次采样一条，含上述时间戳与 `spanned_ticks`）、每条里的
`engine_particle_count / scan_truncated / particles_reported`，以及每颗粒子的
`class / identity / pos / distance / age / render_type / aabb_min`；给了 `expect`
时另有 `delta_to_expect`。

`motion[]` 给出**同一颗粒子对象**在相邻两次采样之间的 `from / to / delta`：
配对严格用 `System.identityHashCode`（对象生命周期内稳定）。该值不保证唯一，
因此**同一次采样内重复出现的 identity 一律判为歧义并从配对中剔除**，计入
`identity_collisions`，不猜配对。**不用 `(class, age)` 配对**：`age` 每刻递增、
且不同粒子可以有相同 age。

- 源码依据：`Particle.java:24-32`（`x/y/z` 是 `protected`，故用 `toString()` 的
  `Pos (x,y,z)` 文本取坐标，`Particle.java:139-158`）；`AABB.java:14-18`（`minX/minY/minZ`
  是 `public final`）；`System.identityHashCode`。
- **公开 API 修正（此前版本是错的）**：`Minecraft.particleEngine` 是
  `public final` **字段**（`Minecraft.java:281`），**没有访问器方法**——对
  `particleEngine` 用 `java_call_method` 会抛 "No matching methods"。现在走
  `getInstance()` + `java_access_field`。同理 `SpriteUtil.INSTANCE` 是静态字段，
  用 `java_access_field(null, ...)`，不是方法。
- 唯一非公开读取：`ParticleEngine.particles`（`ParticleEngine.java:84`，`private final`，
  无公开访问器），用 `Class#getDeclaredField` + `Field#setAccessible` 经 `java_*` 桥读取；
  遍历 `values()` 里的**队列**再逐颗取（该字段是
  `Map<ParticleRenderType, Queue<Particle>>`，直接当集合会拿到队列而不是粒子）。
- **硬上限**：`samples` 夹到 `[1, 8]`，`max_particles` 夹到 `[1, 200]`
  （配置无法解除，夹取会写进 `clamp_notes`）；另有 `MAX_SCAN_PARTICLES=4000` 的句柄上限，
  超出置 `scan_truncated`。`particles_reported` 是**报告条数**，不是半径内全部。
- 用法：站在目标磨旁，`near` 留 `null` 即取玩家位置；或显式给 `near`/`expect`（磨口点）。
  磨静止（0°/90°）与载具直行、弯道各采一轮，对比 `motion[].delta` 与 `delta_to_expect`。

### 5.2 动画活跃状态：`animation_probe.py`（**不是**诊断日志的替代品）
```json
{"op": "eval_file",
 "path": "E:/codexworktrees/nine-fixes/dimblend/build/diag/nine-fixes-harness/render/animation_probe.py"}
```

**先说清边界（这是上一版文档的错误）**：脚本永远经
`Minecraft#getTextureAtlas` → `Function#apply` 从**当前**图集取贴图，再用**同一只手**
解析回来比较，所以这个身份比较**按构造必为 true**。它**不能**判断 mod 缓存里那张旧贴图是否
过期——旧实例在脚本里根本不可达。

- `live_atlas_resolves`：**恒为 true 的解析器自检**（类/名字路径通、贴图存在、
  `contents()` 可读），不是"缓存是新的"的证据。
- `stale 缓存证据只来自现有 Java 诊断`：`-Ddimblend.sable.animation.diag=true` 下
  `SableAnimationDiagnostics` 为**mod 实际正在标记的那张**打
  `sprite_at / current_at / is_current / mark_call / active`。脚本只是把同一时间段的相关日志
  行有界摘出来（`diag_tail`），**两份不是同三事实，不能互相替代**。
- 脚本真正可观测的：
  - `active_readings[]`：活贴图 `contents` 上 `SpriteContentsExtension#sodium$isActive()`
    的真实值（Sodium ticker 读的就是它）。每 tick 被清，故采 `samples` 次序列；
    回读不可用记 `n/a`，**不写成 false**。
  - `has_animation`：`SpriteUtil.INSTANCE.hasAnimation(sprite)`
    （javap 核对 `0.8.13+mc1.21.1`：`public abstract boolean`）。
  - `sprite_identity_readings` / `contents_identity_readings` / `atlas_swapped_during_probe`：
    **活图集自身**在两次采样间是否换过实例（即重载是否发生）。是活-活比较。
- **不调 `markSpriteActive`**：它会改 Sodium 状态，且调用成功不等于动画活跃。
- **硬上限**：`samples` 夹到 `[1, 16]`，`sprites` 截到 24 条，
  `diag_tail_bytes` 夹到 `[4096, 1 MiB]`，日志只报最后 10 行。
- 日志读取是**有界尾读**：`seek(末尾 - N)` 只读该字节窗口，绝不 `readlines()` 整个文件；
  `partial_first_line` 标记窗口是否切断了首行。

### 5.3 执行顺序（冷启 → 重载）
1. JVM 参数 `-Ddimblend.sable.animation.diag=true`（默认关；开启后才有 `[sable-anim]` 行）。
   **stale 证据靠它**，脚本替代不了。
2. 冷启进档，坐到能看见载具火焰的位置 → 跑 `animation_probe.py` 记 A 组。
3. 触发资源重载：`Minecraft#reloadResourcePacks()`（`Minecraft.java:888`；F3+T 走的是
   `KeyboardHandler.java:247-250` 同一入口）。**脚本不自己调它**，用真人按键，避免脚本制造出
   与用户不同的重载时序。
4. 重载后立刻再跑一次记 B 组：`atlas_swapped_during_probe` 在跨重载的对比里应为 true
   （活图集换了实例）；`active_readings` 在可见时序列里应出现 true。
5. `diag_tail` 里应有新的 `贴图代次推进 epoch=N` 且该行 `is_current=true`——
   **这才是"缓存已随重载作废"的证据**。

### 5.4 静态验证范围（本轮实际做过的）
- 两份脚本 `python -m py_compile` 通过（Python 3.15，非 Gradle、非游戏）。
- 脚本里每个 Java 类名/方法名/字段名都按源码行号核对过**存在性与可见性**；本轮另修正两处
  真实错误：`__file__`（桥不注入）、`Minecraft.particleEngine` 当方法调用。
- **未执行**：脚本从未在游戏里跑过（CPU 94%/可用内存 2.6 GiB，无游戏 token）。
  返回结构、句柄释放时机、跨采样配对效果都还没有实机证据。

### 5.5 明确拿不到的事实（缺口）
- **粒子属于哪个子层级**：`java_call_method` 按声明方法的类解析，无法把基类实例当接口调用
  `ParticleExtension#sable$getTrackingSubLevel`。脚本只报世界坐标与位移，不报归属。
- **粒子是否被 `initialKickOut` 平移过**：mixin 内部行为，无公开可观测痕迹；只能靠位移是否
  系统性等于子层级位姿变化推断。
- **`renderChunkedSubLevel` 的调用计数/时序**：mixin 内部，无公开字段。若必须拿到，最小方案是
  给现有 `SableSubLevelSpriteAnimationMixin` 注入处加 `@Unique` 计数器 + 受 diag flag 控制的
  日志——**属改 mixin，本轮不做**，需父安排。
- **Loot Journal 的 `renderIcon` 调用**：私有 GUI 路径，脚本无入口；现场尚未复现
  （live 会先恢复 HUD 采有效图），本轮不写 instrumentation。
