# 2026-10-16 水处理与照片兼容

本批需求由窗子提出，用户已确认全部实施。2026-10-16 的五项澄清已写入根 HANDOFF.md。

## 净水

YAT NeoForge 1.21-1.5.2（Modrinth version ID `xmk0FTsX`）。相同版本号有不同加载器文件，禁止仅按版本号解析依赖。

只删除旧净化及堵塞滤芯清洗配方，不改过滤器处理流程；三种滤芯的最高纯净度均为 1（有点脏），织物寿命由 10000 改为 5000，活性炭由 250000 改为 500000，沙滤芯 50000 不变。沿用 YAT 的逐 mB 衰减、旧滤芯保存的剩余寿命和堵塞机制；common setup 修改其公开配置常量，双端一致。

配方归 dimblend-craft：肮脏水瓶在篝火/烟熏变有点脏；1000mB 有点脏 + 烈焰粉变 500mB 可接受并返还烈焰粉；1000mB 可接受加热搅拌成 1000mB 纯净。碗和桶不参与净化配方。

纯净度存于 `minecraft:custom_data.Purity`，0/1/2/3 对应肮脏/有点脏/可接受/纯净。Create 分液池的两次倒瓶调用（模拟与执行）均保留该值；四种精确纯净度的灌装配方使流体能重新装瓶，不触碰普通药水。上游水瓶没有 Purity 时沿用 YAT 的默认值。

## 铜水槽

2026-10-17 补充：合成配方中央的水桶改为空桶，见 craft 的配方规则；下述容器功能不变。

恢复 fluid:copper_sink 配方。`copperSinkInfinite` 默认关闭，在 /dbx 主面板可修改，服务端配置和快照双端同步。开启还需 Create Fluid 的 COMMON `infiniteWater` 为真；本开关只增加限制。

沿用上游 2000mB 储罐、原有流体接口与保存方式。有限模式新槽为空，抽取实际扣量；普通 FluidStack 出入保留纯净度，不另建存储层。铜水槽右键水瓶采用相同 250mB 整瓶交换，补齐 Create 通用手持交换丢失纯净度的问题；未知纯净度水取瓶回退肮脏。普通药水、桶和其他物品沿用原路径。YAT 的 FluidTank 混合逻辑按较低纯净度合并。有限模式的资源型 drain 直接复用 SmartFluidTank 父类语义，修复上游先拒绝非水、造成茶装瓶不扣量的问题；模拟不扣量、执行按请求扣量，流体和组件不匹配则拒绝。开关不清空底层存水；从无限模式切回有限模式后恢复实际存量。水面插值每客户端 tick 追踪当前模式的真实填充比例，避免开关后显示滞留。

## 铜龙头

沿用 `shouldDrip` 与粒子：开着、有来源、没有可灌注目标，连续 300 tick 后首次从来源扣 1mB，以后每 20 tick 扣 1mB。关上、正常灌注、来源不可用会复位计时。复用上游来源方向和空面回退；含水树叶/铜格栅没有真实流体 capability，不扣其虚拟无限来源。不增加粒子。

## 照片

2026-10-17 后续规则：展示框改为居中裁剪至 4:3 并露出两侧框边；新拍照片与取景框采用同一比例，拍摄在 GUI 绘制前取图。详见 [camera-composition.md](camera-composition.md)，下段保留上一批实现记录。

目标 Camerapture 1.10.12+mc1.21.1-neoforge（version ID `bCB7PwmA`）。复用 ClientPictureStore 的下载/缓存和纹理，原版普通/发光展示框采用地图满格框模型，照片按原始比例居中，横图左右顶满、上下留空；竖图保持在框内。未取得图片或无有效数据时保留原版物品渲染。仅在 Camerapture 在场时注册客户端 mixin；地图和其他物品走原路径。

## 动力电池反向冲突破坏

用户最终澄清的对象为 Create Connected 1.2.2 `create_connected:kinetic_battery`，触发为放电旋转方向与外部网络相反导致自动破坏，不是普通扳手拆卸。

已在未修版本以外部 -32rpm、电池 +64rpm 放电复现：Create 在 `super.tick()` 中因动力源方向冲突破坏方块，CC 的外层 tick 继续扣电（满电等级 5→4），`updateLevel()` 又用被移除的旧 BE 状态写回该位置，导致掉落一件而方块复活。外部 -256rpm 走另一分支，不能覆盖此问题。

`KineticBatteryLifecycleMixin` 在状态写入口 `updateLevel()` 校验 level、isRemoved 与当前位置 BE 的精确实例身份。旧实例不能写回空气、其他方块或替换后的新电池；仍沿用 Create 原有冲突破坏和战利品，不抑制销毁或补发掉落。

## 验证入口

只跑本批八条 GameTest：`./gradlew :dimblend-experience:runGameTestServer -PwithBatchCompat`。
该属性仅向开发运行加入 CDG/YAT/Fluid/Create Connected/craft，并把测试命名空间限定为 `dimblend_compat_batch`。
用例覆盖柴油机红石与燃油判据、有限水槽数量/纯净度/开关、水瓶右键四级纯净度往返、非水资源抽取模拟/执行/匹配与空槽防重复出瓶、龙头计时、动力电池反向放电冲突自动破坏（地面/Sable）、新配方真实解码与匹配。无油 HUD 需在客户端执行，服务端无法调用 Create 的客户端格式化链。

局部单测：`./gradlew :dimblend-experience:test --tests 'dimblend.experience.compat.cdg.CdgFluctuationMathTest' --tests 'dimblend.experience.tuning.*'`。

三种柴油机客户端 HUD 已实际执行并各显示两条提示；照片已在完整客户端截图验证普通/发光框的左右标识、满格与留空。动力电池精确冲突路径已在完整 PCL 客户端的地面与 Sable 上验证：外网 -32rpm 跟随状态切换放电后原位为空、各掉落一件。临时验证模组已移除，QA 存档独立于用户原存档，具体证据见 HANDOFF.md。
