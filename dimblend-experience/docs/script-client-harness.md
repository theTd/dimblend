# 脚本化客户端入口（agent_bridge + Minescript 4.0）

本文件只描述 **dimblend nine-fixes 实机脚本测试** 这一条链路：实例里的
minescript/agent_bridge.py 如何执行固定目录下的诊断脚本，以及
client_resume.py / client_state.py 两个入口，以及 capture/capture_hud.py 的 HUD 截图入口。产品代码、Gradle 构建、
live 操作单元自己的 nine-fixes-live 目录都不在这里。

## 1. 边界

- 桥只读写实例内本地文件（agent_bridge/inbox|outbox|done、status.json），没有网络监听。
- eval_file 只接受**已存在的绝对路径 .py 文件**，且 resolve 后必须落在两个固定根内：
  - E:\misc\dimblend\build\diag（历史白名单，保留）
  - E:\codexworktrees\nine-fixes\dimblend\build\diag\nine-fixes-harness（本任务专用根）
- 不接受任意 cwd / 环境变量路径 / 相对路径；Path.resolve() 之后做 containment，
  符号链接指向根外会被拒绝。
- 脚本不使用反斜杠 eval、OS 鼠标键盘、第三方原生模块、网络监听；不启动游戏、不构建 Gradle。

## 2. 桥的改动（最小）

minescript/agent_bridge.py 只改 eval_file 一处，并新增两个纯函数：

    EVAL_FILE_ROOTS = (
        Path(r"E:\misc\dimblend\build\diag"),
        Path(r"E:\codexworktrees\nine-fixes\dimblend\build\diag\nine-fixes-harness"),
    )

    def resolve_eval_file(path_text): ...   # 绝对路径 + resolve + containment + 现存 .py
    def eval_result_value(namespace): ...   # 仅接受 JSON 兼容 dict 的 BRIDGE_RESULT

执行语义不变：exec(compile(...), namespace) 在桥的脚本线程里跑；
inbox/outbox/done、bridge.lock、status.json 心跳、错误格式全部保持原样。
唯一新增：脚本显式赋值 BRIDGE_RESULT（dict）时，桥把它放进 result.eval_result；
不赋值时响应与旧版逐字段一致。

请求示例（仍用实例自带的 send.py；路径用正斜杠，Windows 上等价）：

    python -I E:\misc\TrainTripWorld\agent_bridge\send.py @request-client-state.json --timeout 60

request-client-state.json：

    {"op": "eval_file",
     "path": "E:/codexworktrees/nine-fixes/dimblend/build/diag/nine-fixes-harness/client_state.py"}

## 3. client_state.py（只读）

返回（eval_result.state）：

| 字段 | 含义 |
|---|---|
| screen | 当前 Screen 的 FQCN，无则 null |
| screen_title | Minescript.getScreenName() 的标题 |
| screen_is_pause | Screen.isPauseScreen() |
| overlay / overlay_is_pause | Minecraft.getOverlay() 及其 isPauseScreen() |
| paused | Minecraft.isPaused()（即 runTick 算出的 pause 标志） |
| hide_gui | Options.hideGui |
| game_time | Level.getGameTime()（客户端 level data；暂停时不前进） |
| player_position | minescript.player_position() |
| using_item / scoping | LivingEntity.isUsingItem() / Player.isScoping() |

## 4. client_resume.py（关闭暂停界面）

流程：

1. 渲染线程快照 before（同上字段）。
2. screen == null → already_resumed；若 paused 仍为真则 blocked_paused_by_overlay
   （暂停来自 Overlay，setScreen(null) 无法解除）。
3. 当前 screen 不是暂停屏 → skipped_non_pause_screen，不动它（避免关容器/聊天造成状态丢失）。
4. 暂停时先 sleep 0.4s 再读一次 game_time，记录 pause_probe_ticks（暂停应为 0）。
5. 在 with ms.render_loop: 内调用 Minecraft.setScreen(null)（参数用 ms.java_null = 0）；
   getInstance 的 member 句柄与 setScreen 的 void 返回句柄都在 finally 里 java_release。
6. 再做一次 render-loop 往返，让 runTick 末尾重算 pause；暂停时再 sleep 0.25s。
7. 读取 after，返回 status / ticks_advanced / before / after / notes。

status 取值：resumed、already_resumed、skipped_non_pause_screen、
blocked_paused_by_overlay、closed_but_screen_replaced、closed_but_still_paused、
closed_but_ticks_stalled。ok=true 仅 resumed / already_resumed。

### 4.1 为什么是 setScreen(null)，不是 show_chat_screen

- Minescript.show_chat_screen(false) 只在当前 Screen 是 ChatScreen 时调用 onClose()，
  其它 Screen 直接返回 false（net/minescript/common/Minescript.java:2298-2315），
  没有"关闭任意 Screen"语义。
- Minecraft.setScreen(@Nullable Screen) 是公开方法
  （E:\misc\mcsrc-1.21.1\net\minecraft\client\Minecraft.java:1004），传 null 即回到游戏，
  与 ESC / onClose() 走同一入口；PauseScreen.onClose() 也是 setScreen(null)。

### 4.2 线程证据

- Python 侧 with ms.render_loop: 给后续脚本函数打 R 执行器标记
  （minescript_runtime.py:111）。
- Java 侧 Job.processFunctionCall 把 RENDER_LOOP 放进 job render queue
  （net/minescript/common/Job.java:208-212）。
- 该队列只在 Minescript.onRenderWorld() 里 poll（Minescript.java:1065-1075），
  而它由 MinescriptNeoForgeClientMod 的 RenderLevelStageEvent.Stage.AFTER_LEVEL 触发
  （客户端渲染线程）。
- Minecraft.setScreen 自带线程断言：非 game thread 会记录
  "setScreen called from non-game thread"（Minecraft.java:1005-1007）。

### 4.3 已知限制

- setScreen(null) 在 clientLevelTeardownInProgress（断开中）会抛
  IllegalStateException；桥会把异常按原格式回报，不会吞掉。
- 玩家死亡时 setScreen(null) 会被换成 DeathScreen，脚本报 closed_but_screen_replaced。
- 暂停标志在 runTick 末尾重算（Minecraft.java:1206-1209），所以是"下一帧再读"，
  不是同步返回就为 false。
- 世界被 /tick freeze 冻结时 game_time 同样不前进，会被判成 closed_but_ticks_stalled；
  这是诊断信息，不是 resume 失败。

## 5. 静态验证（不碰游戏）

    python -I E:\codexworktrees\nine-fixes\dimblend\build\diag\nine-fixes-harness\test_harness_guard.py

test_harness_guard.py 用 ast 从**实例里真实的** agent_bridge.py 抽取
EVAL_FILE_ROOTS、resolve_eval_file、eval_result_value，在临时目录里验证：

- 两个固定根都被接受（历史根用现存的 focus_eval.py 样本）；
- 相对路径、根外文件、.. 穿越、非 .py、目录、不存在文件、符号链接逃逸全部拒绝；
- BRIDGE_RESULT 缺省不改变旧行为；dict 回传；非 dict / 非 JSON 值报 ValueError；
- agent_bridge.py 与两个入口脚本都能 compile()。

HUD 截图脚本另有纯静态契约测试：

    python -I E:\codexworktrees\nine-fixes\dimblend\build\diag\nine-fixes-harness\capture\test_capture_contract.py

它检查文件名严格性、PNG 完整性、ms.screenshot 必须在 with ms.script_loop 内、
禁止 tick_loop / 命令 / 子进程 / socket / 反斜杠 eval，并 compile capture_hud.py。

不覆盖：真实游戏内执行、渲染线程实际命中、setScreen(null) 对暂停的实际解除——
这些只能由 live 重启客户端后按下面的顺序实测。

## 6. 实机验证顺序（live 执行）

1. 重进世界，确认 agent_bridge\status.json 心跳更新。
2. client_state.py：应看到 screen: null、paused: false。
3. 若已有暂停菜单，或已通过源码查证的游戏 API 脚本打开暂停菜单，再 client_state.py：screen 为
   net.minecraft.client.gui.screens.PauseScreen，paused: true。
4. 等 1 秒再 client_state.py，game_time 不变（暂停证据）。
5. client_resume.py：应返回 status: "resumed"、after.screen: null、
   after.paused: false、ticks_advanced > 0。
6. 再 client_state.py 交叉确认，接着截 HUD 图。

## 7. HUD 截图（capture/capture_hud.py）

背景：live 核实 ms.screenshot 在默认 render_loop（AFTER_LEVEL）时调用
Screenshot.grab，而 AFTER_LEVEL 发生在 GameRenderer.renderLevel 内、早于 HUD 绘制，
所以之前的截图不含 HUD。本节给出源码结论与替代入口；本轮不新增 Java 类、不加
-D flag、不改桥、不改产品代码。

### 7.1 绘制与 swap 顺序（MC 1.21.1 / NeoForge 21.1.249 补丁源）

1. GameRenderer.render：1079 this.minecraft.gui.render(...) 画 HUD，1098 画当前
   Screen，1151 guigraphics.flush()。
2. Minecraft.runTick：1167 client tick；1190 RenderSystem.clear；1191
   mainRenderTarget.bindWrite；1201 gameRenderer.render；1203
   RenderFrameEvent.Post；1215-1216 unbindWrite + blitToScreen；1223
   window.updateDisplay()。
3. Window.updateDisplay（com/mojang/blaze3d/platform/Window.java:286-287）调用
   RenderSystem.flipFrame；flipFrame 在 144 行 replayQueue()、146 行才
   glfwSwapBuffers。
4. Screenshot.grab(File, String, RenderTarget, Consumer) 在**非渲染线程**调用时走
   RenderSystem.recordRenderCall（Screenshot.java:45-49）；recordingQueue 是
   ConcurrentLinkedQueue（RenderSystem.java:47），由 flipFrame 在 swap 前 drain。
5. Minescript 的 screenshot 就是 Screenshot.grab(gameDirectory, filename,
   getMainRenderTarget(), ...)（Minescript.java:2096）。

### 7.2 两个可用入口，以及 tick_loop 结论

- script_loop（capture_hud.py 采用）：with ms.script_loop 让脚本函数在 job 线程
  执行（非渲染线程）→ Screenshot.grab 走 recordRenderCall → flipFrame 在当前帧
  gui.render + flush 之后、swap 之前读取主 target。截到的是**当前完整帧**。
  它不经过 tick 队列，暂停时也不会阻塞。
- tick_loop：tick 队列在 LevelTickEvent.Pre（Minecraft.java:1892，位于 runTick
  1167 的 tick 内）执行；客户端主线程就是渲染线程（Main.java:211-212 把当前线程
  命名为 Render thread 并 initRenderThread），所以 Screenshot.grab 在该线程上走
  同步 _grab；tick 时主 target 还没被 1190 的 clear 覆盖，读到的是上一帧，而上一帧
  在 1079 已画 HUD、1151 已 flush。**结论：tick_loop 的“上一完整帧（含 HUD）”
  方案源码成立。** 但暂停时 Minecraft.java:1874 的 if (!this.pause) 会整段跳过
  level tick，tick 队列不被服务，脚本将永久阻塞；因此本入口不用 tick_loop。

### 7.3 入口、状态码与限制

请求：

    {"op": "eval_file",
     "path": "E:/codexworktrees/nine-fixes/dimblend/build/diag/nine-fixes-harness/capture/capture_hud.py"}

文件名由脚本生成（nine_hud_<YYYYmmdd_HHMMSS>_<6hex>.png，只含 [A-Za-z0-9_-]），
写入 <gameDirectory>/screenshots/。只有 level 存在、player 存在、screen == null、
getOverlay() == null、Options.hideGui == false 时才请求截图；否则返回
no_level / no_player / screen_open / overlay_open / hud_hidden，不截图。
其余状态：file_timeout（6s 无文件）、invalid_png、captured_state_changed
（0.2s 后探针不再干净，文件保留但不作为有效证据）、captured（ok=true，
附带 bytes 与 SHA-256）。脚本有界等待，不在 render_loop 内 sleep。

已知限制：请求与实际 flipFrame 之间约 0~1 帧，状态探针不是与帧原子绑定；
窗口最小化可能 file_timeout；只验证 PNG 签名/IEND，不解析像素。

### 7.4 静态验证

    python -I E:\codexworktrees\nine-fixes\dimblend\build\diag\nine-fixes-harness\capture\test_capture_contract.py

7 项：文件名严格性（200 次）、完整 PNG 接受、文本/截断 PNG 拒绝、
ms.screenshot 必须在 with ms.script_loop 内、禁止 tick_loop/命令/子进程/socket/
反斜杠 eval、脚本可 compile。不覆盖游戏内 HUD 内容，需实机验证。
