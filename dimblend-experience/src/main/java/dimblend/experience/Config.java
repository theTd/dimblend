package dimblend.experience;

import dimblend.experience.compat.sable.LavaMeltMath;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-side feature switches. All exploration/compat rules are enforced on the
 * logical server, so a SERVER config keeps client installs from desyncing.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // 键名由 deathRules（旧布尔开关，v1.13 前的 A1/A2：死亡保留物品+床遗失改道重生）改为
    // deathExpClearRatio：保留物品/改道重生已取消，旧键被自动清除、新键取默认 1.0
    public static final ModConfigSpec.DoubleValue DEATH_EXP_CLEAR_RATIO = BUILDER
            .comment("探索限制 A1（v1.13 重写）：旋转维度内死亡重生按比率清空经验——重生后经验 = 死亡前(等级+进度) × (1−比率)，"
                    + "0=全保留、1=全清；无视 keepInventory 游戏规则；经验球不掉落（不能找回）。"
                    + "物品不再由本模组保留，死亡掉落交给 gamerule 与其他 mod")
            .defineInRange("deathExpClearRatio", 1.0D, 0.0D, 1.0D);

    public static final ModConfigSpec.BooleanValue DEPTH_CURSE = BUILDER
            .comment("探索限制 A3/A4：|z| 每跨过 256 生命上限 ×0.75（下限 1 点），回到 |z|≤128 完全恢复；关闭只停扣上限，层级计数照常（进度条用）")
            .define("depthCurse", true);

    public static final ModConfigSpec.BooleanValue CURSE_BOSSBAR = BUILDER
            .comment("探索限制 A5：旋转维度内在盔甲 HUD 位置常驻显示 z256 进度条（(|z| − 256×诅咒层级)/256，负数 0% + |z| 数字）")
            .define("curseBossbar", true);

    public static final ModConfigSpec.BooleanValue SAFE_ZONE = BUILDER
            .comment("探索限制 A6：|z|≤64 安全区内拦截自然类生成（放行刷怪笼/刷怪蛋/繁殖等玩家侧与机器间接生成）")
            .define("safeZone", true);

    public static final ModConfigSpec.BooleanValue GLOBAL_BEACON = BUILDER
            .comment("信标全图广播（A7，全局功能不受维度约束）：激活的信标效果广播至其所在维度的全体玩家")
            .define("globalBeacon", true);

    public static final ModConfigSpec.BooleanValue NICKNAME = BUILDER
            .comment("物品昵称（A8，全局功能）：按键打开对话框（默认不绑定），也可 /dbx nickname。"
                    + "id 绑定、纯显示层、全服生效；"
                    + "运行时关闭后已下发到客户端的昵称表保留至重连（接受瞬态）")
            .define("nickname", true);

    public static final ModConfigSpec.IntValue NICKNAME_PERMISSION = BUILDER
            .comment("使用物品昵称（按键对话框与 /dbx nickname）所需的权限等级（0=所有人，2=管理员）")
            .defineInRange("nicknamePermission", 0, 0, 4);

    public static final ModConfigSpec.BooleanValue LOOT_MAP_FILTER = BUILDER
            .comment("A10 战利品探索地图过滤（全局功能，不限维度）：任何战利品箱不再产出 Dungeons Arise"
                    + "（含七海）的探险地图与原版藏宝图（沉船/水下废墟的 buried_treasure 地图）；"
                    + "战利品表加载时移除条目 + 运行时兜底拦截（复合嵌套/GLM 路径产空堆），"
                    + "制图师村民交易不走战利品表、不受影响")
            .define("lootMapFilter", true);

    public static final ModConfigSpec.BooleanValue TREASURE_SHELL_X = BUILDER
            .comment("A11 藏宝箱贝壳 X 标记（全局功能，A10 藏宝图过滤的替代发现机制）：埋藏的宝藏生成时，"
                    + "用 dimblend_blocks:shell_marker 替换宝箱正上方沙滩表层的沙块摆成 X（中心+四对角，"
                    + "每臂独立找落点、只替换沙子，残臂跳过；宝箱裸露则整标放弃）；"
                    + "dimblend-blocks 缺席时静默跳过")
            .define("treasureShellX", true);

    public static final ModConfigSpec.BooleanValue DIESEL_ENGINE_BEHAVIOR = BUILDER
            .comment("B 板块：CDG 柴油机转速行为（点火爬梯 16rpm→每4秒+2→额定；额定后 80%~100% 随机波动并取整到整数 rpm，"
                    + "应力容量随转速同比缩放；运转中过载持续2秒确认后爆机掉落；忽略红石模拟调速——throttle 恒 1，B11）")
            .define("dieselEngineBehavior", true);

    public static final ModConfigSpec.BooleanValue DIESEL_PLACEMENT_GUARD = BUILDER
            .comment("B8：放置 CDG 柴油机（普通/组合式/巨型）时，所接 Create 动力网络转速必须已静止——"
                    + "接入运转中网络（含被其他机器带着转）的柴油机放行放置后下一 tick 破坏自身按 loot 掉落；"
                    + "不豁免创造模式；蓝图炮/setblock 等不经 EntityPlaceEvent 的路径不拦；需要 CDG 在场")
            .define("dieselEnginePlacementGuard", true);

    public static final ModConfigSpec.BooleanValue DIESEL_ENGINE_OVERLOAD = BUILDER
            .comment("Diesel engine overload penalty; default true. Disabling clears pending overload fuses without changing RPM behavior.")
            .define("dieselEngineOverload", true);

    public static final ModConfigSpec.IntValue DIESEL_LOAD_GRACE_SECONDS = BUILDER
            .comment("B 板块读档宽限：进世界（开服）后这么多秒内柴油机不判爆——不累计过载确认、"
                    + "已在走的引信倒计时也暂停，宽限过后重新累计。读档时 Create 动力网络仍在重建，"
                    + "实时账本这几秒内不可信（0=关闭宽限）")
            .defineInRange("dieselLoadGraceSeconds", 5, 0, 60);

    public static final ModConfigSpec.BooleanValue DIESEL_OVERLOAD_PROBE = BUILDER
            .comment("B 板块诊断探针：柴油机疑似过载（缓存 overStressed 为真）时在日志输出 [CDG-PROBE] 行——"
                    + "疑似开始/结束、网络账本快照、成员逐项明细、网络最近账本事件回放；点引信时 WARN 完整转储。"
                    + "只读不改行为，定位完毕后可关")
            .define("dieselOverloadProbe", true);

    public static final ModConfigSpec.IntValue DIESEL_ENGINE_FUEL_TANK_MB = BUILDER
            .comment("B10：CDG 小号（普通）与中号（组合式）柴油机燃料容量（mB，默认 100，范围 1-16000；"
                    + "1000 恢复 CDG 原生）。大号（巨型）100mB 为 CDG 原样、不受此键影响。"
                    + "生效机制：双端容量均经构造器定型（普通 addBehaviours / 组合式构造器）——新放置/"
                    + "区块重进（BE 重建）生效；组合式 read 重断言走客户端同步包路径（clientPacket=true），"
                    + "防客户端容量与服务端分叉。超出新容量的存油保留烧完为止、不能再补到容量以上。"
                    + "需要 CDG 在场")
            .defineInRange("dieselEngineFuelTankMb", 100, 1, 16000);

    public static final ModConfigSpec.BooleanValue STEAM_ENGINE_OVERLOAD = BUILDER
            .comment("H 板块：Create 蒸汽引擎过载两阶段（过载持续 16 秒内每秒警告音 + 云粒子，解除即停；"
                    + "满 16 秒断开传动杆掉落 + 排气音 1 次 + 8 秒云粒子，过载解除不中断）")
            .define("steamEngineOverload", true);

    public static final ModConfigSpec.BooleanValue ELECTRIC_MOTOR_BEHAVIOR = BUILDER
            .comment("D 板块：CCA 电动马达（反转红石语义：信号在场=运转、无信号=停转；"
                    + "信号 1-15 映射 4rpm~面板额定；耗电改纯线性无待机下限；"
                    + "D7 过载锁存：kinetic 过载且|面板|>64且运转中锁信号冻输出、耗电×2、播过载音/粒子，过载恢复或FE耗尽重置）。"
                    + "开启将改变 CCA 原版'红石=停转'语义，既有红石装置请注意")
            .define("electricMotorBehavior", true);

    public static final ModConfigSpec.BooleanValue ELECTRIC_MOTOR_OVERLOAD = BUILDER
            .comment("Electric motor overload penalty; default true. Independent of redstone and energy behavior.")
            .define("electricMotorOverload", true);

    public static final ModConfigSpec.BooleanValue ALTERNATOR_IDLE_DRAIN = BUILDER
            .comment("D 板块 D5：CCA 交流发电机无有效转速输入（本 tick 不产电：停转 0rpm /"
                    + " 过载·冻结网络读数归零 / 最低转速门未满足）时内部储存 FE 自行流失"
                    + "（每 tick 250、约每秒 5000，扣到 0 为止；有输入产电时不流失）")
            .define("alternatorIdleDrain", true);

    public static final ModConfigSpec.BooleanValue ALTERNATOR_OVERSTRESS_LATCH = BUILDER
            .comment("D 板块 D8：CCA 交流发电机过载解列锁存——kinetic 网络过载且被驱动中时"
                    + "本机应力需求强制归零并锁定（网络即时解负），播 motor_overstress 音效"
                    + "（每 20 tick、音量 0.5）与 enchanted_hit 粒子（每 tick×5）；锁存期"
                    + "FE 产出也锁 0（2026-10-15 拍板；只锁产出、不锁输出，buffer 存量继续"
                    + "流出直至放空）；理论转速与锁存时不同（含停机归零）即恢复"
                    + "（2026-10-15 拍板，原\"归零才恢复\"作废）。")
            .define("alternatorOverstressLatch", true);

    public static final ModConfigSpec.BooleanValue SIMURAIL_PROTECT = BUILDER
            .comment("E 板块：Simurail 自带方块防爆防拆，仅创造模式可拆改；生存模式可右键打开物理转向架设置，不能用扳手拆卸。"
                    + "例外：create:non_breakable tag 补强为静态数据包资源，不受本开关控制（Create 机械侧防护始终生效）")
            .define("simurailProtect", true);

    public static final ModConfigSpec.BooleanValue COUPLER_REDSTONE = BUILDER
            .comment("E 板块 E5：取消自动车钩的红石信号解锁（true=红石信号不再断开车钩；贯通框不受影响）")
            .define("couplerRedstone", true);

    public static final ModConfigSpec.BooleanValue ALLOW_SURVIVAL_COUPLER_INTERACTION = BUILDER
            .comment("Allow Survival players to right-click automatic couplers; default false. Other block protections remain active.")
            .define("allowSurvivalCouplerInteraction", false);

    public static final ModConfigSpec.BooleanValue ASSEMBLER_GUARD = BUILDER
            .comment("F 板块 F1：simulated 物理组装器禁止摆放（生存模式禁、创造模式放行；"
                    + "放行放置后本 tick 末破坏自身返还：真人回背包、机器放置掉落；需要 simulated 在场）")
            .define("assemblerGuard", true);

    public static final ModConfigSpec.BooleanValue PORTABLE_ENGINE_EXCLUSIVITY = BUILDER
            .comment("F 板块 F2：simulated 便携引擎同 Create 动力网络互斥（16 色全算同类；按连通判、与转速无关，"
                    + "零转速静置网络同样生效，通电离合器隔开的两侧不算同网）；"
                    + "实体放置的新引擎若已连着其他引擎则本 tick 末自毁；其余任何接通（传动杆/齿轮/皮带接起来、离合器切换、"
                    + "蓝图炮/装置解体、区块加载、起转）本 tick 末随机留一台、其余全毁；"
                    + "一律按 loot 掉落、无豁免；需要 simulated+create 在场）")
            .define("portableEngineExclusivity", true);

    public static final ModConfigSpec.BooleanValue TRAIN_SOUNDS = BUILDER
            .comment("E 板块 E2-E4：转向架行驶轨道节奏循环（音量/音调随速度）+ 刹车/松闸气阀触发音。"
                    + "需要 Simurail 在场；客户端经 ConfigSync 同步后生效")
            .define("trainSounds", true);

    public static final ModConfigSpec.DoubleValue TRAIN_BRAKE_SOUND_SPEED_THRESHOLD = BUILDER
            .comment("E3 施闸音速度阈值（m/s，0-64，默认 2.0，/dbx 面板步进 0.1）："
                    + "仅 |visualSpeed| 严格大于阈值时播放；松闸音不受此门控")
            .defineInRange("trainBrakeSoundSpeedThreshold", 2.0D, 0.0D, 64.0D);

    public static final ModConfigSpec.BooleanValue WIDE_GAUGE_PARTICLES = BUILDER
            .comment("E 板块 E6：幻纱宽轨上的车架在其 y-1 5x5 范围生成下落的末地烛粒子（纯客户端视觉）。需要 Simurail 在场")
            .define("wideGaugeParticles", true);

    // 键名 offStructureTeleport（v1.8 E7 离结构传送）随 v1.18 机制重做废弃：旧键被自动清除，
    // 新机制（无人值守刹车锁存）用 trainDeadmanBrake / trainDeadmanRadius 两键
    public static final ModConfigSpec.BooleanValue TRAIN_DEADMAN_BRAKE = BUILDER
            .comment("E 板块 E7'：旋转维度内编组在动（存在 >4 m/s 车架）且编组上离每个玩家最近的"
                    + "车架都超出半径时，该编组全列车架强制满刹锁存；任意车架红石信号等级变化"
                    + "即解除所在编组全部紧急刹车（上升、下降、正强度间变化均有效）。"
                    + "锁存期间强制驱动网络过载（按网络容量×1.5 反算转向架应力），触发动力源"
                    + "柴油机过载惩罚——掉下车=事故。需要 Simurail+Sable 在场")
            .define("trainDeadmanBrake", true);

    public static final ModConfigSpec.DoubleValue TRAIN_DEADMAN_RADIUS = BUILDER
            .comment("E 板块 E7' 配套：无人值守判定半径（格，8-128，默认 32）——对整列编组量距离，"
                    + "编组上离玩家最近的车架在该半径内即算已值守（不看该架速度）。经 /dbx 面板可调")
            .defineInRange("trainDeadmanRadius", 32.0D, 8.0D, 128.0D);

    public static final ModConfigSpec.BooleanValue TRAIN_LATERAL_FORCE = BUILDER
            .comment("E 板块 E8：车架速度大于 4 m/s 时以 速度/20 的概率施加横向力；默认每 2 秒抽签、1200 pN，可分别调节"
                    + "（垂直车架朝向、左右随机、持续 10 tick，作用于车架位置）。全维度；需要 Simurail 在场")
            .define("trainLateralForce", true);

    // 键名由 trainFixedBrakeSignal（v1.14 布尔：任意正强度一律按 1 级计）改为 trainBrakeSignalCap：
    // 旧布尔语义是上限 1 的特例，上限可调后开关与上限两键语义重复故合并；旧键被自动清除、新键取默认 1（v1.14 行为不变）
    public static final ModConfigSpec.IntValue TRAIN_BRAKE_SIGNAL_CAP = BUILDER
            .comment("E 板块 E9：转向架可接收红石信号的最大强度（级，1-15）：不高于上限的信号原样生效，"
                    + "超过上限的强度统一压到上限（刹车力度与动力网络应力同按修正后强度；"
                    + "原行为为随信号强度线性，上限 15 即等效关闭恢复原版）。全维度；需要 Simurail 在场；经 /dbx 面板可调")
            .defineInRange("trainBrakeSignalCap", 1, 1, 15);

    public static final ModConfigSpec.DoubleValue TRAIN_SHAKE_INTERVAL_SECONDS = BUILDER
            .comment("Random train shaking draw interval in seconds; default 2.0. 0 disables draws, otherwise 0.1-60.0 in steps of 0.1.")
            .defineInRange("trainShakeIntervalSeconds", 2.0D, 0.0D, 60.0D);

    public static final ModConfigSpec.IntValue TRAIN_SHAKE_FORCE_PN = BUILDER
            .comment("Additional lateral force per random train shaking in pN; default 1200. Range 0-10000, step 1.")
            .defineInRange("trainShakeForcePn", 1200, 0, 10000);

    public static final ModConfigSpec.BooleanValue VILLAGER_MASTER = BUILDER
            .comment("G 板块 G1：旋转维度内村民首次获得职业时定大师并补全全部交易，每条只能成交一次、不补货，掉工作站点不掉职业；"
                    + "无业新生保持无业不指派，存量不追溯，傻子/婴儿/流浪商人跳过")
            .define("villagerMaster", true);

    public static final ModConfigSpec.BooleanValue STRUCTURE_BED = BUILDER
            .comment("G 板块 G2：旋转维度内只有 sable 结构上的床可交互（睡/设重生点）；破坏不管，创造模式豁免")
            .define("structureBed", true);

    public static final ModConfigSpec.BooleanValue PORTAL_BAN = BUILDER
            .comment("G 板块 G4：旋转维度内禁一切传送门生成与跨维度旅行；折跃门方块全局禁传送（留块）。回家通道不管")
            .define("portalBan", true);

    public static final ModConfigSpec.BooleanValue COPPER_SINK_INFINITE = BUILDER
            .comment("铜水槽无限水：默认关闭，关闭后沿用 Create Fluid 的 2000mB 有限储罐；开启还需要上游 infiniteWater 配置允许")
            .define("copperSinkInfinite", false);

    public static final ModConfigSpec.BooleanValue LIMITED_WATER = BUILDER
            .comment("G 板块 G3 有限水：旋转维度内每次写入源水（water8），先看以该格为球心 8 格内最近的非旁观玩家——"
                    + "该玩家为创造模式则不检测、原样写入；其余情况（生存/冒险，或 8 格内无人）检测水平四邻："
                    + "源水或冰/浮冰/蓝冰 ≥2 格保留源水，0-1 格改写为流动 water7。流动水、含水方块、霜冰不计；"
                    + "只拦新写入，已存在的水与世界生成不动；Sable 载具上的玩家与方块按真实世界坐标计距")
            .define("limitedWater", true);

    public static final ModConfigSpec.BooleanValue ICE_PLACEMENT_BAN = BUILDER
            .comment("G 板块 G3 配套：旋转维度内生存模式玩家与 Create 机械手不能放置冰/浮冰/蓝冰；"
                    + "其它模式玩家、其它机器/假玩家不拦")
            .define("icePlacementBan", true);

    public static final ModConfigSpec.BooleanValue SABLE_STRUCTURE_VOID_FIT = BUILDER
            .comment("G 板块 G3 配套：旋转维度内给 Sable 载具的世界投影体积拟合 structure_void，"
                    + "水不会流进行驶中的载具（原版流体永不占据 structure_void）。"
                    + "拟合格不入存档（区块序列化时临时剥成空气再还原）；"
                    + "关闭后调度整体暂停：不再新增拟合、也不再检测载具消失——已加载区块内的"
                    + "存量空位保留到区块卸载重载（落盘时被剥离）或重新打开开关后由调度清理；"
                    + "需要 Sable 在场")
            .define("sableStructureVoidFit", true);

    public static final ModConfigSpec.IntValue SABLE_VOID_FIT_REFIT_TICKS = BUILDER
            .comment("结构空位拟合的扫描周期（tick）：默认 5 即每秒 4 次位姿检查；"
                    + "位姿变化超阈值才重算体素，每 20 个周期强制自愈一次")
            .defineInRange("sableVoidFitRefitTicks", 5, 1, 40);

    public static final ModConfigSpec.BooleanValue SABLE_LAVA_MELT = BUILDER
            .comment("G 板块 G6（结构空位拟合配对）：旋转维度内岩浆/岩浆流按"
                    + " sableLavaMeltSpeed 倍徒手速度（默认 4 倍）"
                    + "熔毁位于 Sable 结构上的方块并使其掉落（同格及投影体积 0.25 容差内"
                    + "持续浇淋累计进度，接触中断清零；硬度只决定速度不设阈值——黑曜石级"
                    + "默认倍率下约 62.5 秒才熔、硬度负免疫；#dimblend_experience:lava_melt_immune 标签"
                    + "（继承 create:non_breakable，转向架/自动车钩等机械不可破坏部件）免疫；"
                    + "掉落走方块自身规则、容器内容物并入；"
                    + "熔毁进行中在被浇方块的世界侧投影位置每轮扫描播一次 block.fire.extinguish）。"
                    + "开启时拟合不再驱逐熔岩格（熔岩浇穿船体逐步推进），"
                    + "关闭后本特性停摆、熔岩与水同被拟合驱逐；需要 Sable 在场")
            .define("sableLavaMelt", true);

    public static final ModConfigSpec.DoubleValue SABLE_LAVA_MELT_SPEED = BUILDER
            .comment("G6 熔毁速度倍率（相对徒手挖掘）：默认 4.0 即徒手 16 秒的方块浇 4 秒熔掉；"
                    + "范围 0.5-64.0、步进 0.1（/dbx 调参面板同步可调，运行中改值即时生效，"
                    + "已累进度不变、后续按新速率累计）；0 即停手的语义由 sableLavaMelt 开关承担")
            .defineInRange("sableLavaMeltSpeed", LavaMeltMath.DEFAULT_SPEED_MULTIPLIER, 0.5D, 64.0D);

    public static final ModConfigSpec.BooleanValue ENDER_STORAGE_STRUCTURE_ONLY = BUILDER
            .comment("G 板块 G5：旋转维度内末影箱/末影罐（enderstorage:ender_chest / ender_tank）只能放在 sable 结构上；"
                    + "放在结构外则放行放置后本 tick 末破坏返还（真人回背包、机器放置掉落，频率保留）；"
                    + "创造模式豁免；需要 enderstorage 在场，sable 缺席时放行")
            .define("enderStorageStructureOnly", true);

    public static final ModConfigSpec.BooleanValue COPYCAT_OBSIDIAN_HARDNESS = BUILDER
            .comment("伪装硬度（全局功能，不限维度）：Create / Copycats+ / Create Connected"
                    + " 的全体伪装方块统一黑曜石硬度（挖掘/爆抗/末影龙凋灵免疫，含钻石镐采集校验）。"
                    + "与 dimblend-blocks 的 C0 共存（两者都装时以本 BlockState 拦截为准，行为一致）")
            .define("copycatObsidianHardness", true);

    public static final ModConfigSpec.BooleanValue ITEM_DRAIN_IRRIGATION = BUILDER
            .comment("分液池保湿（全局功能，不限维度）：水箱只要有水，就给周围同层土壤保湿；"
                    + "经 NeoForge FarmlandWaterManager 票据维持 MOISTURE（无催熟，催熟见 itemDrainGrowth*）；"
                    + "保湿不消耗水量、无最低水量门槛；建票时把范围内耕地主动拉满一次，"
                    + "其余时间走原版耕地 randomTick 节奏；空水箱/非水时摘票，回水后自动重建")
            .define("itemDrainIrrigation", true);

    // 键名由 itemDrainIrrigationRadius（旧默认 5=11x11）改为 itemDrainCoverageRadius：
    // NeoForge 对已存在的键保留旧值，不改名则老配置永远停在 11x11；改名后旧键被自动清除、新键取默认 3
    public static final ModConfigSpec.IntValue ITEM_DRAIN_COVERAGE_RADIUS = BUILDER
            .comment("分液池保湿/催熟半径（格）：默认 3 即 7x7；改动后下次重建票据时生效")
            .defineInRange("itemDrainCoverageRadius", 3, 1, 8);

    public static final ModConfigSpec.BooleanValue ITEM_DRAIN_GROWTH = BUILDER
            .comment("分液池催熟（全局功能，不限维度）：存水不低于阈值时，每周期随机挑范围内一株植物"
                    + "走原版骨粉逻辑催熟（一切骨粉目标都算，含草方块/苔藓蔓延型）；"
                    + "骨粉生效才扣水，范围内无目标或骨粉判定未生效时不扣；"
                    + "范围内无可催熟作物时倒计时停走，作物出现后自动恢复计时")
            .define("itemDrainGrowth", true);

    // 键名由 itemDrainGrowthIntervalTicks（旧默认 1200=60 秒）改为按秒的 itemDrainGrowthIntervalSeconds，
    // 理由同 itemDrainCoverageRadius：老配置的旧值不会被新默认覆盖
    public static final ModConfigSpec.IntValue ITEM_DRAIN_GROWTH_INTERVAL_SECONDS = BUILDER
            .comment("分液池催熟周期（秒）；默认 30")
            .defineInRange("itemDrainGrowthIntervalSeconds", 30, 1, 600);

    public static final ModConfigSpec.IntValue ITEM_DRAIN_GROWTH_COST_MB = BUILDER
            .comment("分液池每次催熟生效扣除的水量（mb，1 桶=1000）；0=免费催熟")
            .defineInRange("itemDrainGrowthCostMb", 10, 0, 1500);

    public static final ModConfigSpec.IntValue ITEM_DRAIN_GROWTH_MIN_MB = BUILDER
            .comment("分液池存水低于此水量（mb）时停止催熟（保湿不受影响）；回水后自动恢复")
            .defineInRange("itemDrainGrowthMinMb", 200, 0, 1500);

    public static final ModConfigSpec.BooleanValue ITEM_DRAIN_PIPE_REFILL = BUILDER
            .comment("分液池管道补水（全局功能，不限维度）：开放分液池注入口，允许流体管道/泵向池内注水"
                    + "（原版机械动力禁止管道灌入分液池、且正上方无流体接口，本开关两者都放开；侧面/底面/顶面均可接管道）；"
                    + "关闭后恢复原版行为")
            .define("itemDrainPipeRefill", true);

    public static final ModConfigSpec.ConfigValue<String> ROTATING_DIMENSION_ID = BUILDER
            .comment("探索限制规则（A1-A6）生效的维度 id，必须带命名空间（缺命名空间将回退默认 dimblend:rotating 并记错误日志）")
            .define("rotatingDimensionId", "dimblend:rotating");

    public static final ModConfigSpec.BooleanValue SABLE_HEADLIGHT = BUILDER
            .comment("Render custom headlight cones for amethyst-activated dndecor frontlights in the world and on Sable vehicles (without Iris).")
            .define("sableHeadlight", true);
    public static final ModConfigSpec.DoubleValue SABLE_HEADLIGHT_RANGE = BUILDER
            .comment("Maximum headlight reach in blocks.")
            .defineInRange("sableHeadlightRange", 24.0D, 4.0D, 64.0D);
    public static final ModConfigSpec.DoubleValue SABLE_HEADLIGHT_ANGLE = BUILDER
            .comment("Full headlight cone angle in degrees.")
            .defineInRange("sableHeadlightAngle", 50.0D, 10.0D, 120.0D);
    public static final ModConfigSpec.DoubleValue SABLE_HEADLIGHT_INTENSITY = BUILDER
            .comment("Headlight intensity, before soft exposure limiting.")
            .defineInRange("sableHeadlightIntensity", 1.0D, 0.0D, 3.0D);
    public static final ModConfigSpec.BooleanValue SABLE_HEADLIGHT_FOG = BUILDER
            .comment("Render volumetric headlight haze.")
            .define("sableHeadlightFog", true);
    public static final ModConfigSpec.DoubleValue SABLE_HEADLIGHT_FOG_DENSITY = BUILDER
            .comment("Headlight haze density; zero skips the volumetric pass.")
            .defineInRange("sableHeadlightFogDensity", 0.35D, 0.0D, 2.0D);
    public static final ModConfigSpec.IntValue SABLE_HEADLIGHT_FOG_SAMPLES = BUILDER
            .comment("Ray-march samples per headlight at half width and half height.")
            .defineInRange("sableHeadlightFogSamples", 8, 4, 24);
    public static final ModConfigSpec.BooleanValue SABLE_HEADLIGHT_OCCLUSION = BUILDER
            .comment("Block-grid shadows for headlight surface light and haze (walls, pillars, vehicles).")
            .define("sableHeadlightOcclusion", true);

    // 列车汽笛音高：每个音效 × 汽笛尺寸（小/中/大）独立可调；引擎侧取值范围与
    // SoundEngine.calculatePitch 钳制区间一致（0.5~2.0），默认小 1.5/中 1.0/大 0.5
    // （小笛高音、大笛低音）
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_STEAM_PITCH_SMALL = BUILDER
            .comment("列车汽笛·蒸汽：小尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornSteamPitchSmall", 1.5D, 0.5D, 2.0D);
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_STEAM_PITCH_MEDIUM = BUILDER
            .comment("列车汽笛·蒸汽：中尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornSteamPitchMedium", 1.0D, 0.5D, 2.0D);
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_STEAM_PITCH_LARGE = BUILDER
            .comment("列车汽笛·蒸汽：大尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornSteamPitchLarge", 0.5D, 0.5D, 2.0D);
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_DIESEL_PITCH_SMALL = BUILDER
            .comment("列车汽笛·柴油：小尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornDieselPitchSmall", 1.5D, 0.5D, 2.0D);
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_DIESEL_PITCH_MEDIUM = BUILDER
            .comment("列车汽笛·柴油：中尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornDieselPitchMedium", 1.0D, 0.5D, 2.0D);
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_DIESEL_PITCH_LARGE = BUILDER
            .comment("列车汽笛·柴油：大尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornDieselPitchLarge", 0.5D, 0.5D, 2.0D);
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_ELEC_PITCH_SMALL = BUILDER
            .comment("列车汽笛·电动：小尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornElecPitchSmall", 1.5D, 0.5D, 2.0D);
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_ELEC_PITCH_MEDIUM = BUILDER
            .comment("列车汽笛·电动：中尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornElecPitchMedium", 1.0D, 0.5D, 2.0D);
    public static final ModConfigSpec.DoubleValue TRAIN_HORN_ELEC_PITCH_LARGE = BUILDER
            .comment("列车汽笛·电动：大尺寸汽笛的播放音高（0.5~2.0）")
            .defineInRange("trainHornElecPitchLarge", 0.5D, 0.5D, 2.0D);

    static final ModConfigSpec SPEC = BUILDER.build();

    /**
     * 配置是否已加载。SERVER 配置在同步/加载完成前 {@code Value#get()} 会抛
     * {@link IllegalStateException}（"Cannot get config value before config is loaded"），
     * capability provider 等任意端任意时机都可能被查询的路径必须先判本方法。
     */
    public static boolean isLoaded() {
        return SPEC.isLoaded();
    }

    public static void save() {
        SPEC.save();
    }

    private Config() {
    }
}
